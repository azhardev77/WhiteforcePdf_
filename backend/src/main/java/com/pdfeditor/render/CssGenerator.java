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
            if (firstPage.getWidth() > 0) width = firstPage.getWidth();
            if (firstPage.getHeight() > 0) height = firstPage.getHeight();
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
               "    font-family: -apple-system, BlinkMacSystemFont, \"Segoe UI\", Roboto, Helvetica, Arial, sans-serif;\n" +
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
               "}\n" +
               ".pdf-page {\n" +
               "    position: relative;\n" +
               "    width: " + widthStr + ";\n" +
               "    height: " + heightStr + ";\n" +
               "    margin: 20px auto;\n" +
               "    background: #ffffff;\n" +
               "    overflow: hidden;\n" +
               "    page-break-after: always;\n" +
               "    break-after: page;\n" +
               "    box-shadow: 0 4px 12px rgba(0, 0, 0, 0.08);\n" +
               "}\n" +
               ".block {\n" +
               "    position: absolute;\n" +
               "    margin: 0;\n" +
               "    padding: 0;\n" +
               "    box-sizing: border-box;\n" +
               "}\n" +
               ".paragraph-block {\n" +
               "    margin: 0;\n" +
               "    padding: 0;\n" +
               "}\n" +
               ".heading-block {\n" +
               "    margin: 0;\n" +
               "    padding: 0;\n" +
               "}\n" +
               ".text-run {\n" +
               "    display: inline;\n" +
               "    white-space: pre-wrap;\n" +
               "}\n";
    }

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
        if (family == null) return "sans-serif";
        return "\"" + family.replace("\"", "\\\"") + "\", sans-serif";
    }
}
