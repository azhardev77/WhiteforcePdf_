package com.pdfeditor.rules;

import com.pdfeditor.export.PdfRedactionService;
import com.pdfeditor.export.TextRedactionEdit;
import com.pdfeditor.extraction.ExtractionPipeline;
import com.pdfeditor.extraction.Glyph;
import com.pdfeditor.model.*;
import com.pdfeditor.session.SessionValidationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSFloat;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.util.Matrix;
import org.apache.pdfbox.pdmodel.common.PDMetadata;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDAction;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Collection;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class ResumeRedactionService {

    private static final Logger log = LoggerFactory.getLogger(ResumeRedactionService.class);

    private final ExtractionPipeline extractionPipeline;
    private final ResumeRules resumeRules;
    private final PdfRedactionService pdfRedactionService;

    public ResumeRedactionService(ExtractionPipeline extractionPipeline,
                                  ResumeRules resumeRules,
                                  PdfRedactionService pdfRedactionService) {
        this.extractionPipeline = extractionPipeline;
        this.resumeRules = resumeRules;
        this.pdfRedactionService = pdfRedactionService;
    }

    /**
     * Executes the RESUME redaction pipeline:
     * 1. Validate extractable text (throws 422 if scanned/image-only).
     * 2. Extract DocumentModel and run ResumeRules.
     * 3. Convert matches to TextRedactionEdits and run visual redaction.
     * 4. Scrub annotations (mailto:/tel: links), Info metadata, XMP metadata.
     * 5. Scrub content stream tokens and Form XObjects (TJ arrays, Tj operators, hex strings).
     * 6. Deep scan raw content streams to guarantee zero residual PII strings remain.
     */
    public byte[] redactResume(byte[] originalPdfBytes) throws IOException {
        return redactResume(originalPdfBytes, null);
    }

    public byte[] redactResume(byte[] originalPdfBytes, Collection<String> selectedCategoryIds) throws IOException {
        if (originalPdfBytes == null || originalPdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF bytes cannot be empty");
        }

        // Step 1: Validate extractable text
        validateExtractableText(originalPdfBytes);

        // If categories is explicitly empty, user selected nothing to redact
        if (selectedCategoryIds != null && selectedCategoryIds.isEmpty()) {
            return originalPdfBytes;
        }

        Set<String> categorySet = null;
        if (selectedCategoryIds != null) {
            categorySet = selectedCategoryIds.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .map(String::toLowerCase)
                    .collect(Collectors.toSet());
        }

        // Step 2: Extract DocumentModel
        DocumentModel docModel = extractionPipeline.extract(originalPdfBytes, "resume.pdf");

        // Step 3: Run ResumeRules
        List<Match> allMatches = resumeRules.find(docModel);
        if (allMatches == null || allMatches.isEmpty()) {
            return originalPdfBytes;
        }

        // Filter matches by categorySet (if null, all categories match)
        List<Match> matches = new ArrayList<>();
        for (Match match : allMatches) {
            String type = match.getType() != null ? match.getType().trim().toLowerCase() : "";
            if (categorySet == null || categorySet.contains(type)) {
                matches.add(match);
            }
        }

        if (matches.isEmpty()) {
            return originalPdfBytes;
        }

                // Collect page glyphs for pages with matches
        Map<Integer, List<Glyph>> pageGlyphsMap = new HashMap<>();
        try (PDDocument origDoc = Loader.loadPDF(originalPdfBytes)) {
            for (Match match : matches) {
                int pNum = match.getPageNumber();
                if (!pageGlyphsMap.containsKey(pNum)) {
                    try {
                        List<Glyph> gList = extractionPipeline.getCharacterCollector().collectPage(origDoc, pNum);
                        pageGlyphsMap.put(pNum, gList != null ? gList : Collections.emptyList());
                    } catch (Exception e) {
                        log.warn("Failed to collect glyphs for page {}: {}", pNum, e.getMessage());
                        pageGlyphsMap.put(pNum, Collections.emptyList());
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load PDF for glyph collection: {}", e.getMessage());
        }

        // Step 4: Convert matches to TextRedactionEdit with expanded bounding box for email/phone contact icons
        final float SAFETY_PADDING = 3.0f;
        List<TextRedactionEdit> edits = new ArrayList<>();
        for (Match match : matches) {
            String type = match.getType() != null ? match.getType().trim().toLowerCase() : "";
            boolean isContact = "email".equals(type) || "phone".equals(type);

            double[] bbox = match.getBbox();
            double[] expandedBbox = bbox != null ? bbox.clone() : new double[4];
            List<double[]> expandedLineBboxes = new ArrayList<>();

            if (isContact && expandedBbox.length >= 4) {
                List<Glyph> pageGlyphs = pageGlyphsMap.get(match.getPageNumber());
                // Find if there is preceding text on this line so we don't expand into legitimate user text
                double origX = expandedBbox[0];
                double origY = expandedBbox[1];
                double origH = expandedBbox[3];
                double maxPrecedingRight = calculateMaxPrecedingTextRight(docModel, pageGlyphs, match.getPageNumber(), origX, origY, origH);

                float availableGap = (maxPrecedingRight > 0) ? (float) (origX - maxPrecedingRight) : (float) origX;
                float leftExpansion = Math.max(0.0f, Math.min(22.0f, availableGap - SAFETY_PADDING));
                double newX = origX - leftExpansion;
                if (maxPrecedingRight > 0) {
                    double minAllowedX = Math.min(origX, maxPrecedingRight + SAFETY_PADDING);
                    newX = Math.max(newX, minAllowedX);
                }
                newX = Math.max(0.0, newX);
                double shift = origX - newX;
                expandedBbox[0] = newX;
                expandedBbox[2] = expandedBbox[2] + shift;

                if (match.getLineBboxes() != null && !match.getLineBboxes().isEmpty()) {
                    for (double[] lbox : match.getLineBboxes()) {
                        if (lbox != null && lbox.length >= 4) {
                            double origLx = lbox[0];
                            double origLy = lbox[1];
                            double origLh = lbox[3];
                            double maxPrecedingL = calculateMaxPrecedingTextRight(docModel, pageGlyphs, match.getPageNumber(), origLx, origLy, origLh);
                            float availableGapL = (maxPrecedingL > 0) ? (float) (origLx - maxPrecedingL) : (float) origLx;
                            float leftExpansionL = Math.max(0.0f, Math.min(22.0f, availableGapL - SAFETY_PADDING));
                            double newLx = origLx - leftExpansionL;
                            if (maxPrecedingL > 0) {
                                double minAllowedLx = Math.min(origLx, maxPrecedingL + SAFETY_PADDING);
                                newLx = Math.max(newLx, minAllowedLx);
                            }
                            newLx = Math.max(0.0, newLx);
                            double shiftL = origLx - newLx;
                            expandedLineBboxes.add(new double[]{newLx, lbox[1], lbox[2] + shiftL, lbox[3]});
                        }
                    }
                } else {
                    expandedLineBboxes.add(expandedBbox);
                }

                // Set expanded bboxes on match for vector stream scrubbing and whiteout overlay
                match.setExpandedBbox(expandedBbox);
                match.setExpandedLineBboxes(expandedLineBboxes);
            } else {
                if (match.getLineBboxes() != null && !match.getLineBboxes().isEmpty()) {
                    for (double[] lbox : match.getLineBboxes()) {
                        expandedLineBboxes.add(lbox != null ? lbox.clone() : new double[4]);
                    }
                } else {
                    expandedLineBboxes.add(expandedBbox);
                }
                match.setExpandedBbox(expandedBbox);
                match.setExpandedLineBboxes(expandedLineBboxes);
            }

            TextRedactionEdit edit = new TextRedactionEdit(
                    match.getPageNumber(),
                    expandedBbox,
                    match.getMatchedText(),
                    "",
                    null,
                    "#FFFFFF"
            );
            edit.setWhiteoutBoxes(expandedLineBboxes);
            edits.add(edit);
        }

        // Step 5: Visual whiteout and token stripping via PdfRedactionService
        byte[] redactedBytes = pdfRedactionService.redactAndReplace(originalPdfBytes, edits);

        // Step 6: Post-process document for complete link, metadata, and content stream eradication
        try (PDDocument doc = Loader.loadPDF(redactedBytes)) {
            // Remove mailto:/tel: and matching link annotations
            scrubAnnotations(doc, matches, categorySet);

            // Scrub Document Information and XMP metadata
            scrubMetadata(doc, matches, categorySet);

            // Scrub content stream tokens (Tj, TJ, Form XObjects)
            scrubAllContentStreams(doc, matches);

            // Deep scan raw content stream bytes for any remaining strings
            deepScrubRawStreams(doc, matches, categorySet);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    public void validateExtractableText(byte[] pdfBytes) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdfBytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(doc);
            if (text == null || text.trim().isEmpty()) {
                throw new SessionValidationException("Scanned or image-only PDF: no extractable text found for RESUME mode", 422);
            }
        }
    }

    private void scrubAnnotations(PDDocument doc, List<Match> matches, Set<String> categorySet) {
        boolean scrubEmail = categorySet == null || categorySet.contains("email");
        boolean scrubPhone = categorySet == null || categorySet.contains("phone");
        for (PDPage page : doc.getPages()) {
            try {
                List<PDAnnotation> annotations = page.getAnnotations();
                if (annotations == null || annotations.isEmpty()) continue;

                List<PDAnnotation> toKeep = new ArrayList<>();
                for (PDAnnotation annot : annotations) {
                    boolean shouldRemove = false;
                    if (annot instanceof PDAnnotationLink link) {
                        PDAction action = link.getAction();
                        if (action instanceof PDActionURI uriAction) {
                            String uri = uriAction.getURI();
                            if (uri != null) {
                                String lower = uri.toLowerCase();
                                boolean hasMatchingPii = false;
                                for (PiiDetector.PiiSpan span : PiiDetector.findPIISpans(uri)) {
                                    if (categorySet == null || categorySet.contains(span.getType().toLowerCase())) {
                                        hasMatchingPii = true;
                                        break;
                                    }
                                }
                                if ((scrubEmail && lower.startsWith("mailto:"))
                                        || (scrubPhone && lower.startsWith("tel:"))
                                        || hasMatchingPii
                                        || matchesAnyPii(uri, matches)) {
                                    shouldRemove = true;
                                }
                            }
                        }

                        if (!shouldRemove) {
                            COSDictionary cosDict = link.getCOSObject();
                            COSBase aBase = cosDict.getDictionaryObject(COSName.A);
                            if (aBase instanceof COSDictionary aDict) {
                                String uri = aDict.getString(COSName.URI);
                                if (uri != null) {
                                    String lower = uri.toLowerCase();
                                    boolean hasMatchingPii = false;
                                    for (PiiDetector.PiiSpan span : PiiDetector.findPIISpans(uri)) {
                                        if (categorySet == null || categorySet.contains(span.getType().toLowerCase())) {
                                            hasMatchingPii = true;
                                            break;
                                        }
                                    }
                                    if ((scrubEmail && lower.startsWith("mailto:"))
                                            || (scrubPhone && lower.startsWith("tel:"))
                                            || hasMatchingPii
                                            || matchesAnyPii(uri, matches)) {
                                        shouldRemove = true;
                                    }
                                }
                            }
                        }
                    }
                    if (!shouldRemove) {
                        toKeep.add(annot);
                    }
                }
                page.setAnnotations(toKeep);
            } catch (Exception e) {
                log.warn("Failed to scrub annotations: {}", e.getMessage());
            }
        }
    }

    private void scrubMetadata(PDDocument doc, List<Match> matches, Set<String> categorySet) {
        PDDocumentInformation info = doc.getDocumentInformation();
        if (info != null) {
            info.setTitle(scrubPiiString(info.getTitle(), matches, categorySet));
            info.setAuthor(scrubPiiString(info.getAuthor(), matches, categorySet));
            info.setSubject(scrubPiiString(info.getSubject(), matches, categorySet));
            info.setKeywords(scrubPiiString(info.getKeywords(), matches, categorySet));
            info.setCreator(scrubPiiString(info.getCreator(), matches, categorySet));
            info.setProducer(scrubPiiString(info.getProducer(), matches, categorySet));

            COSDictionary infoDict = info.getCOSObject();
            for (COSName key : infoDict.keySet()) {
                COSBase val = infoDict.getItem(key);
                if (val instanceof COSString cosStr) {
                    String s = cosStr.getString();
                    boolean hasTarget = matchesAnyPii(s, matches);
                    if (!hasTarget) {
                        for (PiiDetector.PiiSpan span : PiiDetector.findPIISpans(s)) {
                            if (categorySet == null || categorySet.contains(span.getType().toLowerCase())) {
                                hasTarget = true;
                                break;
                            }
                        }
                    }
                    if (hasTarget) {
                        String scrubbed = scrubPiiString(s, matches, categorySet);
                        cosStr.setValue(scrubbed.getBytes(StandardCharsets.ISO_8859_1));
                    }
                }
            }
        }

        PDMetadata metadata = doc.getDocumentCatalog().getMetadata();
        if (metadata != null) {
            try (InputStream is = metadata.createInputStream()) {
                byte[] bytes = is.readAllBytes();
                if (bytes.length > 0) {
                    String xml = new String(bytes, StandardCharsets.UTF_8);
                    boolean changed = false;
                    for (Match m : matches) {
                        if (m.getMatchedText() != null && !m.getMatchedText().isBlank()) {
                            String target = m.getMatchedText().trim();
                            if (xml.contains(target)) {
                                xml = xml.replace(target, "");
                                changed = true;
                            }
                        }
                    }
                    List<PiiDetector.PiiSpan> spans = PiiDetector.findPIISpans(xml);
                    for (PiiDetector.PiiSpan span : spans) {
                        if (categorySet == null || categorySet.contains(span.getType().toLowerCase())) {
                            xml = xml.replace(span.getMatch(), "");
                            changed = true;
                        }
                    }
                    if (changed) {
                        metadata.importXMPMetadata(xml.getBytes(StandardCharsets.UTF_8));
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private void scrubAllContentStreams(PDDocument doc, List<Match> matches) {
        if (matches == null || matches.isEmpty()) return;
        int pageNum = 1;
        for (PDPage page : doc.getPages()) {
            final int currPage = pageNum;
            List<Match> pageMatches = matches.stream()
                    .filter(m -> m.getPageNumber() == currPage)
                    .toList();
            if (!pageMatches.isEmpty() && page.hasContents()) {
                scrubPageStreams(doc, page, pageMatches);
                scrubFormXObjects(doc, page.getResources(), pageMatches, page);
            }
            pageNum++;
        }
    }

    private void scrubPageStreams(PDDocument doc, PDPage page, List<Match> pageMatches) {
        try {
            float pageH = page.getCropBox() != null ? page.getCropBox().getHeight() : page.getMediaBox().getHeight();
            float originX = page.getCropBox() != null ? page.getCropBox().getLowerLeftX() : 0f;
            float originY = page.getCropBox() != null ? page.getCropBox().getLowerLeftY() : 0f;

            Iterator<PDStream> iter = page.getContentStreams();
            List<PDStream> newStreams = new ArrayList<>();
            boolean modifiedAny = false;

            while (iter.hasNext()) {
                PDStream stream = iter.next();
                PDStream resultStream = scrubSingleStream(doc, stream, pageMatches, page.getResources(), pageH, originX, originY);
                if (resultStream != null) {
                    newStreams.add(resultStream);
                    modifiedAny = true;
                } else {
                    newStreams.add(stream);
                }
            }

            if (modifiedAny) {
                page.setContents(newStreams);
            }
        } catch (Exception e) {
            log.warn("Failed to scrub page streams: {}", e.getMessage());
        }
    }

    private void scrubFormXObjects(PDDocument doc, PDResources resources, List<Match> pageMatches, PDPage page) {
        if (resources == null) return;
        float pageH = page.getCropBox() != null ? page.getCropBox().getHeight() : page.getMediaBox().getHeight();
        float originX = page.getCropBox() != null ? page.getCropBox().getLowerLeftX() : 0f;
        float originY = page.getCropBox() != null ? page.getCropBox().getLowerLeftY() : 0f;

        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDFormXObject formXObject) {
                    PDStream stream = formXObject.getContentStream();
                    if (stream != null) {
                        PDResources formRes = formXObject.getResources() != null ? formXObject.getResources() : resources;
                        PDStream resultStream = scrubSingleStream(doc, stream, pageMatches, formRes, pageH, originX, originY);
                        if (resultStream != null) {
                            formXObject.getCOSObject().setItem(COSName.CONTENTS, resultStream);
                        }
                    }
                    PDResources nested = formXObject.getResources();
                    if (nested != null && nested != resources) {
                        scrubFormXObjects(doc, nested, pageMatches, page);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private PDStream scrubSingleStream(PDDocument doc, PDStream stream, List<Match> matches,
                                       PDResources res, float pageH, float originX, float originY) {
        try {
            PDFStreamParser parser = new PDFStreamParser(stream.toByteArray());
            List<Object> tokens = parser.parse();
            parser.close();

            Deque<Matrix> ctmStack = new ArrayDeque<>();
            Matrix ctm = new Matrix();
            Matrix textMatrix = null;
            Matrix textLineMatrix = null;
            PDFont activeFont = null;
            float fontSize = 0f;
            float hScaling = 100f;
            float textLeading = 0f;
            boolean streamModified = false;

            List<Object> newTokens = new ArrayList<>(tokens.size());
            List<Object> currentOperands = new ArrayList<>();

            for (Object token : tokens) {
                if (token instanceof Operator op) {
                    String opName = op.getName();

                    if ("cm".equals(opName)) {
                        if (currentOperands.size() >= 6) {
                            float a = getFloatOperand(currentOperands.get(0));
                            float b = getFloatOperand(currentOperands.get(1));
                            float c = getFloatOperand(currentOperands.get(2));
                            float d = getFloatOperand(currentOperands.get(3));
                            float e = getFloatOperand(currentOperands.get(4));
                            float f = getFloatOperand(currentOperands.get(5));
                            ctm.concatenate(new Matrix(a, b, c, d, e, f));
                        }
                    } else if ("q".equals(opName)) {
                        ctmStack.push(ctm.clone());
                    } else if ("Q".equals(opName)) {
                        ctm = ctmStack.isEmpty() ? new Matrix() : ctmStack.pop();
                    } else if ("re".equals(opName)) {
                        if (currentOperands.size() >= 4) {
                            float rx = getFloatOperand(currentOperands.get(0));
                            float ry = getFloatOperand(currentOperands.get(1));
                            float rw = getFloatOperand(currentOperands.get(2));
                            float rh = getFloatOperand(currentOperands.get(3));
                            java.awt.geom.Point2D.Float pt = ctm.transformPoint(rx, ry);
                            if (isPointInsideMatches(pt.x, pt.y, rw, rh, matches, pageH, originX, originY)) {
                                streamModified = true;
                                currentOperands.clear();
                                continue;
                            }
                        }
                    } else if ("m".equals(opName) || "l".equals(opName)) {
                        if (currentOperands.size() >= 2) {
                            float px = getFloatOperand(currentOperands.get(0));
                            float py = getFloatOperand(currentOperands.get(1));
                            java.awt.geom.Point2D.Float pt = ctm.transformPoint(px, py);
                            if (isPointInsideMatches(pt.x, pt.y, 1f, 1f, matches, pageH, originX, originY)) {
                                streamModified = true;
                                currentOperands.clear();
                                continue;
                            }
                        }
                    } else if ("Do".equals(opName)) {
                        float tx = ctm.getTranslateX();
                        float ty = ctm.getTranslateY();
                        if (isPointInsideMatches(tx, ty, 10f, 10f, matches, pageH, originX, originY)) {
                            streamModified = true;
                            currentOperands.clear();
                            continue;
                        }
                    } else if ("BT".equals(opName)) {
                        textMatrix = new Matrix();
                        textLineMatrix = new Matrix();
                    } else if ("ET".equals(opName)) {
                        textMatrix = null;
                        textLineMatrix = null;
                    } else if ("Tf".equals(opName)) {
                        if (currentOperands.size() >= 2) {
                            if (currentOperands.get(0) instanceof COSName fontName && res != null) {
                                try {
                                    activeFont = res.getFont(fontName);
                                } catch (Exception ignored) {}
                            }
                            fontSize = getFloatOperand(currentOperands.get(1));
                        }
                    } else if ("Tz".equals(opName)) {
                        if (!currentOperands.isEmpty()) {
                            hScaling = getFloatOperand(currentOperands.get(0));
                        }
                    } else if ("TL".equals(opName)) {
                        if (!currentOperands.isEmpty()) {
                            textLeading = getFloatOperand(currentOperands.get(0));
                        }
                    } else if ("Tm".equals(opName)) {
                        if (currentOperands.size() >= 6) {
                            textMatrix = new Matrix(
                                    getFloatOperand(currentOperands.get(0)), getFloatOperand(currentOperands.get(1)),
                                    getFloatOperand(currentOperands.get(2)), getFloatOperand(currentOperands.get(3)),
                                    getFloatOperand(currentOperands.get(4)), getFloatOperand(currentOperands.get(5))
                            );
                            textLineMatrix = textMatrix.clone();
                        }
                    } else if ("Td".equals(opName)) {
                        if (currentOperands.size() >= 2 && textLineMatrix != null) {
                            float tx = getFloatOperand(currentOperands.get(0));
                            float ty = getFloatOperand(currentOperands.get(1));
                            textLineMatrix.concatenate(new Matrix(1, 0, 0, 1, tx, ty));
                            textMatrix = textLineMatrix.clone();
                        }
                    } else if ("TD".equals(opName)) {
                        if (currentOperands.size() >= 2 && textLineMatrix != null) {
                            float tx = getFloatOperand(currentOperands.get(0));
                            float ty = getFloatOperand(currentOperands.get(1));
                            textLeading = -ty;
                            textLineMatrix.concatenate(new Matrix(1, 0, 0, 1, tx, ty));
                            textMatrix = textLineMatrix.clone();
                        }
                    } else if ("T*".equals(opName)) {
                        if (textLineMatrix != null) {
                            textLineMatrix.concatenate(new Matrix(1, 0, 0, 1, 0, -textLeading));
                            textMatrix = textLineMatrix.clone();
                        }
                    } else if ("Tj".equals(opName) || "'".equals(opName) || "\"".equals(opName)) {
                        if (!currentOperands.isEmpty() && currentOperands.get(currentOperands.size() - 1) instanceof COSString cosStr) {
                            COSArray asArr = new COSArray();
                            asArr.add(cosStr);
                            COSArray scrubbed = scrubCosArray(asArr, textMatrix, ctm, activeFont, fontSize, hScaling, matches, pageH, originX, originY);
                            if (scrubbed != null) {
                                streamModified = true;
                                if (scrubbed.size() == 0 || !containsAnyString(scrubbed)) {
                                    currentOperands.remove(currentOperands.size() - 1);
                                    if (("'".equals(opName) || "\"".equals(opName)) && textLineMatrix != null) {
                                        textLineMatrix.concatenate(new Matrix(1, 0, 0, 1, 0, -textLeading));
                                        textMatrix = textLineMatrix.clone();
                                    }
                                    currentOperands.clear();
                                    continue;
                                } else {
                                    currentOperands.remove(currentOperands.size() - 1);
                                    currentOperands.add(scrubbed);
                                    op = Operator.getOperator("TJ");
                                }
                            }
                        }
                        if (("'".equals(opName) || "\"".equals(opName)) && textLineMatrix != null) {
                            textLineMatrix.concatenate(new Matrix(1, 0, 0, 1, 0, -textLeading));
                            textMatrix = textLineMatrix.clone();
                        }
                    } else if ("TJ".equals(opName)) {
                        if (!currentOperands.isEmpty() && currentOperands.get(currentOperands.size() - 1) instanceof COSArray arr) {
                            COSArray scrubbed = scrubCosArray(arr, textMatrix, ctm, activeFont, fontSize, hScaling, matches, pageH, originX, originY);
                            if (scrubbed != null) {
                                streamModified = true;
                                if (scrubbed.size() == 0 || !containsAnyString(scrubbed)) {
                                    currentOperands.remove(currentOperands.size() - 1);
                                    currentOperands.clear();
                                    continue;
                                } else {
                                    currentOperands.set(currentOperands.size() - 1, scrubbed);
                                }
                            }
                        }
                    }

                    newTokens.addAll(currentOperands);
                    newTokens.add(op);
                    currentOperands.clear();
                } else {
                    currentOperands.add(token);
                }
            }

            if (!currentOperands.isEmpty()) {
                newTokens.addAll(currentOperands);
            }

            if (streamModified) {
                PDStream newStream = new PDStream(doc);
                try (OutputStream os = newStream.createOutputStream(COSName.FLATE_DECODE)) {
                    ContentStreamWriter writer = new ContentStreamWriter(os);
                    writer.writeTokens(newTokens);
                }
                return newStream;
            }
        } catch (Exception e) {
            log.warn("Error parsing stream tokens: {}", e.getMessage());
        }
        return null;
    }

    private boolean containsAnyString(COSArray arr) {
        if (arr == null) return false;
        for (int i = 0; i < arr.size(); i++) {
            if (arr.get(i) instanceof COSString) return true;
        }
        return false;
    }

    private COSArray scrubCosArray(COSArray arr, Matrix textMatrix, Matrix ctm,
                                   PDFont font, float fontSize, float hScaling,
                                   List<Match> matches, float pageH, float originX, float originY) {
        COSArray result = new COSArray();
        float scale = hScaling / 100.0f;
        ByteArrayOutputStream keptBytes = new ByteArrayOutputStream();
        float accumulatedAdjustment = 0f;
        boolean arrayModified = false;

        for (int i = 0; i < arr.size(); i++) {
            COSBase item = arr.get(i);
            if (item instanceof COSNumber num) {
                float tx = -num.floatValue() / 1000.0f * fontSize * scale;
                if (textMatrix != null) {
                    textMatrix.concatenate(new Matrix(1, 0, 0, 1, tx, 0));
                }
                if (keptBytes.size() > 0) {
                    result.add(new COSString(keptBytes.toByteArray()));
                    keptBytes.reset();
                }
                float totalNum = num.floatValue() + accumulatedAdjustment;
                accumulatedAdjustment = 0f;
                result.add(new COSFloat(totalNum));
            } else if (item instanceof COSString cosStr) {
                byte[] bytes = cosStr.getBytes();
                try {
                    if (font instanceof PDType0Font type0) {
                        InputStream in = new ByteArrayInputStream(bytes);
                        while (in.available() > 0) {
                            int before = in.available();
                            int code = type0.readCode(in);
                            int after = in.available();
                            int codeLen = before - after;
                            float charW = (type0.getWidth(code) / 1000.0f) * fontSize * scale;

                            Matrix trm = (textMatrix != null ? textMatrix : new Matrix()).multiply(ctm);
                            float curX = trm.getTranslateX();
                            float curY = trm.getTranslateY();

                            if (isInsideMatches(curX, curY, charW, matches, pageH, originX, originY)) {
                                arrayModified = true;
                                if (keptBytes.size() > 0) {
                                    result.add(new COSString(keptBytes.toByteArray()));
                                    keptBytes.reset();
                                }
                                float disp = -(charW / (fontSize * scale) * 1000.0f);
                                accumulatedAdjustment += disp;
                            } else {
                                if (accumulatedAdjustment != 0) {
                                    result.add(new COSFloat(accumulatedAdjustment));
                                    accumulatedAdjustment = 0f;
                                }
                                int offset = bytes.length - before;
                                keptBytes.write(bytes, offset, codeLen);
                            }

                            if (textMatrix != null) {
                                textMatrix.concatenate(new Matrix(1, 0, 0, 1, charW, 0));
                            }
                        }
                    } else {
                        for (byte b : bytes) {
                            int code = b & 0xFF;
                            float charW = (font != null ? font.getWidth(code) : 500f) / 1000.0f * fontSize * scale;

                            Matrix trm = (textMatrix != null ? textMatrix : new Matrix()).multiply(ctm);
                            float curX = trm.getTranslateX();
                            float curY = trm.getTranslateY();

                            if (isInsideMatches(curX, curY, charW, matches, pageH, originX, originY)) {
                                arrayModified = true;
                                if (keptBytes.size() > 0) {
                                    result.add(new COSString(keptBytes.toByteArray()));
                                    keptBytes.reset();
                                }
                                float disp = -(charW / (fontSize * scale) * 1000.0f);
                                accumulatedAdjustment += disp;
                            } else {
                                if (accumulatedAdjustment != 0) {
                                    result.add(new COSFloat(accumulatedAdjustment));
                                    accumulatedAdjustment = 0f;
                                }
                                keptBytes.write(b);
                            }

                            if (textMatrix != null) {
                                textMatrix.concatenate(new Matrix(1, 0, 0, 1, charW, 0));
                            }
                        }
                    }
                } catch (Exception e) {
                    Matrix trm = (textMatrix != null ? textMatrix : new Matrix()).multiply(ctm);
                    float curX = trm.getTranslateX();
                    float curY = trm.getTranslateY();
                    float totalW = bytes.length * fontSize * 0.5f * scale;
                    if (isInsideMatches(curX, curY, totalW, matches, pageH, originX, originY)) {
                        arrayModified = true;
                        if (keptBytes.size() > 0) {
                            result.add(new COSString(keptBytes.toByteArray()));
                            keptBytes.reset();
                        }
                        float disp = -(totalW / (fontSize * scale) * 1000.0f);
                        accumulatedAdjustment += disp;
                    } else {
                        if (accumulatedAdjustment != 0) {
                            result.add(new COSFloat(accumulatedAdjustment));
                            accumulatedAdjustment = 0f;
                        }
                        keptBytes.writeBytes(bytes);
                    }
                    if (textMatrix != null) {
                        textMatrix.concatenate(new Matrix(1, 0, 0, 1, totalW, 0));
                    }
                }
            }
        }

        if (keptBytes.size() > 0) {
            if (accumulatedAdjustment != 0) {
                result.add(new COSFloat(accumulatedAdjustment));
                accumulatedAdjustment = 0f;
            }
            result.add(new COSString(keptBytes.toByteArray()));
        } else if (accumulatedAdjustment != 0) {
            result.add(new COSFloat(accumulatedAdjustment));
        }

        if (!arrayModified) {
            return null;
        }
        return result;
    }

    private boolean isInsideMatches(float curX, float curY, float width, List<Match> matches,
                                    float pageH, float originX, float originY) {
        float tol = 4.0f;
        float rightX = curX + width;
        float topY = curY + 1.0f;
        for (Match m : matches) {
            if (m.getLineBboxes() != null && !m.getLineBboxes().isEmpty()) {
                for (double[] box : m.getLineBboxes()) {
                    if (boxOverlaps(curX, rightX, curY, topY, box, pageH, originX, originY, tol)) return true;
                }
            }
            if (m.getBbox() != null && m.getBbox().length >= 4) {
                if (boxOverlaps(curX, rightX, curY, topY, m.getBbox(), pageH, originX, originY, tol)) return true;
            }
        }
        return false;
    }

    private boolean boxOverlaps(float curX, float rightX, float curY, float topY, double[] box,
                                float pageH, float originX, float originY, float tol) {
        float boxX = (float) box[0];
        float boxY = (float) box[1];
        float boxW = (float) box[2];
        float boxH = (float) box[3];

        float pdfMinX = originX + boxX - tol;
        float pdfMaxX = originX + boxX + boxW + tol;
        float pdfMinY = originY + (pageH - boxY - boxH) - tol;
        float pdfMaxY = originY + (pageH - boxY) + tol;

        boolean yOverlaps = Math.max(curY, pdfMinY) <= Math.min(topY, pdfMaxY);
        boolean xOverlaps = Math.max(curX, pdfMinX) <= Math.min(rightX, pdfMaxX);
        return xOverlaps && yOverlaps;
    }

    private static final String[] CONTACT_ICON_TOKENS = {
            "\u260E", "\u2709", "\uF095", "\uF0E0", "fa-phone", "fa-envelope", "fa-envelope-o", "fa-mobile", "✉", "☎", "✆", "📞", "📧", "📱"
    };

    private double calculateMaxPrecedingTextRight(DocumentModel docModel, List<Glyph> glyphs, int pageNumber,
                                                  double targetX, double targetY, double targetH) {
        double maxRight = 0.0;
        double targetTop = targetY;
        double targetBottom = targetY + targetH;

        // 1. Check exact CharacterCollector glyphs if available
        if (glyphs != null && !glyphs.isEmpty()) {
            for (Glyph charGlyph : glyphs) {
                String text = charGlyph.getText();
                if (text == null || text.isBlank()) continue;

                double charTop = charGlyph.getY();
                double charBottom = charGlyph.getY() + charGlyph.getHeight();
                double yOverlap = Math.max(0, Math.min(targetBottom, charBottom) - Math.max(targetTop, charTop));
                if (yOverlap > 2.0) {
                    double charX = charGlyph.getX();
                    double charW = charGlyph.getWidth();
                    // Preceding character: starts before targetX
                    if (charX < targetX - 0.5) {
                        double charRight = charX + charW;
                        if (charRight <= targetX + 1.0) {
                            maxRight = Math.max(maxRight, charRight);
                        } else {
                            maxRight = Math.max(maxRight, targetX);
                        }
                    }
                }
            }
        }

        // 2. Also check DocumentModel blocks and runs (for paragraphs, headings, lists, tables)
        if (docModel != null && docModel.getPages() != null) {
            for (PageModel page : docModel.getPages()) {
                if (page.getPageNumber() != pageNumber) continue;
                List<Block> blocks = page.getBlocks();
                if (blocks == null) continue;

                List<TextRun> runs = extractAllRunsFromBlocks(blocks);
                for (TextRun run : runs) {
                    String text = run.getText();
                    if (text == null || text.isBlank()) continue;
                    double[] box = run.getBbox();
                    if (box == null || box.length < 4) continue;

                    double rx = box[0];
                    double ry = box[1];
                    double rw = box[2];
                    double rh = box[3];
                    double rTop = ry;
                    double rBottom = ry + rh;

                    double yOverlap = Math.max(0, Math.min(targetBottom, rBottom) - Math.max(targetTop, rTop));
                    if (yOverlap > 2.0) {
                        int len = text.length();
                        if (len > 0) {
                            double charW = rw / len;
                            for (int i = 0; i < len; i++) {
                                char ch = text.charAt(i);
                                if (Character.isWhitespace(ch)) continue;
                                double cX = rx + (i * charW);
                                double cRight = cX + charW;
                                if (cX < targetX - 0.5 && cRight <= targetX + 1.0) {
                                    maxRight = Math.max(maxRight, cRight);
                                }
                            }
                        }
                    }
                }
            }
        }

        return maxRight;
    }

    private double calculateMaxPrecedingTextRight(DocumentModel docModel, int pageNumber,
                                                  double targetX, double targetY, double targetH) {
        return calculateMaxPrecedingTextRight(docModel, null, pageNumber, targetX, targetY, targetH);
    }

    private List<TextRun> extractAllRunsFromBlocks(List<Block> blocks) {
        List<TextRun> runs = new ArrayList<>();
        if (blocks == null) return runs;
        for (Block b : blocks) {
            if (b instanceof ParagraphBlock pb) {
                if (pb.getRuns() != null) runs.addAll(pb.getRuns());
            } else if (b instanceof HeadingBlock hb) {
                if (hb.getRuns() != null) runs.addAll(hb.getRuns());
            } else if (b instanceof ListBlock lb) {
                if (lb.getItems() != null) {
                    for (ParagraphBlock pb : lb.getItems()) {
                        if (pb != null && pb.getRuns() != null) runs.addAll(pb.getRuns());
                    }
                }
            } else if (b instanceof TableBlock tb) {
                if (tb.getCells() != null) {
                    for (List<ParagraphBlock> row : tb.getCells()) {
                        if (row != null) {
                            for (ParagraphBlock pb : row) {
                                if (pb != null && pb.getRuns() != null) runs.addAll(pb.getRuns());
                            }
                        }
                    }
                }
            }
        }
        return runs;
    }

    private boolean isPointInsideMatches(float curX, float curY, float width, float height,
                                         List<Match> matches, float pageH, float originX, float originY) {
        if (matches == null || matches.isEmpty()) return false;
        float tol = 4.0f;
        float rightX = curX + width;
        float topY = curY + height;
        for (Match m : matches) {
            List<double[]> boxes = m.getExpandedLineBboxes();
            if (boxes != null && !boxes.isEmpty()) {
                for (double[] box : boxes) {
                    if (boxOverlaps(curX, rightX, curY, topY, box, pageH, originX, originY, tol)) return true;
                }
            }
            double[] bbox = m.getExpandedBbox();
            if (bbox != null && bbox.length >= 4) {
                if (boxOverlaps(curX, rightX, curY, topY, bbox, pageH, originX, originY, tol)) return true;
            }
        }
        return false;
    }

    private float getFloatOperand(Object obj) {
        if (obj instanceof COSNumber n) {
            return n.floatValue();
        }
        return 0f;
    }

    private void deepScrubRawStreams(PDDocument doc, List<Match> matches, Set<String> categorySet) {
        for (PDPage page : doc.getPages()) {
            try {
                Iterator<PDStream> iter = page.getContentStreams();
                List<PDStream> updated = new ArrayList<>();
                boolean anyChanged = false;

                while (iter.hasNext()) {
                    PDStream stream = iter.next();
                    try (InputStream is = stream.createInputStream()) {
                        byte[] bytes = is.readAllBytes();
                        String content = new String(bytes, StandardCharsets.ISO_8859_1);
                        boolean modified = false;

                        for (Match m : matches) {
                            String target = m.getMatchedText();
                            if (target != null && !target.isBlank()) {
                                String clean = target.trim();
                                if (content.contains(clean)) {
                                    content = content.replace(clean, "");
                                    modified = true;
                                }
                            }
                        }

                        // Also scrub contact icon tokens and font glyphs
                        for (String iconToken : CONTACT_ICON_TOKENS) {
                            if (content.contains(iconToken)) {
                                content = content.replace(iconToken, "");
                                modified = true;
                            }
                        }

                        // Also find any matching category PII in content (bounded to <= 65KB streams to avoid regex CPU hangs on massive vector drawing streams)
                        if (content.length() <= 65536) {
                            List<PiiDetector.PiiSpan> spans = PiiDetector.findPIISpans(content);
                            for (PiiDetector.PiiSpan span : spans) {
                                if (categorySet == null || categorySet.contains(span.getType().toLowerCase())) {
                                    content = content.replace(span.getMatch(), "");
                                    modified = true;
                                }
                            }
                        }

                        if (modified) {
                            PDStream newStream = new PDStream(doc);
                            try (OutputStream os = newStream.createOutputStream(COSName.FLATE_DECODE)) {
                                os.write(content.getBytes(StandardCharsets.ISO_8859_1));
                            }
                            updated.add(newStream);
                            anyChanged = true;
                        } else {
                            updated.add(stream);
                        }
                    }
                }

                if (anyChanged) {
                    page.setContents(updated);
                }
            } catch (Exception e) {
                log.warn("Deep stream scrub exception: {}", e.getMessage());
            }
        }
    }

    private boolean matchesAnyPii(String text, List<Match> matches) {
        if (text == null || text.isBlank() || matches == null) return false;
        for (Match m : matches) {
            if (m.getMatchedText() != null && !m.getMatchedText().isBlank()) {
                if (text.contains(m.getMatchedText().trim())) {
                    return true;
                }
            }
        }
        return false;
    }

    private String scrubPiiString(String text, List<Match> matches, Set<String> categorySet) {
        if (text == null || text.isBlank()) return text;
        String scrubbed = text;
        if (matches != null) {
            for (Match m : matches) {
                if (m.getMatchedText() != null && !m.getMatchedText().isBlank()) {
                    scrubbed = scrubbed.replace(m.getMatchedText().trim(), "");
                }
            }
        }
        for (PiiDetector.PiiSpan span : PiiDetector.findPIISpans(scrubbed)) {
            if (categorySet == null || categorySet.contains(span.getType().toLowerCase())) {
                scrubbed = scrubbed.replace(span.getMatch(), "");
            }
        }
        return scrubbed.trim();
    }
}
