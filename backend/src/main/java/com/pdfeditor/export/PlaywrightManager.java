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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Manages Playwright browser instances for high-fidelity HTML-to-PDF rendering.
 * Enforces a maximum concurrency limit of 2 instances via Semaphore to eliminate
 * OOM container crashes, and bounds rendering execution to a strict 15-second timeout.
 */
@Component
public class PlaywrightManager implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightManager.class);

    public static final int MAX_CONCURRENT_INSTANCES = 2;
    public static final long RENDER_TIMEOUT_SECONDS = 15L;

    private final Semaphore renderSemaphore = new Semaphore(MAX_CONCURRENT_INSTANCES, true);
    private final BlockingQueue<BrowserInstance> instancePool = new ArrayBlockingQueue<>(MAX_CONCURRENT_INSTANCES);
    private final List<BrowserInstance> allInstances = new CopyOnWriteArrayList<>();
    private final AtomicInteger totalCreated = new AtomicInteger(0);

    private final ExecutorService renderExecutor = Executors.newFixedThreadPool(MAX_CONCURRENT_INSTANCES, r -> {
        Thread t = new Thread(r, "playwright-render-pool");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean initialized = false;

    @PostConstruct
    public synchronized void init() {
        if (!initialized) {
            BrowserInstance first = new BrowserInstance();
            totalCreated.incrementAndGet();
            allInstances.add(first);
            instancePool.offer(first);
            initialized = true;
            log.info("PlaywrightManager initialized with primary browser instance (max concurrency: {})", MAX_CONCURRENT_INSTANCES);
        }
    }

    public void ensureInitialized() {
        if (!initialized) {
            init();
        }
    }

    public byte[] exportHtmlToPdf(String html, double widthPt, double heightPt) {
        Page.PdfOptions options = createPdfOptions(widthPt, heightPt);
        return exportHtmlToPdf(html, options);
    }

    public byte[] exportHtmlToPdf(String html, Page.PdfOptions options) {
        ensureInitialized();
        Page.PdfOptions pdfOptions = options != null ? options : createPdfOptions(612.0, 792.0);

        boolean acquired;
        try {
            acquired = renderSemaphore.tryAcquire(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for PDF rendering capacity slot", e);
        }

        if (!acquired) {
            throw new IllegalStateException("PDF rendering capacity exceeded: maximum "
                    + MAX_CONCURRENT_INSTANCES + " concurrent rendering operations in use. Please retry shortly.");
        }

        BrowserInstance instance = null;
        try {
            instance = borrowInstance(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (instance == null) {
                throw new IllegalStateException("Failed to acquire Playwright browser instance within "
                        + RENDER_TIMEOUT_SECONDS + " seconds.");
            }
            return executeRenderWithTimeout(instance, html, pdfOptions);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Playwright browser instance", e);
        } finally {
            if (instance != null) {
                returnInstance(instance);
            }
            renderSemaphore.release();
        }
    }

    private byte[] executeRenderWithTimeout(BrowserInstance instance, String html, Page.PdfOptions options) {
        CompletableFuture<byte[]> future = CompletableFuture.supplyAsync(() -> {
            BrowserContext context = null;
            Page page = null;
            try {
                context = instance.browser.newContext();
                page = context.newPage();
                page.setDefaultTimeout(RENDER_TIMEOUT_SECONDS * 1000);
                page.setContent(html, new Page.SetContentOptions()
                        .setWaitUntil(WaitUntilState.NETWORKIDLE)
                        .setTimeout(RENDER_TIMEOUT_SECONDS * 1000));
                return page.pdf(options);
            } finally {
                if (page != null) {
                    try {
                        page.close();
                    } catch (Exception ignored) {
                    }
                }
                if (context != null) {
                    try {
                        context.close();
                    } catch (Exception ignored) {
                    }
                }
            }
        }, renderExecutor);

        try {
            return future.get(RENDER_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("PDF rendering exceeded execution timeout of "
                    + RENDER_TIMEOUT_SECONDS + " seconds.", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("PDF rendering was interrupted.", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            throw new RuntimeException("PDF rendering failed: " + cause.getMessage(), cause);
        }
    }

    private BrowserInstance borrowInstance(long timeout, TimeUnit unit) throws InterruptedException {
        BrowserInstance instance = instancePool.poll();
        if (instance != null) {
            if (instance.isHealthy()) {
                return instance;
            }
            instance.close();
            allInstances.remove(instance);
            totalCreated.decrementAndGet();
        }

        synchronized (this) {
            if (totalCreated.get() < MAX_CONCURRENT_INSTANCES) {
                BrowserInstance created = new BrowserInstance();
                totalCreated.incrementAndGet();
                allInstances.add(created);
                return created;
            }
        }

        BrowserInstance waited = instancePool.poll(timeout, unit);
        if (waited != null && !waited.isHealthy()) {
            waited.close();
            allInstances.remove(waited);
            try {
                BrowserInstance fresh = new BrowserInstance();
                allInstances.add(fresh);
                return fresh;
            } catch (Exception e) {
                totalCreated.decrementAndGet();
                throw e;
            }
        }
        return waited;
    }

    private void returnInstance(BrowserInstance instance) {
        if (instance != null) {
            if (instance.isHealthy()) {
                instancePool.offer(instance);
            } else {
                instance.close();
                allInstances.remove(instance);
                try {
                    BrowserInstance fresh = new BrowserInstance();
                    allInstances.add(fresh);
                    instancePool.offer(fresh);
                } catch (Exception ignored) {
                    totalCreated.decrementAndGet();
                }
            }
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
        for (BrowserInstance inst : allInstances) {
            if (inst != null && inst.isHealthy()) {
                return inst.browser;
            }
        }
        return null;
    }

    public synchronized Playwright getPlaywright() {
        ensureInitialized();
        for (BrowserInstance inst : allInstances) {
            if (inst != null && inst.isHealthy()) {
                return inst.playwright;
            }
        }
        return null;
    }

    public Semaphore getRenderSemaphore() {
        return renderSemaphore;
    }

    public int getAvailablePermits() {
        return renderSemaphore.availablePermits();
    }

    @Override
    @PreDestroy
    public synchronized void close() {
        renderExecutor.shutdown();
        try {
            if (!renderExecutor.awaitTermination(3, TimeUnit.SECONDS)) {
                renderExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            renderExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }

        for (BrowserInstance inst : allInstances) {
            try {
                inst.close();
            } catch (Exception ignored) {
            }
        }
        allInstances.clear();
        instancePool.clear();
        totalCreated.set(0);
        initialized = false;
    }

    private static class BrowserInstance implements AutoCloseable {
        private final Playwright playwright;
        private final Browser browser;

        BrowserInstance() {
            this.playwright = Playwright.create();
            this.browser = this.playwright.chromium().launch(new BrowserType.LaunchOptions().setHeadless(true));
        }

        boolean isHealthy() {
            return browser != null && browser.isConnected();
        }

        @Override
        public void close() {
            if (browser != null) {
                try {
                    browser.close();
                } catch (Exception ignored) {
                }
            }
            if (playwright != null) {
                try {
                    playwright.close();
                } catch (Exception ignored) {
                }
            }
        }
    }
}
