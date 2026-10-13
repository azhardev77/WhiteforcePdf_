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
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class PdfRedactionService {

    public byte[] redactAndReplace(byte[] originalPdfBytes, List<TextRedactionEdit> edits) throws IOException {
        if (originalPdfBytes == null || originalPdfBytes.length == 0) {
            throw new IllegalArgumentException("Original PDF bytes cannot be empty");
        }

        if (edits == null || edits.isEmpty()) {
            return originalPdfBytes; // No edits to apply
        }

        try (PDDocument doc = Loader.loadPDF(originalPdfBytes)) {
            // Group edits by pageNumber
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
                PDRectangle mediaBox = page.getMediaBox() != null ? page.getMediaBox() : cropBox;
                float originX = cropBox.getLowerLeftX();
                float originY = cropBox.getLowerLeftY();
                float pageW = cropBox.getWidth();
                float pageH = cropBox.getHeight();

                List<TextRedactionEdit> pageEdits = entry.getValue();

                // 1. Cover edited areas with background color (Redaction pass)
                for (TextRedactionEdit edit : pageEdits) {
                    if (edit.isTransparentBackground()) {
                        // Skip whiteout redaction when transparentBackground is true (e.g. overprinting on dots/underlines)
                        continue;
                    }

                    double[] bbox = edit.getBbox();
                    // Inverse-transform viewport bbox → PDF coordinate space
                    double[] pdfBbox = toUnrotatedPdfCoords(bbox, edit.getPageRotation(), pageW, pageH);

                    float pad = 0.5f;
                    float rectX = originX + (float) pdfBbox[0] - pad;
                    float rectW = (float) pdfBbox[2] + (pad * 2f);
                    float rectH = (float) pdfBbox[3] + (pad * 2f);
                    float rectY = originY + (pageH - (float) pdfBbox[1] - (float) pdfBbox[3]) - pad;

                    try (PDPageContentStream cs = new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        Color bgColor = parseColor(edit.getBackgroundColor(), Color.WHITE);
                        cs.setNonStrokingColor(bgColor);
                        cs.addRect(rectX, rectY, rectW, rectH);
                        cs.fill();
                    }
                }

                // 2. Draw replacement text at exact location (Text replacement pass)
                for (TextRedactionEdit edit : pageEdits) {
                    String newText = edit.getNewText();
                    if (newText == null || newText.isEmpty()) {
                        continue;
                    }

                    double[] bbox = edit.getBbox();
                    // Inverse-transform viewport bbox → PDF coordinate space
                    double[] pdfBbox = toUnrotatedPdfCoords(bbox, edit.getPageRotation(), pageW, pageH);

                    Style style = edit.getStyle();
                    boolean bold = style != null && style.bold();
                    boolean italic = style != null && style.italic();
                    float fontSize = (style != null && style.fontSize() > 0) ? (float) style.fontSize() : 11.0f;
                    Color textColor = parseColor(style != null ? style.color() : "#000000", Color.BLACK);

                    PDFont font = selectFont(bold, italic);

                    String[] lines = newText.split("\r?\n");
                    float lineHeight = fontSize * 1.15f;

                    try (PDPageContentStream cs = new PDPageContentStream(
                            doc, page, PDPageContentStream.AppendMode.APPEND, true, true)) {
                        for (int i = 0; i < lines.length; i++) {
                            String lineText = sanitizeForWinAnsi(lines[i]);
                            if (lineText.isEmpty()) continue;

                            float textX = originX + (float) pdfBbox[0];
                            float baselineY = originY + (pageH - (float) pdfBbox[1] - (fontSize * 0.82f)) - (i * lineHeight);

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

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * Inverse-transforms a bounding box from rotated-viewport space back to un-rotated PDF space.
     *
     * <p>PDF.js {@code page.getViewport({ rotation })} applies a rotation transform so that
     * the visible content is always "right-side up" in the canvas. The bounding boxes stored
     * in the DocumentModel (and sent from the frontend after editing) are in PDF-extraction
     * space (top-left origin, un-rotated). However when the user actively rotates a page via
     * the UI, the bbox sent with the edit has already been mapped through
     * {@code viewport.convertToViewportPoint()}, which means it lives in viewport-space.
     * This helper reverses that mapping for the four cardinal rotations so that the backend
     * can paint the replacement text/rect at the correct PDF-space location.
     *
     * @param bbox         [x, y, w, h] in viewport/screen space (top-left origin)
     * @param rotation     effective page rotation in degrees (0, 90, 180, 270)
     * @param pageWidth    un-rotated PDF page width in points
     * @param pageHeight   un-rotated PDF page height in points
     * @return             [x, y, w, h] in un-rotated PDF space (top-left origin)
     */
    private double[] toUnrotatedPdfCoords(double[] bbox, int rotation, float pageWidth, float pageHeight) {
        if (bbox == null || bbox.length < 4) return new double[4];
        int rot = ((rotation % 360) + 360) % 360;
        if (rot == 0) {
            // No rotation — bbox is already in un-rotated PDF top-left origin space
            return bbox;
        }

        double vx = bbox[0]; // viewport x (left)
        double vy = bbox[1]; // viewport y (top)
        double vw = bbox[2]; // viewport width
        double vh = bbox[3]; // viewport height

        switch (rot) {
            case 90: {
                // Viewport is (pageH x pageW). In the rotated viewport:
                //   pdf_x = vy
                //   pdf_y = pageW - vx - vw
                //   pdf_w = vh, pdf_h = vw
                double pdfX = vy;
                double pdfY = pageWidth - vx - vw;
                double pdfW = vh;
                double pdfH = vw;
                return new double[]{ pdfX, pdfY, pdfW, pdfH };
            }
            case 180: {
                // Viewport is same size as page.
                //   pdf_x = pageW - vx - vw
                //   pdf_y = pageH - vy - vh
                double pdfX = pageWidth  - vx - vw;
                double pdfY = pageHeight - vy - vh;
                return new double[]{ pdfX, pdfY, vw, vh };
            }
            case 270: {
                // Viewport is (pageH x pageW). In the rotated viewport:
                //   pdf_x = pageH - vy - vh
                //   pdf_y = vx
                //   pdf_w = vh, pdf_h = vw
                double pdfX = pageHeight - vy - vh;
                double pdfY = vx;
                double pdfW = vh;
                double pdfH = vw;
                return new double[]{ pdfX, pdfY, pdfW, pdfH };
            }
            default:
                return bbox;
        }
    }

    private PDFont selectFont(boolean bold, boolean italic) {
        if (bold && italic) {
            return new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD_OBLIQUE);
        } else if (bold) {
            return new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        } else if (italic) {
            return new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
        } else {
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
        if (text == null) return "";
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
                case '\u201C', '\u201D' -> sb.append('"'); // Curved double quotes
                case '\u2018', '\u2019' -> sb.append('\''); // Curved single quotes
                case '\u2013', '\u2014' -> sb.append('-'); // En/Em dashes
                case '\u2022' -> sb.append('-'); // Bullet point
                case '\u2026' -> sb.append("..."); // Ellipsis
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
