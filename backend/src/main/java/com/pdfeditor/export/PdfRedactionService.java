package com.pdfeditor.export;

import com.pdfeditor.model.Style;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.contentstream.operator.Operator;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSNumber;
import org.apache.pdfbox.pdfparser.PDFStreamParser;
import org.apache.pdfbox.pdfwriter.ContentStreamWriter;
import org.apache.pdfbox.pdmodel.common.PDStream;
import org.apache.pdfbox.util.Matrix;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class PdfRedactionService {

    private static final float RIGHT_PAGE_MARGIN = 36.0f; // Right margin (0.5 inch)
    private static final float LINE_HEIGHT_FACTOR = 1.20f;

    private final Map<String, byte[]> fontBytesMap = new ConcurrentHashMap<>();
    private final ThreadLocal<Integer> lastReplacedCharCount = ThreadLocal.withInitial(() -> 0);

    public int getLastReplacedCharCount() {
        return lastReplacedCharCount.get();
    }

    public byte[] redactAndReplace(byte[] originalPdfBytes, List<TextRedactionEdit> edits) throws IOException {
        if (originalPdfBytes == null || originalPdfBytes.length == 0) {
            throw new IllegalArgumentException("Original PDF bytes cannot be empty");
        }

        if (edits == null || edits.isEmpty()) {
            return originalPdfBytes;
        }

        try (PDDocument doc = Loader.loadPDF(originalPdfBytes)) {
            Map<String, PDType0Font> docFontCache = new HashMap<>();
            int totalReplacedCount = 0;

            Map<Integer, List<TextRedactionEdit>> editsByPage = new HashMap<>();
            for (TextRedactionEdit edit : edits) {
                if (edit == null || edit.getBbox() == null || edit.getBbox().length < 4) {
                    continue;
                }
                editsByPage.computeIfAbsent(edit.getPageNumber(), k -> new ArrayList<>()).add(edit);
            }

            int maxRequestedPage = editsByPage.keySet().stream().mapToInt(Integer::intValue).max().orElse(doc.getNumberOfPages());
            while (doc.getNumberOfPages() < maxRequestedPage) {
                PDPage lastPage = doc.getPage(doc.getNumberOfPages() - 1);
                PDRectangle templateBox = lastPage.getCropBox() != null ? lastPage.getCropBox() : lastPage.getMediaBox();
                doc.addPage(new PDPage(templateBox != null ? templateBox : PDRectangle.A4));
            }
            int numPages = doc.getNumberOfPages();
            for (Map.Entry<Integer, List<TextRedactionEdit>> entry : editsByPage.entrySet()) {
                int pageNumber = entry.getKey();
                if (pageNumber < 1 || pageNumber > numPages) {
                    continue;
                }

                PDPage page = doc.getPage(pageNumber - 1);
                PDRectangle cropBox = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
                float originX = cropBox.getLowerLeftX();
                float originY = cropBox.getLowerLeftY();
                float pageW = cropBox.getWidth();
                float pageH = cropBox.getHeight();

                List<TextRedactionEdit> pageEdits = entry.getValue();

                // Strip original text tokens (Tj, TJ, ', ") inside the redaction boxes from the content stream
                // so that hidden underlying text cannot be extracted or merged on re-opening.
                stripTextTokensInRedactionBoxes(doc, page, pageEdits, originX, originY, pageW, pageH);

                // Edits ko top-to-bottom (Y coordinate ke hisaab se) sort karein taaki shift
                // sahi kaam kare
                pageEdits.sort(Comparator.comparingDouble(e -> {
                    double[] b = toUnrotatedPdfCoords(e.getBbox(), e.getPageRotation(), pageW, pageH);
                    return b[1];
                }));

                // Cumulative vertical shift track karne ke liye
                float cumulativeDeltaY = 0.0f;

                for (TextRedactionEdit edit : pageEdits) {
                    double[] bbox = edit.getBbox();
                    double[] pdfBbox = toUnrotatedPdfCoords(bbox, edit.getPageRotation(), pageW, pageH);

                    float boxX = (float) pdfBbox[0];
                    // Agar upar ke kisi edit ne page shift kiya hai, toh is edit ka Y bhi neeche
                    // shift hoga
                    float boxY = (float) pdfBbox[1] + cumulativeDeltaY;
                    float boxW = (float) pdfBbox[2];
                    float boxH = (float) pdfBbox[3];

                    Style style = edit.getStyle();
                    boolean bold = style != null && style.bold();
                    boolean italic = style != null && style.italic();
                    float fontSize = (style != null && style.fontSize() > 0) ? (float) style.fontSize() : 11.0f;
                    Color textColor = parseColor(style != null ? style.color() : "#000000", Color.BLACK);
                    String fontFamily = style != null ? style.fontFamily() : null;
                    PDType0Font font = selectFont(doc, docFontCache, fontFamily, bold, italic);
                    PDType0Font fallbackFont = getFallbackFont(doc, docFontCache);
                    float lineHeight = fontSize * LINE_HEIGHT_FACTOR;

                    // Wrapping ke liye maximum width:
                    // Agar text original box mein nahi aata, toh page ke right margin tak wrap hone
                    // ki jagah dein
                    float maxAllowedWidth = Math.max(boxW, pageW - boxX - RIGHT_PAGE_MARGIN);

                    String newText = edit.getNewText();
                    ProcessedText processedNew = prepareText(newText, font, fallbackFont);
                    totalReplacedCount += processedNew.replacedCount;
                    List<String> wrappedLines = wrapTextToWidth(processedNew.text, font, fallbackFont, fontSize, maxAllowedWidth);

                    ProcessedText processedOrig = prepareText(edit.getOriginalText(), font, fallbackFont);
                    List<String> origLines = (processedOrig.text != null && !processedOrig.text.isEmpty())
                            ? wrapTextToWidth(processedOrig.text, font, fallbackFont, fontSize, maxAllowedWidth)
                            : List.of();
                    float effectiveLineHeight = (origLines.size() > 1 && boxH > 0)
                            ? Math.max(lineHeight, boxH / origLines.size())
                            : lineHeight;

                    float effectiveBoxH = boxH > 0 ? boxH : effectiveLineHeight;
                    float requiredHeight = wrappedLines.size() * effectiveLineHeight;
                    float extraHeightNeeded = (requiredHeight > effectiveBoxH) ? (requiredHeight - effectiveBoxH) : 0.0f;

                    double[] whiteoutBbox = edit.getOriginalBbox() != null ? edit.getOriginalBbox() : edit.getBbox();
                    double[] pdfWhiteoutBbox = toUnrotatedPdfCoords(whiteoutBbox, edit.getPageRotation(), pageW, pageH);

                    float whiteoutX = (float) pdfWhiteoutBbox[0];
                    float whiteoutY = (float) pdfWhiteoutBbox[1];
                    float whiteoutW = (float) pdfWhiteoutBbox[2];
                    float whiteoutH = (float) pdfWhiteoutBbox[3];

                    // Step 1: Pehle original text ko white/background box se मिटाएं (Redact)
                    // Fix 2: Exact text box height to strictly preserve underlines and border vectors directly below
                    if (!edit.isTransparentBackground()) {
                        if (edit.getWhiteoutBoxes() != null && !edit.getWhiteoutBoxes().isEmpty()) {
                            for (double[] wBox : edit.getWhiteoutBoxes()) {
                                double[] pdfWBox = toUnrotatedPdfCoords(wBox, edit.getPageRotation(), pageW, pageH);
                                float wx = (float) pdfWBox[0];
                                float wy = (float) pdfWBox[1];
                                float ww = (float) pdfWBox[2];
                                float wh = (float) pdfWBox[3];
                                float padX = 0.5f;
                                float rectX = originX + wx - padX;
                                float rectW = ww + (padX * 2f);
                                float rectY = originY + (pageH - wy - wh);
                                float rectH = wh;

                                try (PDPageContentStream cs = new PDPageContentStream(
                                        doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                                    Color bgColor = parseColor(edit.getBackgroundColor(), Color.WHITE);
                                    cs.setNonStrokingColor(bgColor);
                                    cs.addRect(rectX, rectY, rectW, rectH);
                                    cs.fill();
                                }
                            }
                        } else {
                            float padX = 0.5f;
                            float rectX = originX + whiteoutX - padX;
                            float rectW = whiteoutW + (padX * 2f);
                            float rectY = originY + (pageH - whiteoutY - whiteoutH);
                            float rectH = whiteoutH;

                            try (PDPageContentStream cs = new PDPageContentStream(
                                    doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                                Color bgColor = parseColor(edit.getBackgroundColor(), Color.WHITE);
                                cs.setNonStrokingColor(bgColor);
                                cs.addRect(rectX, rectY, rectW, rectH);
                                cs.fill();
                            }
                        }
                    } else if (edit.getOriginalText() != null && edit.getOriginalText().matches("^[\\s,.;:\\-_–—]+$") && !edit.getOriginalText().trim().isEmpty()) {
                        // For blank lines with trailing comma/punctuation, only whiteout the tiny ~10px area of the punctuation itself at the right end
                        // so the underlying horizontal black line is NOT erased, but the original comma is covered
                        float punctW = Math.min(12.0f, whiteoutW);
                        float punctX = originX + whiteoutX + whiteoutW - punctW;
                        float rectY = originY + (pageH - whiteoutY - whiteoutH);
                        float rectH = whiteoutH;

                        try (PDPageContentStream cs = new PDPageContentStream(
                                doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            Color bgColor = parseColor(edit.getBackgroundColor(), Color.WHITE);
                            cs.setNonStrokingColor(bgColor);
                            cs.addRect(punctX, rectY, punctW, rectH);
                            cs.fill();
                        }
                    }

                    // Step 2: Agar naya text zyada lines le raha hai, toh iske neeche ke poore page
                    // content ko shift karein (sirf un-paginated legacy edits ke liye;
                    // frontend cascading reflow mein downstream blocks already have explicit target coordinates)
                    boolean isFrontendManagedReflow = pageEdits.stream().anyMatch(e -> e.isMoved() || e.isReflowed() || e.isNewPage())
                            || edits.stream().anyMatch(e -> e.isMoved() || e.isReflowed() || e.isNewPage() || e.getPageNumber() > 1);

                    if (!isFrontendManagedReflow && extraHeightNeeded > 1.0f) {
                        float splitFromTopY = boxY + effectiveBoxH + 1.0f;
                        shiftPageContentBelow(doc, pageNumber - 1, page, splitFromTopY, extraHeightNeeded,
                                parseColor(edit.getBackgroundColor(), Color.WHITE));
                        cumulativeDeltaY += extraHeightNeeded;
                    }

                    // Step 3: Naya wrapped text line-by-line print karein
                    if (!wrappedLines.isEmpty()) {
                        try (PDPageContentStream cs = new PDPageContentStream(
                                doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                            for (int i = 0; i < wrappedLines.size(); i++) {
                                String lineText = wrappedLines.get(i);
                                if (lineText.isEmpty())
                                    continue;

                                float textX = originX + boxX;
                                float baselineY = originY + (pageH - boxY - (fontSize * 0.82f)) - (i * effectiveLineHeight);

                                cs.beginText();
                                cs.setNonStrokingColor(textColor);
                                cs.newLineAtOffset(textX, baselineY);
                                renderTextWithFallback(cs, lineText, font, fallbackFont, fontSize);
                                cs.endText();
                            }
                        }
                    }
                }
            }

            lastReplacedCharCount.set(totalReplacedCount);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private byte[] getFontBytes(String filename) throws IOException {
        byte[] cached = fontBytesMap.get(filename);
        if (cached != null) {
            return cached;
        }
        try (InputStream is = getClass().getResourceAsStream("/fonts/" + filename)) {
            if (is == null) {
                throw new IOException("Font resource not found: /fonts/" + filename);
            }
            byte[] bytes = is.readAllBytes();
            fontBytesMap.put(filename, bytes);
            return bytes;
        }
    }

    private PDType0Font getOrLoadFont(PDDocument doc, Map<String, PDType0Font> fontCache, String filename) throws IOException {
        PDType0Font cached = fontCache.get(filename);
        if (cached != null) {
            return cached;
        }
        byte[] bytes = getFontBytes(filename);
        PDType0Font font = PDType0Font.load(doc, new ByteArrayInputStream(bytes));
        fontCache.put(filename, font);
        return font;
    }

    private String resolveFontFilename(String fontFamily, boolean bold, boolean italic) {
        String fn = fontFamily != null ? fontFamily.toLowerCase() : "";
        String style = (bold && italic) ? "-BoldItalic.ttf" : bold ? "-Bold.ttf" : italic ? "-Italic.ttf" : "-Regular.ttf";
        if (fn.contains("times") || fn.contains("serif") || fn.contains("roman")) {
            return "LiberationSerif" + style;
        } else if (fn.contains("courier") || fn.contains("mono")) {
            return "LiberationMono" + style;
        } else {
            return "LiberationSans" + style;
        }
    }

    private PDType0Font selectFont(PDDocument doc, Map<String, PDType0Font> fontCache,
                                   String fontFamily, boolean bold, boolean italic) throws IOException {
        String filename = resolveFontFilename(fontFamily, bold, italic);
        return getOrLoadFont(doc, fontCache, filename);
    }

    private PDType0Font getFallbackFont(PDDocument doc, Map<String, PDType0Font> fontCache) throws IOException {
        return getOrLoadFont(doc, fontCache, "NotoSans-Regular.ttf");
    }

    private static boolean fontHasGlyph(PDType0Font font, int codePoint) {
        if (font == null) return false;
        if (font.getCmapLookup() != null) {
            return font.getCmapLookup().getGlyphId(codePoint) > 0;
        }
        try {
            font.encode(new String(Character.toChars(codePoint)));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static class ProcessedText {
        final String text;
        final int replacedCount;

        ProcessedText(String text, int replacedCount) {
            this.text = text;
            this.replacedCount = replacedCount;
        }
    }

    private ProcessedText prepareText(String text, PDType0Font primaryFont, PDType0Font fallbackFont) {
        if (text == null) {
            return new ProcessedText("", 0);
        }
        StringBuilder sb = new StringBuilder();
        int replaced = 0;
        int i = 0;
        int len = text.length();
        while (i < len) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            i += charCount;

            if (cp == '\n' || cp == '\r') {
                sb.appendCodePoint(cp);
                continue;
            }
            if (cp == '\t') {
                sb.append("    ");
                continue;
            }

            // 1. Check if primary or fallback font can render glyph
            if (fontHasGlyph(primaryFont, cp) || fontHasGlyph(fallbackFont, cp)) {
                sb.appendCodePoint(cp);
                continue;
            }

            // 2. Neither font has the glyph: apply typographic fallback if applicable
            switch (cp) {
                case '\u201C', '\u201D' -> sb.append('"');
                case '\u2018', '\u2019' -> sb.append('\'');
                case '\u2013', '\u2014' -> sb.append('-');
                case '\u2022' -> sb.append('-');
                case '\u2026' -> sb.append("...");
                default -> {
                    if (cp < 32) {
                        sb.append(' ');
                    } else {
                        // Character no font can render: replace with '?' and count it
                        sb.append('?');
                        replaced++;
                    }
                }
            }
        }
        return new ProcessedText(sb.toString(), replaced);
    }

    private float safeGetStringWidth(PDType0Font font, PDType0Font fallbackFont, String text, float fontSize) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }
        float totalWidth = 0.0f;
        int i = 0;
        int len = text.length();
        while (i < len) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            PDType0Font fontToUse = fontHasGlyph(font, cp) ? font : fallbackFont;

            int runStart = i;
            i += charCount;
            while (i < len) {
                int nextCp = text.codePointAt(i);
                PDType0Font nextFont = fontHasGlyph(font, nextCp) ? font : fallbackFont;
                if (nextFont != fontToUse) break;
                i += Character.charCount(nextCp);
            }
            String run = text.substring(runStart, i);
            try {
                totalWidth += (fontToUse.getStringWidth(run) / 1000.0f) * fontSize;
            } catch (Exception e) {
                totalWidth += run.length() * fontSize * 0.5f;
            }
        }
        return totalWidth;
    }

    /**
     * Text ko available width (maxWidth) ke hisaab se automatically words aur lines
     * mein wrap karta hai.
     */
    private List<String> wrapTextToWidth(String text, PDType0Font font, PDType0Font fallbackFont, float fontSize, float maxWidth) throws IOException {
        List<String> resultLines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return resultLines;
        }

        float effectiveMaxWidth = Math.max(maxWidth, fontSize * 2.0f);
        String[] manualParagraphs = text.split("\r?\n");

        for (String paragraph : manualParagraphs) {
            if (paragraph.isBlank()) {
                resultLines.add("");
                continue;
            }

            String[] words = paragraph.split("\\s+");
            StringBuilder currentLine = new StringBuilder();

            for (String word : words) {
                if (word.isEmpty()) {
                    continue;
                }
                String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
                float candidateWidth = safeGetStringWidth(font, fallbackFont, candidate, fontSize);

                if (candidateWidth <= effectiveMaxWidth) {
                    currentLine.setLength(0);
                    currentLine.append(candidate);
                } else {
                    if (!currentLine.isEmpty()) {
                        resultLines.add(currentLine.toString());
                        currentLine.setLength(0);
                    }

                    // Agar koi single word hi maxWidth se bada ho, toh use characters mein break karein
                    float wordWidth = safeGetStringWidth(font, fallbackFont, word, fontSize);
                    if (wordWidth > effectiveMaxWidth) {
                        StringBuilder charChunk = new StringBuilder();
                        int i = 0;
                        while (i < word.length()) {
                            int cp = word.codePointAt(i);
                            int charCount = Character.charCount(cp);
                            i += charCount;
                            String nextChunk = charChunk.toString() + new String(Character.toChars(cp));
                            float chunkW = safeGetStringWidth(font, fallbackFont, nextChunk, fontSize);
                            if (chunkW > effectiveMaxWidth && !charChunk.isEmpty()) {
                                resultLines.add(charChunk.toString());
                                charChunk.setLength(0);
                            }
                            charChunk.appendCodePoint(cp);
                        }
                        currentLine.append(charChunk);
                    } else {
                        currentLine.append(word);
                    }
                }
            }

            if (!currentLine.isEmpty()) {
                resultLines.add(currentLine.toString());
            }
        }

        return resultLines;
    }

    private void renderTextWithFallback(PDPageContentStream cs, String text,
                                        PDType0Font primaryFont, PDType0Font fallbackFont,
                                        float fontSize) throws IOException {
        if (text == null || text.isEmpty()) return;

        int i = 0;
        int len = text.length();
        while (i < len) {
            int cp = text.codePointAt(i);
            int charCount = Character.charCount(cp);
            PDType0Font fontToUse = fontHasGlyph(primaryFont, cp) ? primaryFont : fallbackFont;

            int runStart = i;
            i += charCount;
            while (i < len) {
                int nextCp = text.codePointAt(i);
                PDType0Font nextFont = fontHasGlyph(primaryFont, nextCp) ? primaryFont : fallbackFont;
                if (nextFont != fontToUse) break;
                i += Character.charCount(nextCp);
            }

            String run = text.substring(runStart, i);
            cs.setFont(fontToUse, fontSize);
            cs.showText(run);
        }
    }

    /**
     * splitFromTopY ke neeche wale poore PDF page hisse ko capture karke deltaY
     * points neeche khiskata hai.
     */
    private void shiftPageContentBelow(PDDocument doc, int pageIndex, PDPage page,
            float splitFromTopY, float deltaY, Color bgColor) throws IOException {
        PDRectangle cropBox = page.getCropBox() != null ? page.getCropBox() : page.getMediaBox();
        float originX = cropBox.getLowerLeftX();
        float originY = cropBox.getLowerLeftY();
        float pageW = cropBox.getWidth();
        float pageH = cropBox.getHeight();

        if (splitFromTopY >= pageH - 5.0f || deltaY <= 0) {
            return;
        }

        float scale = 3.0f; // 216 DPI high-res rendering taaki quality bilkul sharp rahe
        PDFRenderer renderer = new PDFRenderer(doc);
        BufferedImage pageImage = renderer.renderImage(pageIndex, scale);

        int cropStartY = Math.max(0, Math.min(pageImage.getHeight() - 1, Math.round(splitFromTopY * scale)));
        int cropHeight = pageImage.getHeight() - cropStartY;
        if (cropHeight <= 1) {
            return;
        }

        BufferedImage lowerPartImage = pageImage.getSubimage(0, cropStartY, pageImage.getWidth(), cropHeight);
        PDImageXObject pdImage = LosslessFactory.createFromImage(doc, lowerPartImage);

        float lowerSlicePdfHeight = pageH - splitFromTopY;

        try (PDPageContentStream cs = new PDPageContentStream(
                doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
            // Purane lower area ko clean karein
            cs.setNonStrokingColor(bgColor);
            cs.addRect(originX, originY, pageW, lowerSlicePdfHeight);
            cs.fill();

            // Neeche wale hisse ko deltaY points neeche draw karein
            cs.drawImage(pdImage, originX, originY - deltaY, pageW, lowerSlicePdfHeight);
        }
    }

    private double[] toUnrotatedPdfCoords(double[] bbox, int rotation, float pageWidth, float pageHeight) {
        if (bbox == null || bbox.length < 4)
            return new double[4];
        int rot = ((rotation % 360) + 360) % 360;
        if (rot == 0) {
            return bbox;
        }

        double vx = bbox[0];
        double vy = bbox[1];
        double vw = bbox[2];
        double vh = bbox[3];

        switch (rot) {
            case 90: {
                double pdfX = vy;
                double pdfY = pageWidth - vx - vw;
                return new double[] { pdfX, pdfY, vh, vw };
            }
            case 180: {
                double pdfX = pageWidth - vx - vw;
                double pdfY = pageHeight - vy - vh;
                return new double[] { pdfX, pdfY, vw, vh };
            }
            case 270: {
                double pdfX = pageHeight - vy - vh;
                double pdfY = vx;
                return new double[] { pdfX, pdfY, vh, vw };
            }
            default:
                return bbox;
        }
    }

    private Color parseColor(String hex, Color defaultColor) {
        if (hex == null || hex.isBlank()) {
            return defaultColor;
        }
        try {
            String clean = hex.trim();
            if (clean.startsWith("#")) {
                clean = clean.substring(1);
            }
            if (clean.length() == 6) {
                int r = Integer.parseInt(clean.substring(0, 2), 16);
                int g = Integer.parseInt(clean.substring(2, 4), 16);
                int b = Integer.parseInt(clean.substring(4, 6), 16);
                return new Color(r, g, b);
            } else if (clean.length() == 3) {
                int r = Integer.parseInt(clean.substring(0, 1) + clean.substring(0, 1), 16);
                int g = Integer.parseInt(clean.substring(1, 2) + clean.substring(1, 2), 16);
                int b = Integer.parseInt(clean.substring(2, 3) + clean.substring(2, 3), 16);
                return new Color(r, g, b);
            }
        } catch (Exception ignored) {
        }
        return defaultColor;
    }

    public static String sanitizeForWinAnsi(String text) {
        if (text == null)
            return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\u20B9' -> {
                    sb.append("Rs.");
                    if (i + 1 < text.length() && text.charAt(i + 1) != ' ') {
                        sb.append(' ');
                    }
                }
                case '\u201C', '\u201D' -> sb.append('"');
                case '\u2018', '\u2019' -> sb.append('\'');
                case '\u2013', '\u2014' -> sb.append('-');
                case '\u2022' -> sb.append('-');
                case '\u2026' -> sb.append("...");
                case '\t' -> sb.append("    ");
                default -> {
                    if (c >= 32 && c <= 255) {
                        sb.append(c);
                    } else if (c < 32 && c != '\r' && c != '\n') {
                        sb.append(' ');
                    } else if (c > 255) {
                        sb.append('?');
                    }
                }
            }
        }
        return sb.toString();
    }

    /**
     * Strips text-showing tokens (Tj, TJ, ', ") whose coordinates fall within any of the target
     * redaction edit bounding boxes. This prevents PDF.js and PDFBox extractors from detecting
     * underlying obsolete text operators beneath whiteout rectangles.
     */
    private void stripTextTokensInRedactionBoxes(PDDocument doc, PDPage page, List<TextRedactionEdit> edits,
                                                float originX, float originY, float pageW, float pageH) {
        if (edits == null || edits.isEmpty() || !page.hasContents()) {
            return;
        }

        try {
            PDFStreamParser parser = new PDFStreamParser(page);
            List<Object> tokens = parser.parse();
            parser.close();

            if (tokens == null || tokens.isEmpty()) {
                return;
            }

            List<Object> newTokens = new ArrayList<>(tokens.size());
            List<Object> currentOperands = new ArrayList<>();

            Deque<Matrix> ctmStack = new ArrayDeque<>();
            Matrix ctm = new Matrix();
            Matrix textMatrix = null;
            Matrix textLineMatrix = null;
            float textLeading = 0.0f;
            boolean modified = false;

            for (Object token : tokens) {
                if (token instanceof Operator) {
                    Operator op = (Operator) token;
                    String opName = op.getName();

                    boolean isTextShowing = "Tj".equals(opName) || "TJ".equals(opName)
                            || "'".equals(opName) || "\"".equals(opName);

                    if (isTextShowing) {
                        Matrix trm = (textMatrix != null ? textMatrix : new Matrix()).multiply(ctm);
                        float posX = trm.getTranslateX();
                        float posY = trm.getTranslateY();

                        if (isInsideAnyEditBox(posX, posY, edits, originX, originY, pageW, pageH)) {
                            // Stripping this text token!
                            currentOperands.clear();
                            modified = true;
                            // For ' and ", update text line matrix even if stripped because they advance line
                            if (("'".equals(opName) || "\"".equals(opName)) && textLineMatrix != null) {
                                textLineMatrix.concatenate(new Matrix(1, 0, 0, 1, 0, -textLeading));
                                textMatrix = textLineMatrix.clone();
                            }
                            continue;
                        }

                        // For ' and ", update text line matrix if kept
                        if (("'".equals(opName) || "\"".equals(opName)) && textLineMatrix != null) {
                            textLineMatrix.concatenate(new Matrix(1, 0, 0, 1, 0, -textLeading));
                            textMatrix = textLineMatrix.clone();
                        }
                    } else if ("cm".equals(opName)) {
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
                    } else if ("BT".equals(opName)) {
                        textMatrix = new Matrix();
                        textLineMatrix = new Matrix();
                    } else if ("ET".equals(opName)) {
                        textMatrix = null;
                        textLineMatrix = null;
                    } else if ("Tm".equals(opName)) {
                        if (currentOperands.size() >= 6) {
                            float a = getFloatOperand(currentOperands.get(0));
                            float b = getFloatOperand(currentOperands.get(1));
                            float c = getFloatOperand(currentOperands.get(2));
                            float d = getFloatOperand(currentOperands.get(3));
                            float e = getFloatOperand(currentOperands.get(4));
                            float f = getFloatOperand(currentOperands.get(5));
                            textMatrix = new Matrix(a, b, c, d, e, f);
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
                    } else if ("TL".equals(opName)) {
                        if (!currentOperands.isEmpty()) {
                            textLeading = getFloatOperand(currentOperands.get(0));
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
                currentOperands.clear();
            }

            if (modified) {
                PDStream newStream = new PDStream(doc);
                try (OutputStream out = newStream.createOutputStream(COSName.FLATE_DECODE)) {
                    ContentStreamWriter writer = new ContentStreamWriter(out);
                    writer.writeTokens(newTokens);
                }
                page.setContents(newStream);
            }
        } catch (Exception e) {
            System.err.println("[PdfRedactionService] Warning: Failed to strip text tokens: " + e.getMessage());
        }
    }

    private boolean isInsideAnyEditBox(float posX, float posY, List<TextRedactionEdit> edits,
                                       float originX, float originY, float pageW, float pageH) {
        float tol = 4.0f;
        for (TextRedactionEdit edit : edits) {
            if (edit == null) continue;

            // 1. Check whiteoutBoxes if present
            if (edit.getWhiteoutBoxes() != null && !edit.getWhiteoutBoxes().isEmpty()) {
                for (double[] wbox : edit.getWhiteoutBoxes()) {
                    if (checkSingleBox(posX, posY, wbox, edit.getPageRotation(), originX, originY, pageW, pageH, tol)) {
                        return true;
                    }
                }
            }

            // 2. Check originalBbox / originalBoundingBox if present
            double[] origBbox = edit.getOriginalBbox() != null ? edit.getOriginalBbox() : edit.getOriginalBoundingBox();
            if (origBbox != null && origBbox.length >= 4) {
                if (checkSingleBox(posX, posY, origBbox, edit.getPageRotation(), originX, originY, pageW, pageH, tol)) {
                    return true;
                }
            }

            // 3. Check edit.getBbox()
            if (edit.getBbox() != null && edit.getBbox().length >= 4) {
                if (checkSingleBox(posX, posY, edit.getBbox(), edit.getPageRotation(), originX, originY, pageW, pageH, tol)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean checkSingleBox(float posX, float posY, double[] box, int pageRotation,
                                   float originX, float originY, float pageW, float pageH, float tol) {
        if (box == null || box.length < 4) return false;
        double[] pdfBbox = toUnrotatedPdfCoords(box, pageRotation, pageW, pageH);
        float boxX = (float) pdfBbox[0];
        float boxY = (float) pdfBbox[1]; // distance from page top
        float boxW = (float) pdfBbox[2];
        float boxH = (float) pdfBbox[3];

        float minUserX = originX + boxX;
        float maxUserX = originX + boxX + boxW;
        float minUserY = originY + (pageH - boxY - boxH);
        float maxUserY = originY + (pageH - boxY);

        float lowX = Math.min(minUserX, maxUserX);
        float highX = Math.max(minUserX, maxUserX);
        float lowY = Math.min(minUserY, maxUserY);
        float highY = Math.max(minUserY, maxUserY);

        return (posX >= (lowX - tol)) && (posX <= (highX + tol))
                && (posY >= (lowY - tol)) && (posY <= (highY + tol));
    }

    private float getFloatOperand(Object obj) {
        if (obj instanceof COSNumber) {
            return ((COSNumber) obj).floatValue();
        }
        return 0.0f;
    }
}