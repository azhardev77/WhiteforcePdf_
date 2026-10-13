package com.pdfeditor.export;

import com.pdfeditor.model.DocumentModel;
import com.pdfeditor.model.PageModel;
import com.pdfeditor.render.HtmlGenerator;
import org.springframework.stereotype.Service;

@Service
public class PdfExportService {

    private final HtmlGenerator htmlGenerator;
    private final PlaywrightManager playwrightManager;

    public PdfExportService(HtmlGenerator htmlGenerator, PlaywrightManager playwrightManager) {
        this.htmlGenerator = htmlGenerator != null ? htmlGenerator : new HtmlGenerator();
        this.playwrightManager = playwrightManager != null ? playwrightManager : new PlaywrightManager();
    }

    public byte[] exportToPdf(DocumentModel documentModel) {
        if (documentModel == null) {
            throw new IllegalArgumentException("DocumentModel cannot be null");
        }

        String html = htmlGenerator.generateHtml(documentModel);

        double width = 612.0;
        double height = 792.0;
        if (documentModel.getPages() != null && !documentModel.getPages().isEmpty()) {
            PageModel firstPage = documentModel.getPages().get(0);
            if (firstPage.getWidth() > 0) width = firstPage.getWidth();
            if (firstPage.getHeight() > 0) height = firstPage.getHeight();
        }

        return playwrightManager.exportHtmlToPdf(html, width, height);
    }

    public byte[] exportHtmlToPdf(String html, double widthPt, double heightPt) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("HTML content cannot be empty");
        }
        return playwrightManager.exportHtmlToPdf(html, widthPt, heightPt);
    }

    public String exportToHtml(DocumentModel documentModel) {
        if (documentModel == null) {
            throw new IllegalArgumentException("DocumentModel cannot be null");
        }
        return htmlGenerator.generateHtml(documentModel);
    }

    public HtmlGenerator getHtmlGenerator() {
        return htmlGenerator;
    }

    public PlaywrightManager getPlaywrightManager() {
        return playwrightManager;
    }
}
