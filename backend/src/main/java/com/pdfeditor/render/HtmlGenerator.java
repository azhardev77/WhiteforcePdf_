package com.pdfeditor.render;

import com.pdfeditor.model.Block;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.model.HeadingBlock;
import com.pdfeditor.model.ImageBlock;
import com.pdfeditor.model.ListBlock;
import com.pdfeditor.model.PageModel;
import com.pdfeditor.model.ParagraphBlock;
import com.pdfeditor.model.TableBlock;
import com.pdfeditor.model.TextRun;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@Component
public class HtmlGenerator {

    private final CssGenerator cssGenerator;

    public HtmlGenerator() {
        this(new CssGenerator());
    }

    public HtmlGenerator(CssGenerator cssGenerator) {
        this.cssGenerator = cssGenerator != null ? cssGenerator : new CssGenerator();
    }

    public String generateHtml(DocumentModel document) {
        if (document == null) {
            return HtmlTemplate.wrap("<div class=\"pdf-page\"></div>", cssGenerator.generateBaseCss(612, 792),
                    "Empty Document");
        }

        StringBuilder pagesHtml = new StringBuilder();
        List<PageModel> pages = document.getPages();

        if (pages == null || pages.isEmpty()) {
            pagesHtml.append("<div class=\"pdf-page\"></div>\n");
        } else {
            for (PageModel page : pages) {
                pagesHtml.append(generatePageHtml(page)).append("\n");
            }
        }

        String css = cssGenerator.generateCss(document);
        return HtmlTemplate.wrap(pagesHtml.toString(), css, document.getName());
    }

    public String generatePageHtml(PageModel page) {
        if (page == null) {
            return "<div class=\"pdf-page\"></div>";
        }

        double width = page.getWidth() > 0 ? page.getWidth() : 612.0;
        double height = page.getHeight() > 0 ? page.getHeight() : 792.0;
        String pageStyle = String.format(Locale.US, "width: %.2fpt; min-height: %.2fpt;", width, height);

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("<div class=\"pdf-page\" id=\"page-%d\" style=\"%s\">\n", page.getPageNumber(),
                pageStyle));

        if (page.getBlocks() != null && !page.getBlocks().isEmpty()) {
            List<Block> sortedBlocks = new ArrayList<>(page.getBlocks());
            sortedBlocks.sort(Comparator
                    .comparingDouble(b -> (b.getBbox() != null && b.getBbox().length >= 2) ? b.getBbox()[1] : 0.0));

            double prevBottomY = 0.0;
            for (Block block : sortedBlocks) {
                String blockStyle = cssGenerator.generateFlowBlockStyle(block, prevBottomY, width);
                sb.append("    ").append(renderBlockWithStyle(block, blockStyle)).append("\n");

                double[] bbox = block.getBbox();
                if (bbox != null && bbox.length >= 4) {
                    prevBottomY = Math.max(prevBottomY, bbox[1] + bbox[3]);
                }
            }
        }

        sb.append("</div>");
        return sb.toString();
    }

    public String renderBlock(Block block) {
        if (block == null) {
            return "";
        }
        String blockStyle = cssGenerator.generateBlockStyle(block);
        return renderBlockWithStyle(block, blockStyle);
    }

    private String renderBlockWithStyle(Block block, String blockStyle) {
        if (block == null) {
            return "";
        }

        String styleAttr = (blockStyle == null || blockStyle.isEmpty()) ? "" : " style=\"" + blockStyle + "\"";
        String idAttr = (block.getId() != null) ? " id=\"" + escapeHtml(block.getId()) + "\"" : "";

        if (block instanceof HeadingBlock hb) {
            int level = Math.max(1, Math.min(6, hb.getLevel()));
            String tag = "h" + level;
            return "<" + tag + idAttr + " class=\"block heading-block\"" + styleAttr + ">" +
                    renderRuns(hb.getRuns()) +
                    "</" + tag + ">";
        }

        if (block instanceof ParagraphBlock pb) {
            return "<p" + idAttr + " class=\"block paragraph-block\"" + styleAttr + ">" +
                    renderRuns(pb.getRuns()) +
                    "</p>";
        }

        if (block instanceof ImageBlock ib) {
            return "<div" + idAttr + " class=\"block image-block\"" + styleAttr + ">" +
                    "<img src=\"" + escapeHtml(ib.getSrc())
                    + "\" alt=\"PDF Image\" style=\"max-width: 100%; height: auto; object-fit: contain;\" />" +
                    "</div>";
        }

        if (block instanceof TableBlock tb) {
            StringBuilder tableHtml = new StringBuilder();
            tableHtml.append("<div").append(idAttr).append(" class=\"block table-block\"").append(styleAttr)
                    .append(">");
            tableHtml.append("<table style=\"width: 100%; border-collapse: collapse;\">");
            if (tb.getCells() != null) {
                for (List<ParagraphBlock> row : tb.getCells()) {
                    tableHtml.append("<tr>");
                    for (ParagraphBlock cell : row) {
                        tableHtml.append("<td style=\"border: 1px solid #ccc; padding: 4px;\">");
                        tableHtml.append(renderBlockWithStyle(cell, ""));
                        tableHtml.append("</td>");
                    }
                    tableHtml.append("</tr>");
                }
            }
            tableHtml.append("</table></div>");
            return tableHtml.toString();
        }

        if (block instanceof ListBlock lb) {
            String tag = lb.isOrdered() ? "ol" : "ul";
            StringBuilder listHtml = new StringBuilder();
            listHtml.append("<").append(tag).append(idAttr).append(" class=\"block list-block\"").append(styleAttr)
                    .append(">");
            if (lb.getItems() != null) {
                for (ParagraphBlock item : lb.getItems()) {
                    listHtml.append("<li>").append(renderBlockWithStyle(item, "")).append("</li>");
                }
            }
            listHtml.append("</").append(tag).append(">");
            return listHtml.toString();
        }

        return "<div" + idAttr + " class=\"block\"" + styleAttr + "></div>";
    }

    public String renderRuns(List<TextRun> runs) {
        if (runs == null || runs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (TextRun run : runs) {
            sb.append(renderRun(run));
        }
        return sb.toString();
    }

    public String renderRun(TextRun run) {
        if (run == null || run.getText() == null || run.getText().isEmpty()) {
            return "";
        }
        String runStyle = cssGenerator.generateRunStyle(run.getStyle());
        String styleAttr = runStyle.isEmpty() ? "" : " style=\"" + runStyle + "\"";
        return "<span class=\"text-run\"" + styleAttr + ">" + escapeHtml(run.getText()) + "</span>";
    }

    public static String escapeHtml(String text) {
        if (text == null)
            return "";
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public CssGenerator getCssGenerator() {
        return cssGenerator;
    }
}