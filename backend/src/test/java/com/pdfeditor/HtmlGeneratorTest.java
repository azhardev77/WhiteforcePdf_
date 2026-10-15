package com.pdfeditor;

import com.pdfeditor.model.Block;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.model.HeadingBlock;
import com.pdfeditor.model.PageModel;
import com.pdfeditor.model.ParagraphBlock;
import com.pdfeditor.model.Style;
import com.pdfeditor.model.TextRun;
import com.pdfeditor.render.CssGenerator;
import com.pdfeditor.render.HtmlGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class HtmlGeneratorTest {

    private HtmlGenerator htmlGenerator;
    private CssGenerator cssGenerator;

    @BeforeEach
    void setUp() {
        cssGenerator = new CssGenerator();
        htmlGenerator = new HtmlGenerator(cssGenerator);
    }

    @Test
    @DisplayName("Generate complete HTML matching DocumentModel and print specifications")
    void testGenerateCompleteHtml() {
        Style headingStyle = Style.of("Helvetica-Bold", 24.0, true, false, "#112233");
        HeadingBlock heading = new HeadingBlock(
            new double[]{50.0, 40.0, 300.0, 30.0},
            1,
            List.of(new TextRun("Document Heading", headingStyle, new double[]{50.0, 40.0, 300.0, 30.0}))
        );

        Style regularStyle = Style.of("Helvetica", 12.0, false, false, "#000000");
        Style boldStyle = Style.of("Helvetica-Bold", 12.0, true, false, "#000000");
        ParagraphBlock paragraph = new ParagraphBlock(
            new double[]{50.0, 80.0, 400.0, 20.0},
            List.of(
                new TextRun("Deptt.:- ", regularStyle, new double[]{50.0, 80.0, 60.0, 14.0}),
                new TextRun("Contract", boldStyle, new double[]{110.0, 80.0, 60.0, 14.0})
            ),
            "left"
        );

        PageModel page = new PageModel(1, 612.0, 792.0, List.of(heading, paragraph));
        DocumentModel document = new DocumentModel("doc-1", "contract.pdf", List.of(page));

        String html = htmlGenerator.generateHtml(document);

        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("<title>contract.pdf</title>");
        assertThat(html).contains("@page {\n    size: 612.00pt 792.00pt;");
        assertThat(html).contains("<div class=\"pdf-page\" id=\"page-1\"");

        // Validate heading rendering
        assertThat(html).contains("<h1");
        assertThat(html).contains("class=\"block heading-block\"");
        assertThat(html).contains("Document Heading");
        assertThat(html).contains("font-size: 24.00pt;");
        assertThat(html).contains("font-weight: bold;");
        assertThat(html).contains("color: #112233;");

        // Validate paragraph rendering
        assertThat(html).contains("<p");
        assertThat(html).contains("class=\"block paragraph-block\"");
        assertThat(html).contains("text-align: left;");
        assertThat(html).contains("Deptt.:- ");
        assertThat(html).contains("Contract");
        assertThat(html).contains("font-size: 12.00pt;");
    }

    @Test
    @DisplayName("Verify pixel-accurate bounding box CSS positioning")
    void testBoundingBoxPositioning() {
        ParagraphBlock block = new ParagraphBlock(
            new double[]{72.0, 144.0, 200.0, 50.0},
            List.of(new TextRun("Positioned Text", Style.defaultStyle(), new double[]{72.0, 144.0, 200.0, 50.0})),
            "center"
        );

        String blockHtml = htmlGenerator.renderBlock(block);
        assertThat(blockHtml).contains("left: 72.00pt;");
        assertThat(blockHtml).contains("top: 144.00pt;");
        assertThat(blockHtml).contains("width: 200.00pt;");
        assertThat(blockHtml).contains("min-height: 50.00pt;");
        assertThat(blockHtml).contains("text-align: center;");
    }

    @Test
    @DisplayName("Verify HTML special character escaping")
    void testHtmlEscaping() {
        ParagraphBlock block = new ParagraphBlock(
            new double[]{10.0, 10.0, 100.0, 20.0},
            List.of(new TextRun("<script>alert(\"XSS\")</script> & More", Style.defaultStyle(), new double[]{10.0, 10.0, 100.0, 20.0})),
            "left"
        );

        String blockHtml = htmlGenerator.renderBlock(block);
        assertThat(blockHtml).doesNotContain("<script>");
        assertThat(blockHtml).contains("&lt;script&gt;alert(&quot;XSS&quot;)&lt;/script&gt; &amp; More");
    }

    @Test
    @DisplayName("Verify empty document handling")
    void testEmptyDocument() {
        String html = htmlGenerator.generateHtml(null);
        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("<div class=\"pdf-page\"></div>");
    }

    @Test
    @DisplayName("Verify flow layout styles in generated HTML with reflow support")
    void testFlowLayoutReflowSupport() {
        ParagraphBlock block1 = new ParagraphBlock(
            new double[]{50.0, 40.0, 200.0, 20.0},
            List.of(new TextRun("Block 1", Style.defaultStyle(), new double[]{50.0, 40.0, 200.0, 20.0})),
            "left"
        );
        ParagraphBlock block2 = new ParagraphBlock(
            new double[]{50.0, 70.0, 200.0, 20.0},
            List.of(new TextRun("Block 2", Style.defaultStyle(), new double[]{50.0, 70.0, 200.0, 20.0})),
            "left"
        );

        PageModel page = new PageModel(1, 612.0, 792.0, List.of(block1, block2));
        DocumentModel doc = new DocumentModel("doc-flow", "flow.pdf", List.of(page));

        String html = htmlGenerator.generateHtml(doc);
        assertThat(html).contains("display: flow-root;");
        assertThat(html).contains("overflow-wrap: break-word;");
        assertThat(html).contains("margin-left: 50.00pt;");
        assertThat(html).contains("margin-top: 40.00pt;");
        assertThat(html).contains("margin-top: 10.00pt;");
    }
}
