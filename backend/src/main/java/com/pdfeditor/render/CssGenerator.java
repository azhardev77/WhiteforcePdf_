package com.pdfeditor.render;

import com.pdfeditor.model.Block;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.model.PageModel;
import com.pdfeditor.model.ParagraphBlock;
import com.pdfeditor.model.Style;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class CssGenerator {

    public String generateCss(DocumentModel document) {
        double width = 612.0;
        double height = 792.0;

        if (document != null && document.getPages() != null && !document.getPages().isEmpty()) {
            PageModel firstPage = document.getPages().get(0);
            if (firstPage.getWidth() > 0)
                width = firstPage.getWidth();
            if (firstPage.getHeight() > 0)
                height = firstPage.getHeight();
        }

        return generateBaseCss(width, height);
    }

    public String generatePageCss(PageModel page) {
        double width = (page != null && page.getWidth() > 0) ? page.getWidth() : 612.0;
        double height = (page != null && page.getHeight() > 0) ? page.getHeight() : 792.0;
        return generateBaseCss(width, height);
    }

    public String generateBaseCss(double widthPt, double heightPt) {
        String widthStr = String.format(Locale.US, "%.2fpt", widthPt);
        String heightStr = String.format(Locale.US, "%.2fpt", heightPt);

        return "/* Reset & Global Print Styles */\n" +
                "* { box-sizing: border-box; }\n" +
                "html, body {\n" +
                "    margin: 0;\n" +
                "    padding: 0;\n" +
                "    width: 100%;\n" +
                "    background-color: #f5f5f5;\n" +
                "    font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif;\n"
                +
                "    -webkit-print-color-adjust: exact;\n" +
                "    print-color-adjust: exact;\n" +
                "}\n" +
                "@page {\n" +
                "    size: " + widthStr + " " + heightStr + ";\n" +
                "    margin: 0;\n" +
                "}\n" +
                "@media print {\n" +
                "    html, body {\n" +
                "        background-color: transparent;\n" +
                "    }\n" +
                "    .pdf-page {\n" +
                "        box-shadow: none !important;\n" +
                "        margin: 0 !important;\n" +
                "    }\n" +
                "    .pdf-page:last-child {\n" +
                "        page-break-after: avoid !important;\n" +
                "        break-after: avoid !important;\n" +
                "    }\n" +
                "}\n" +
                ".pdf-page {\n" +
                "    position: relative;\n" +
                "    display: flow-root;\n" +
                "    width: " + widthStr + ";\n" +
                "    min-height: " + heightStr + ";\n" +
                "    height: auto;\n" +
                "    margin: 20px auto;\n" +
                "    background: #ffffff;\n" +
                "    page-break-after: always;\n" +
                "    break-after: page;\n" +
                "    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);\n" +
                "}\n" +
                ".block {\n" +
                "    position: relative;\n" +
                "    margin: 0;\n" +
                "    padding: 0;\n" +
                "    box-sizing: border-box;\n" +
                "    overflow-wrap: break-word;\n" +
                "    word-wrap: break-word;\n" +
                "    word-break: break-word;\n" +
                "}\n" +
                ".paragraph-block, .heading-block {\n" +
                "    margin: 0;\n" +
                "    padding: 0;\n" +
                "}\n" +
                ".text-run {\n" +
                "    display: inline;\n" +
                "    white-space: pre-wrap;\n" +
                "    overflow-wrap: break-word;\n" +
                "}\n";
    }

    /**
     * Backward-compatible method producing absolute positioning (left, top, width, min-height).
     */
    public String generateBlockStyle(Block block) {
        if (block == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        double[] b = block.getBbox();

        if (b != null && b.length == 4 && (b[2] > 0 || b[3] > 0)) {
            sb.append(String.format(Locale.US, "left: %.2fpt; top: %.2fpt; ", b[0], b[1]));
            if (b[2] > 0) sb.append(String.format(Locale.US, "width: %.2fpt; ", b[2]));
            if (b[3] > 0) sb.append(String.format(Locale.US, "min-height: %.2fpt; ", b[3]));
        }

        if (block instanceof ParagraphBlock pb) {
            if (pb.getTextAlign() != null && !pb.getTextAlign().isBlank()) {
                sb.append("text-align: ").append(pb.getTextAlign().toLowerCase()).append("; ");
            }
        }

        return sb.toString().trim();
    }

    /**
     * Normal flow style generator: uses margin-top (relative to previous block's
     * bottom)
     * and margin-left so that expanding text automatically pushes lower blocks
     * down.
     */
    public String generateFlowBlockStyle(Block block, double prevBottomY, double pageWidth) {
        if (block == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        double[] b = block.getBbox();

        if (b != null && b.length == 4 && (b[2] > 0 || b[3] > 0)) {
            double left = Math.max(0.0, b[0]);
            double top = b[1];
            double marginTop = Math.max(2.0, top - prevBottomY);

            // Right margin (36pt) tak max-width dein taaki lamba text katne ke bajaye agli
            // line mein wrap ho
            double availableWidth = Math.max(b[2], pageWidth - left - 36.0);

            sb.append(String.format(Locale.US, "margin-left: %.2fpt; margin-top: %.2fpt; ", left, marginTop));
            if (availableWidth > 0) {
                sb.append(String.format(Locale.US, "max-width: %.2fpt; ", availableWidth));
            }
            if (b[3] > 0) {
                sb.append(String.format(Locale.US, "min-height: %.2fpt; ", b[3]));
            }
        }

        if (block instanceof ParagraphBlock pb) {
            if (pb.getTextAlign() != null && !pb.getTextAlign().isBlank()) {
                sb.append("text-align: ").append(pb.getTextAlign().toLowerCase()).append("; ");
            }
        }

        return sb.toString().trim();
    }

    public String generateRunStyle(Style style) {
        if (style == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();

        if (style.fontFamily() != null && !style.fontFamily().isBlank()) {
            sb.append("font-family: ").append(escapeCssFont(style.fontFamily())).append("; ");
        }

        if (style.fontSize() > 0) {
            sb.append(String.format(Locale.US, "font-size: %.2fpt; ", style.fontSize()));
        }

        if (style.bold()) {
            sb.append("font-weight: bold; ");
        } else {
            sb.append("font-weight: normal; ");
        }

        if (style.italic()) {
            sb.append("font-style: italic; ");
        } else {
            sb.append("font-style: normal; ");
        }

        if (style.color() != null && !style.color().isBlank()) {
            sb.append("color: ").append(style.color()).append("; ");
        }

        if (style.lineHeight() > 0) {
            sb.append(String.format(Locale.US, "line-height: %.2f; ", style.lineHeight()));
        }

        return sb.toString().trim();
    }

    private String escapeCssFont(String family) {
        if (family == null)
            return "sans-serif";
        return "\"" + family.replace("\"", "\\\"") + "\", sans-serif";
    }
}