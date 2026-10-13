package com.pdfeditor.extraction;

import com.pdfeditor.model.Block;
import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.model.PageModel;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
public class ExtractionPipeline {

    private final CharacterCollector characterCollector;
    private final WordGrouper wordGrouper;
    private final LineGrouper lineGrouper;
    private final TableExtractor tableExtractor;
    private final BlockGrouper blockGrouper;

    public ExtractionPipeline() {
        this.wordGrouper = new WordGrouper();
        this.lineGrouper = new LineGrouper(this.wordGrouper);
        this.tableExtractor = new TableExtractor();
        this.blockGrouper = new BlockGrouper();
        this.characterCollector = new CharacterCollector();
    }

    public ExtractionPipeline(CharacterCollector characterCollector,
                              WordGrouper wordGrouper,
                              LineGrouper lineGrouper,
                              BlockGrouper blockGrouper) {
        this(characterCollector, wordGrouper, lineGrouper, new TableExtractor(), blockGrouper);
    }

    public ExtractionPipeline(CharacterCollector characterCollector,
                              WordGrouper wordGrouper,
                              LineGrouper lineGrouper,
                              TableExtractor tableExtractor,
                              BlockGrouper blockGrouper) {
        this.characterCollector = characterCollector != null ? characterCollector : new CharacterCollector();
        this.wordGrouper = wordGrouper != null ? wordGrouper : new WordGrouper();
        this.lineGrouper = lineGrouper != null ? lineGrouper : new LineGrouper(this.wordGrouper);
        this.tableExtractor = tableExtractor != null ? tableExtractor : new TableExtractor();
        this.blockGrouper = blockGrouper != null ? blockGrouper : new BlockGrouper();
    }

    public DocumentModel extract(Path pdfPath) throws IOException {
        if (pdfPath == null) {
            throw new IllegalArgumentException("pdfPath cannot be null");
        }
        File file = pdfPath.toFile();
        try (PDDocument document = Loader.loadPDF(file)) {
            String documentId = UUID.randomUUID().toString();
            String name = pdfPath.getFileName() != null ? pdfPath.getFileName().toString() : "document.pdf";
            return extractDocument(document, documentId, name);
        }
    }

    public DocumentModel extract(File file) throws IOException {
        if (file == null) {
            throw new IllegalArgumentException("file cannot be null");
        }
        try (PDDocument document = Loader.loadPDF(file)) {
            String documentId = UUID.randomUUID().toString();
            return extractDocument(document, documentId, file.getName());
        }
    }

    public DocumentModel extract(byte[] pdfBytes, String name) throws IOException {
        if (pdfBytes == null) {
            throw new IllegalArgumentException("pdfBytes cannot be null");
        }
        try (PDDocument document = Loader.loadPDF(pdfBytes)) {
            String documentId = UUID.randomUUID().toString();
            return extractDocument(document, documentId, name != null ? name : "document.pdf");
        }
    }

    public DocumentModel extract(InputStream inputStream, String name) throws IOException {
        if (inputStream == null) {
            throw new IllegalArgumentException("inputStream cannot be null");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[8192];
        int nRead;
        while ((nRead = inputStream.read(data, 0, data.length)) != -1) {
            buffer.write(data, 0, nRead);
        }
        return extract(buffer.toByteArray(), name);
    }

    public DocumentModel extractDocument(PDDocument document, String documentId, String name) throws IOException {
        if (document == null) {
            throw new IllegalArgumentException("document cannot be null");
        }

        int pageCount = document.getNumberOfPages();
        List<PageModel> pages = new ArrayList<>();

        for (int i = 0; i < pageCount; i++) {
            int pageNumber = i + 1; // 1-based page numbering
            PDPage pdPage = document.getPage(i);
            PDRectangle cropBox = pdPage.getCropBox();
            if (cropBox == null) {
                cropBox = pdPage.getMediaBox();
            }

            double width = cropBox != null ? cropBox.getWidth() : 612.0;
            double height = cropBox != null ? cropBox.getHeight() : 792.0;

            // Pure per-page collection isolating state to avoid multi-page leaks
            List<Glyph> glyphs = characterCollector.collectPage(document, pageNumber);

            // Run geometry clustering pipeline for this page
            List<Line> lines = lineGrouper.groupGlyphsIntoLines(glyphs);
            // Detect tabular multi-column regions before falling back to regular paragraphs
            List<Block> blocks = tableExtractor.extractBlocks(lines, blockGrouper);

            PageModel pageModel = new PageModel(pageNumber, width, height, blocks);
            pages.add(pageModel);
        }

        return new DocumentModel(documentId, name, pages);
    }

    public CharacterCollector getCharacterCollector() {
        return characterCollector;
    }

    public WordGrouper getWordGrouper() {
        return wordGrouper;
    }

    public LineGrouper getLineGrouper() {
        return lineGrouper;
    }

    public TableExtractor getTableExtractor() {
        return tableExtractor;
    }

    public BlockGrouper getBlockGrouper() {
        return blockGrouper;
    }
}
