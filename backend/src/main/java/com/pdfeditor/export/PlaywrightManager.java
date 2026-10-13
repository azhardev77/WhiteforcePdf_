package com.pdfeditor.export;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.Margin;
import com.microsoft.playwright.options.WaitUntilState;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class PlaywrightManager implements AutoCloseable {

    private Playwright playwright;
    private Browser browser;
    private boolean initialized = false;

    @PostConstruct
    public synchronized void init() {
        if (!initialized) {
            playwright = Playwright.create();
            browser = playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
            initialized = true;
        }
    }

    public synchronized void ensureInitialized() {
        if (!initialized || browser == null || !browser.isConnected()) {
            init();
        }
    }

    public synchronized byte[] exportHtmlToPdf(String html, double widthPt, double heightPt) {
        ensureInitialized();
        Page.PdfOptions options = createPdfOptions(widthPt, heightPt);
        return exportHtmlToPdf(html, options);
    }

    public synchronized byte[] exportHtmlToPdf(String html, Page.PdfOptions options) {
        ensureInitialized();
        try (BrowserContext context = browser.newContext()) {
            Page page = context.newPage();
            page.setContent(html, new Page.SetContentOptions().setWaitUntil(WaitUntilState.NETWORKIDLE));
            return page.pdf(options);
        }
    }

    public Page.PdfOptions createPdfOptions(double widthPt, double heightPt) {
        Page.PdfOptions options = new Page.PdfOptions();
        double wPt = widthPt > 0 ? widthPt : 612.0;
        double hPt = heightPt > 0 ? heightPt : 792.0;

        // Convert PDF points (1 pt = 1/72 inch) to inches for Playwright options
        double widthInches = wPt / 72.0;
        double heightInches = hPt / 72.0;

        options.setWidth(String.format(Locale.US, "%.4fin", widthInches));
        options.setHeight(String.format(Locale.US, "%.4fin", heightInches));
        options.setPrintBackground(true);
        options.setDisplayHeaderFooter(false);
        options.setPreferCSSPageSize(true);
        options.setMargin(new Margin().setTop("0").setBottom("0").setLeft("0").setRight("0"));
        return options;
    }

    public synchronized Browser getBrowser() {
        ensureInitialized();
        return browser;
    }

    public synchronized Playwright getPlaywright() {
        ensureInitialized();
        return playwright;
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        if (browser != null) {
            try {
                browser.close();
            } catch (Exception ignored) {
            }
            browser = null;
        }
        if (playwright != null) {
            try {
                playwright.close();
            } catch (Exception ignored) {
            }
            playwright = null;
        }
        initialized = false;
    }
}
