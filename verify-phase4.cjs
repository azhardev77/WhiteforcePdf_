let playwright;
try {
  playwright = require('playwright');
} catch (e) {
  try {
    playwright = require('./frontend/node_modules/playwright');
  } catch (e2) {
    playwright = require('./node_modules/playwright');
  }
}
const { chromium } = playwright;
const fs = require('fs');
const path = require('path');
const http = require('http');

(async () => {
  console.log('=== Starting Phase 4 Frontend Home + Routes Verification ===\n');

  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 900 } });
  const page = await context.newPage();

  page.on('console', msg => {
    if (msg.type() === 'error') {
      console.log(`[Browser Console Error]:`, msg.text());
    }
  });
  page.on('pageerror', err => console.error('[Browser Unhandled Error]:', err));

  try {
    // -------------------------------------------------------------------------
    // Step 1: Verify HomeView at "/"
    // -------------------------------------------------------------------------
    console.log('1. Navigating to HomeView at http://localhost:5173/ ...');
    await page.goto('http://localhost:5173/', { waitUntil: 'networkidle' });

    console.log('   Checking 3 main mode buttons (Resume, Tender, Edit PDF)...');
    const resumeBtn = page.locator('[data-testid="btn-resume-mode"]');
    const tenderBtn = page.locator('[data-testid="btn-tender-mode"]');
    const editBtn = page.locator('[data-testid="btn-edit-mode"]');

    await resumeBtn.waitFor({ state: 'visible', timeout: 5000 });
    await tenderBtn.waitFor({ state: 'visible', timeout: 5000 });
    await editBtn.waitFor({ state: 'visible', timeout: 5000 });
    console.log('   [PASS] 3 main mode buttons are present and visible on HomeView.');

    console.log('   Checking secondary "API access" card (docs + sample curl)...');
    const apiCard = page.locator('[data-testid="card-api-access"]');
    await apiCard.waitFor({ state: 'visible', timeout: 5000 });
    const apiText = await apiCard.innerText();
    if (!apiText.includes('POST') || !apiText.includes('/api/v1/sessions') || !apiText.includes('curl')) {
      throw new Error('API card does not contain expected endpoints or sample curl documentation!');
    }
    console.log('   [PASS] API access card is present with REST documentation and curl snippets.');

    // -------------------------------------------------------------------------
    // Step 2: Verify dedicated routes: /resume, /tender, /edit
    // -------------------------------------------------------------------------
    console.log('\n2. Testing route navigation for /resume, /tender, /edit...');
    
    // /resume
    await page.goto('http://localhost:5173/resume', { waitUntil: 'networkidle' });
    await page.locator('.pill-resume').waitFor({ state: 'visible', timeout: 5000 });
    await page.locator('[data-testid="btn-sample-resume"]').waitFor({ state: 'visible', timeout: 5000 });
    console.log('   [PASS] Route /resume correctly loads Resume mode workspace.');

    // /tender
    await page.goto('http://localhost:5173/tender', { waitUntil: 'networkidle' });
    await page.locator('.pill-tender').waitFor({ state: 'visible', timeout: 5000 });
    await page.locator('[data-testid="btn-sample-tender"]').waitFor({ state: 'visible', timeout: 5000 });
    console.log('   [PASS] Route /tender correctly loads Tender mode workspace.');

    // /edit
    await page.goto('http://localhost:5173/edit', { waitUntil: 'networkidle' });
    await page.locator('.pill-edit').waitFor({ state: 'visible', timeout: 5000 });
    await page.locator('[data-testid="btn-sample-edit"]').waitFor({ state: 'visible', timeout: 5000 });
    console.log('   [PASS] Route /edit correctly loads Edit mode workspace.');

    // -------------------------------------------------------------------------
    // Step 3: Upload -> POST /app/sessions -> /edit/:sessionId with mode badge
    // -------------------------------------------------------------------------
    console.log('\n3. Testing Upload flow: POST /app/sessions -> /edit/:sessionId ...');
    await page.goto('http://localhost:5173/resume', { waitUntil: 'networkidle' });
    const sampleResumeAction = page.locator('[data-testid="btn-sample-resume"]');
    await sampleResumeAction.waitFor({ state: 'visible', timeout: 5000 });
    await sampleResumeAction.click();

    console.log('   Waiting for session creation and redirection to /edit/:sessionId ...');
    await page.waitForURL(/\/edit\/[a-zA-Z0-9_-]+/, { timeout: 15000 });
    const currentUrl = page.url();
    const sessionId = currentUrl.split('/edit/')[1];
    console.log(`   [PASS] Successfully redirected to session URL: /edit/${sessionId}`);

    console.log('   Waiting for editor canvas to mount and render session PDF...');
    await page.waitForSelector('#pdf-page-1 canvas.pdf-page-canvas', { state: 'visible', timeout: 15000 });
    await page.waitForSelector('#pdf-page-1 .wps-paragraph-block', { state: 'visible', timeout: 15000 });

    console.log('   Checking session mode badge (UPLOAD source)...');
    const modeBadge = page.locator('[data-testid="session-mode-badge"]');
    await modeBadge.waitFor({ state: 'visible', timeout: 5000 });
    const badgeText = (await modeBadge.innerText()).trim();
    console.log(`   Badge text: "${badgeText}"`);
    if (!badgeText.includes('RESUME') || !badgeText.includes('UPLOAD')) {
      throw new Error(`Expected badge to contain "RESUME" and "UPLOAD", but got "${badgeText}"`);
    }
    console.log('   [PASS] Session badge displays "RESUME • UPLOAD" correctly.');

    // -------------------------------------------------------------------------
    // Step 4: Verify API-created session (/edit/:sessionId with API source)
    // -------------------------------------------------------------------------
    console.log('\n4. Testing API-created session: POST /api/v1/sessions -> /edit/:sessionId with API source...');
    // Create an API session via fetch in Node
    const resumePath = path.resolve(__dirname, 'sample-pdfs', 'resume.pdf');
    const fileBytes = fs.readFileSync(resumePath);
    const boundary = '----WebKitFormBoundary' + Math.random().toString(36).substring(2);

    const postData = Buffer.concat([
      Buffer.from(`--${boundary}\r\nContent-Disposition: form-data; name="file"; filename="resume.pdf"\r\nContent-Type: application/pdf\r\n\r\n`),
      fileBytes,
      Buffer.from(`\r\n--${boundary}\r\nContent-Disposition: form-data; name="mode"\r\n\r\nRESUME\r\n--${boundary}--\r\n`)
    ]);

    const apiKey = process.env.API_KEY;
    if (!apiKey) {
      throw new Error('API_KEY is not set in environment!');
    }

    const apiSession = await new Promise((resolve, reject) => {
      const req = http.request({
        hostname: 'localhost',
        port: 8080,
        path: '/api/v1/sessions',
        method: 'POST',
        headers: {
          'Content-Type': `multipart/form-data; boundary=${boundary}`,
          'Content-Length': postData.length,
          'X-API-KEY': apiKey
        }
      }, (res) => {
        let body = '';
        res.on('data', chunk => body += chunk);
        res.on('end', () => {
          if (res.statusCode >= 200 && res.statusCode < 300) {
            resolve(JSON.parse(body));
          } else {
            reject(new Error(`API session creation failed (${res.statusCode}): ${body}`));
          }
        });
      });
      req.on('error', reject);
      req.write(postData);
      req.end();
    });

    console.log(`   Created API Session: id=${apiSession.id}, editUrl=${apiSession.editUrl}`);
    await page.goto(apiSession.editUrl, { waitUntil: 'networkidle' });

    console.log('   Waiting for editor canvas to mount for API session...');
    await page.waitForSelector('#pdf-page-1 canvas.pdf-page-canvas', { state: 'visible', timeout: 15000 });
    const apiBadge = page.locator('[data-testid="session-mode-badge"]');
    await apiBadge.waitFor({ state: 'visible', timeout: 5000 });
    const apiBadgeText = (await apiBadge.innerText()).trim();
    console.log(`   API Session Badge text: "${apiBadgeText}"`);
    if (!apiBadgeText.includes('RESUME') || !apiBadgeText.includes('API')) {
      throw new Error(`Expected badge to contain "RESUME" and "API", but got "${apiBadgeText}"`);
    }
    console.log('   [PASS] API Session badge displays "RESUME • API" correctly.');

    // -------------------------------------------------------------------------
    // Step 5: Verify Legacy editor at /editor
    // -------------------------------------------------------------------------
    console.log('\n5. Testing /editor standalone legacy reachability...');
    const legacyContext = await browser.newContext({ viewport: { width: 1280, height: 900 } });
    const legacyPage = await legacyContext.newPage();
    await legacyPage.goto('http://localhost:5173/editor', { waitUntil: 'networkidle' });
    const sampleTenderBtn = legacyPage.locator('button.btn-sample-tender');
    await sampleTenderBtn.waitFor({ state: 'visible', timeout: 5000 });
    console.log('   [PASS] Standalone legacy editor is reachable at /editor with sample buttons.');
    await legacyPage.close();
    await legacyContext.close();

    console.log('\n=======================================================================');
    console.log('=== PHASE 4 VERIFICATION COMPLETED SUCCESSFULLY (100% PASS) ===');
    console.log('=======================================================================\n');

  } catch (err) {
    console.error('\n[PHASE 4 VERIFICATION FAILED]:', err);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
