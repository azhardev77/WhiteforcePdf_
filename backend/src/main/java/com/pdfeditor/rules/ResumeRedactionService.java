package com.pdfeditor.rules;

import com.pdfeditor.export.PdfRedactionService;
import com.pdfeditor.export.TextRedactionEdit;
import com.pdfeditor.extraction.ExtractionPipeline;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.session.SessionValidationException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

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
        if (originalPdfBytes == null || originalPdfBytes.length == 0) {
            throw new IllegalArgumentException("PDF bytes cannot be empty");
        }

        // Step 1: Validate extractable text
        validateExtractableText(originalPdfBytes);

        // Step 2: Extract DocumentModel
        DocumentModel docModel = extractionPipeline.extract(originalPdfBytes, "resume.pdf");

        // Step 3: Run ResumeRules
        List<Match> matches = resumeRules.find(docModel);
        if (matches == null || matches.isEmpty()) {
            return originalPdfBytes;
        }

        // Step 4: Convert matches to TextRedactionEdit
        List<TextRedactionEdit> edits = new ArrayList<>();
        for (Match match : matches) {
            TextRedactionEdit edit = new TextRedactionEdit(
                    match.getPageNumber(),
                    match.getBbox(),
                    match.getMatchedText(),
                    "",
                    null,
                    "#FFFFFF"
            );
            edit.setWhiteoutBoxes(match.getLineBboxes());
            edits.add(edit);
        }

        // Step 5: Visual whiteout and token stripping via PdfRedactionService
        byte[] redactedBytes = pdfRedactionService.redactAndReplace(originalPdfBytes, edits);

        // Step 6: Post-process document for complete link, metadata, and content stream eradication
        try (PDDocument doc = Loader.loadPDF(redactedBytes)) {
            // Remove mailto:/tel: and matching link annotations
            scrubAnnotations(doc, matches);

            // Scrub Document Information and XMP metadata
            scrubMetadata(doc, matches);

            // Scrub content stream tokens (Tj, TJ, Form XObjects)
            scrubAllContentStreams(doc, matches);

            // Deep scan raw content stream bytes for any remaining strings
            deepScrubRawStreams(doc, matches);

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

    private void scrubAnnotations(PDDocument doc, List<Match> matches) {
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
                                if (lower.startsWith("mailto:") || lower.startsWith("tel:")
                                        || PiiDetector.hasPII(uri)
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
                                    if (lower.startsWith("mailto:") || lower.startsWith("tel:")
                                            || PiiDetector.hasPII(uri)
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

    private void scrubMetadata(PDDocument doc, List<Match> matches) {
        PDDocumentInformation info = doc.getDocumentInformation();
        if (info != null) {
            info.setTitle(scrubPiiString(info.getTitle(), matches));
            info.setAuthor(scrubPiiString(info.getAuthor(), matches));
            info.setSubject(scrubPiiString(info.getSubject(), matches));
            info.setKeywords(scrubPiiString(info.getKeywords(), matches));
            info.setCreator(scrubPiiString(info.getCreator(), matches));
            info.setProducer(scrubPiiString(info.getProducer(), matches));

            COSDictionary infoDict = info.getCOSObject();
            for (COSName key : infoDict.keySet()) {
                COSBase val = infoDict.getItem(key);
                if (val instanceof COSString cosStr) {
                    String s = cosStr.getString();
                    if (PiiDetector.hasPII(s) || matchesAnyPii(s, matches)) {
                        String scrubbed = scrubPiiString(s, matches);
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
                        xml = xml.replace(span.getMatch(), "");
                        changed = true;
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
        for (PDPage page : doc.getPages()) {
            scrubPageStreams(doc, page, matches);
            scrubFormXObjects(doc, page.getResources(), matches);
        }
    }

    private void scrubPageStreams(PDDocument doc, PDPage page, List<Match> matches) {
        try {
            Iterator<PDStream> iter = page.getContentStreams();
            List<PDStream> newStreams = new ArrayList<>();
            boolean modifiedAny = false;

            while (iter.hasNext()) {
                PDStream stream = iter.next();
                PDStream resultStream = scrubSingleStream(doc, stream, matches);
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

    private void scrubFormXObjects(PDDocument doc, PDResources resources, List<Match> matches) {
        if (resources == null) return;
        for (COSName name : resources.getXObjectNames()) {
            try {
                PDXObject xobject = resources.getXObject(name);
                if (xobject instanceof PDFormXObject formXObject) {
                    PDStream stream = formXObject.getContentStream();
                    if (stream != null) {
                        PDStream resultStream = scrubSingleStream(doc, stream, matches);
                        if (resultStream != null) {
                            formXObject.getCOSObject().setItem(COSName.CONTENTS, resultStream);
                        }
                    }
                    PDResources nested = formXObject.getResources();
                    if (nested != null && nested != resources) {
                        scrubFormXObjects(doc, nested, matches);
                    }
                }
            } catch (Exception ignored) {
            }
        }
    }

    private PDStream scrubSingleStream(PDDocument doc, PDStream stream, List<Match> matches) {
        try {
            PDFStreamParser parser = new PDFStreamParser(stream.toByteArray());
            List<Object> tokens = parser.parse();
            parser.close();
            List<Object> newTokens = new ArrayList<>();
            List<Object> currentOperands = new ArrayList<>();
            boolean modified = false;

            for (Object token : tokens) {
                if (token instanceof Operator op) {
                    String opName = op.getName();

                    if ("Tj".equals(opName) || "'".equals(opName) || "\"".equals(opName)) {
                        if (!currentOperands.isEmpty() && currentOperands.get(currentOperands.size() - 1) instanceof COSString cosStr) {
                            String original = cosStr.getString();
                            if (PiiDetector.hasPII(original) || matchesAnyPii(original, matches)) {
                                String scrubbed = PiiDetector.redactPIIFromText(original);
                                for (Match m : matches) {
                                    scrubbed = scrubbed.replace(m.getMatchedText(), "").trim();
                                }
                                if (scrubbed.isEmpty()) {
                                    currentOperands.remove(currentOperands.size() - 1);
                                    currentOperands.clear();
                                    modified = true;
                                    continue;
                                } else {
                                    cosStr.setValue(scrubbed.getBytes(StandardCharsets.ISO_8859_1));
                                    modified = true;
                                }
                            }
                        }
                    } else if ("TJ".equals(opName)) {
                        if (!currentOperands.isEmpty() && currentOperands.get(currentOperands.size() - 1) instanceof COSArray arr) {
                            COSArray newArr = new COSArray();
                            boolean arrModified = false;
                            for (int i = 0; i < arr.size(); i++) {
                                COSBase item = arr.get(i);
                                if (item instanceof COSString cosStr) {
                                    String str = cosStr.getString();
                                    if (PiiDetector.hasPII(str) || matchesAnyPii(str, matches)) {
                                        String scrubbed = PiiDetector.redactPIIFromText(str);
                                        for (Match m : matches) {
                                            scrubbed = scrubbed.replace(m.getMatchedText(), "").trim();
                                        }
                                        if (!scrubbed.isEmpty()) {
                                            newArr.add(new COSString(scrubbed));
                                        }
                                        arrModified = true;
                                    } else {
                                        newArr.add(item);
                                    }
                                } else {
                                    newArr.add(item);
                                }
                            }

                            if (arrModified) {
                                modified = true;
                                if (newArr.size() == 0) {
                                    currentOperands.remove(currentOperands.size() - 1);
                                    currentOperands.clear();
                                    continue;
                                } else {
                                    currentOperands.set(currentOperands.size() - 1, newArr);
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

            if (modified) {
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

    private void deepScrubRawStreams(PDDocument doc, List<Match> matches) {
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

                        // Also find any emails or phones in content
                        List<PiiDetector.PiiSpan> spans = PiiDetector.findPIISpans(content);
                        for (PiiDetector.PiiSpan span : spans) {
                            content = content.replace(span.getMatch(), "");
                            modified = true;
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

    private String scrubPiiString(String text, List<Match> matches) {
        if (text == null || text.isBlank()) return text;
        String scrubbed = PiiDetector.redactPIIFromText(text);
        if (matches != null) {
            for (Match m : matches) {
                if (m.getMatchedText() != null && !m.getMatchedText().isBlank()) {
                    scrubbed = scrubbed.replace(m.getMatchedText().trim(), "");
                }
            }
        }
        return scrubbed.trim();
    }
}
