package com.pdfeditor.export;

import com.pdfeditor.model.Style;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PdfRedactionService {

    private static final float RIGHT_PAGE_MARGIN = 36.0f; // Right margin (0.5 inch)
    private static final float LINE_HEIGHT_FACTOR = 1.20f;

    public byte[] redactAndReplace(byte[] originalPdfBytes, List<TextRedactionEdit> edits) throws IOException {
        if (originalPdfBytes == null || originalPdfBytes.length == 0) {
            throw new IllegalArgumentException("Original PDF bytes cannot be empty");
        }

        if (edits == null || edits.isEmpty()) {
            return originalPdfBytes;
        }

        try (PDDocument doc = Loader.loadPDF(originalPdfBytes)) {
            Map<Integer, List<TextRedactionEdit>> editsByPage = new HashMap<>();
            for (TextRedactionEdit edit : edits) {
                if (edit == null || edit.getBbox() == null || edit.getBbox().length < 4) {
                    continue;
                }
                editsByPage.computeIfAbsent(edit.getPageNumber(), k -> new ArrayList<>()).add(edit);
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
                    PDFont font = selectFont(fontFamily, bold, italic);
                    float lineHeight = fontSize * LINE_HEIGHT_FACTOR;

                    // Wrapping ke liye maximum width:
                    // Agar text original box mein nahi aata, toh page ke right margin tak wrap hone
                    // ki jagah dein
                    float maxAllowedWidth = Math.max(boxW, pageW - boxX - RIGHT_PAGE_MARGIN);

                    String newText = edit.getNewText();
                    List<String> wrappedLines = wrapTextToWidth(newText, font, fontSize, maxAllowedWidth);

                    List<String> origLines = (edit.getOriginalText() != null && !edit.getOriginalText().isEmpty())
                            ? wrapTextToWidth(edit.getOriginalText(), font, fontSize, maxAllowedWidth)
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
                    // content ko shift karein
                    if (extraHeightNeeded > 1.0f) {
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
                                cs.setFont(font, fontSize);
                                cs.setNonStrokingColor(textColor);
                                cs.newLineAtOffset(textX, baselineY);
                                cs.showText(lineText);
                                cs.endText();
                            }
                        }
                    }
                }
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private float safeGetStringWidth(PDFont font, String text, float fontSize) {
        if (text == null || text.isEmpty()) {
            return 0.0f;
        }
        try {
            return (font.getStringWidth(text) / 1000.0f) * fontSize;
        } catch (Exception e) {
            return text.length() * fontSize * 0.5f;
        }
    }

    /**
     * Text ko available width (maxWidth) ke hisaab se automatically words aur lines
     * mein wrap karta hai.
     */
    private List<String> wrapTextToWidth(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> resultLines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return resultLines;
        }

        float effectiveMaxWidth = Math.max(maxWidth, fontSize * 2.0f);
        String sanitized = sanitizeForWinAnsi(text);
        String[] manualParagraphs = sanitized.split("\r?\n");

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
                float candidateWidth = safeGetStringWidth(font, candidate, fontSize);

                if (candidateWidth <= effectiveMaxWidth) {
                    currentLine.setLength(0);
                    currentLine.append(candidate);
                } else {
                    if (!currentLine.isEmpty()) {
                        resultLines.add(currentLine.toString());
                        currentLine.setLength(0);
                    }

                    // Agar koi single word hi maxWidth se bada ho, toh use characters mein break karein
                    float wordWidth = safeGetStringWidth(font, word, fontSize);
                    if (wordWidth > effectiveMaxWidth) {
                        StringBuilder charChunk = new StringBuilder();
                        for (int i = 0; i < word.length(); i++) {
                            char c = word.charAt(i);
                            String nextChunk = charChunk.toString() + c;
                            float chunkW = safeGetStringWidth(font, nextChunk, fontSize);
                            if (chunkW > effectiveMaxWidth && !charChunk.isEmpty()) {
                                resultLines.add(charChunk.toString());
                                charChunk.setLength(0);
                            }
                            charChunk.append(c);
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

    private PDFont selectFont(String fontFamily, boolean bold, boolean italic) {
        String fn = fontFamily != null ? fontFamily.toLowerCase() : "";
        if (fn.contains("times") || fn.contains("serif") || fn.contains("roman")) {
            if (bold && italic) return new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD_ITALIC);
            if (bold) return new PDType1Font(Standard14Fonts.FontName.TIMES_BOLD);
            if (italic) return new PDType1Font(Standard14Fonts.FontName.TIMES_ITALIC);
            return new PDType1Font(Standard14Fonts.FontName.TIMES_ROMAN);
        } else if (fn.contains("courier") || fn.contains("mono")) {
            if (bold && italic) return new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD_OBLIQUE);
            if (bold) return new PDType1Font(Standard14Fonts.FontName.COURIER_BOLD);
            if (italic) return new PDType1Font(Standard14Fonts.FontName.COURIER_OBLIQUE);
            return new PDType1Font(Standard14Fonts.FontName.COURIER);
        } else {
            if (bold && italic) return new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE);
            if (bold) return new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            if (italic) return new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
            return new PDType1Font(Standard14Fonts.FontName.HELVETICA);
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
}