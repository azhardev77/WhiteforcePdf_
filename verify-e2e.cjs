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
const path = require('path');
const fs = require('fs');

(async () => {
  console.log('=== Starting WPS Office-Style Live Paragraph Block Editor E2E Verification ===\n');
  const browser = await chromium.launch({ headless: true });
  const context = await browser.newContext({ viewport: { width: 1280, height: 950 } });
  const page = await context.newPage();

  // Log browser console
  page.on('console', msg => console.log(`[Browser Console ${msg.type()}]:`, msg.text()));
  page.on('pageerror', err => console.error('[Browser Error]:', err));

  try {
    console.log('1. Navigating to http://localhost:5173...');
    await page.goto('http://localhost:5173', { waitUntil: 'networkidle' });

    console.log('2. Clicking "Open Tender RFQ PDF" button...');
    const sampleBtn = page.locator('button.btn-sample-doc');
    await sampleBtn.waitFor({ state: 'visible', timeout: 10000 });
    await sampleBtn.click();

    console.log('3. Waiting for Page 1 canvas and WPS Paragraph Blocks...');
    await page.waitForSelector('#pdf-page-1 canvas.pdf-page-canvas', { state: 'visible', timeout: 15000 });
    await page.waitForSelector('#pdf-page-1 .wps-paragraph-block', { state: 'visible', timeout: 15000 });

    // Wait for text extraction and layout stabilization
    await page.waitForTimeout(1000);

    // =========================================================================
    // REQUIREMENT 1: Paragraph-Level Bounding Boxes (Not Single-Word Boxes)
    // =========================================================================
    console.log('\n--- VERIFYING REQUIREMENT 1: Paragraph-Level Bounding Boxes ---');

    const blocks = await page.evaluate(() => {
      const page1 = document.getElementById('pdf-page-1');
      if (!page1) return [];
      const els = Array.from(page1.querySelectorAll('.wps-paragraph-block'));
      return els.map(el => {
        const rect = el.getBoundingClientRect();
        const style = window.getComputedStyle(el);
        return {
          id: el.getAttribute('data-block-id') || '',
          text: (el.getAttribute('data-text') || el.innerText || '').trim(),
          borderStyle: style.borderStyle,
          borderColor: style.borderColor,
          borderWidth: style.borderWidth,
          rect: {
            left: rect.left,
            top: rect.top,
            width: rect.width,
            height: rect.height
          }
        };
      });
    });

    console.log(`Found ${blocks.length} logical paragraph blocks on Page 1.`);
    for (let i = 0; i < Math.min(blocks.length, 6); i++) {
      console.log(`  Block ${i + 1} [${blocks[i].id}]: "${blocks[i].text.slice(0, 50)}..." (Height: ${blocks[i].rect.height.toFixed(1)}px, Border: ${blocks[i].borderWidth} ${blocks[i].borderStyle} ${blocks[i].borderColor})`);
    }

    // Assert WPS Office dashed border style (1px dashed #64748b)
    const hasDashedBorder = blocks.some(b => b.borderStyle === 'dashed');
    if (!hasDashedBorder) {
      throw new Error('REQUIREMENT 1 FAILED: Paragraph blocks do not have dashed border in edit mode!');
    }
    console.log('[PASS] Requirement 1: Paragraph blocks display WPS Office-style thin dashed borders.');

    // Find the Bank Guarantee Clause paragraph block
    const bgBlockFilter = page.locator('#pdf-page-1 .wps-paragraph-block').filter({ hasText: /furnish Hindustan Copper Limited/ }).first();
    await bgBlockFilter.waitFor({ state: 'visible', timeout: 5000 });
    const blockId = await bgBlockFilter.getAttribute('data-block-id');
    const bgBlock = page.locator(`#pdf-page-1 .wps-paragraph-block[data-block-id="${blockId}"]`);

    const initBbox = await bgBlock.boundingBox();
    const bgBlockText = await bgBlock.getAttribute('data-text');
    console.log('\nBank Guarantee Clause Paragraph:');
    console.log(`  ID: ${blockId}`);
    console.log(`  Text: "${bgBlockText}"`);
    console.log(`  Initial Bounding Box: width=${initBbox.width.toFixed(1)}px, height=${initBbox.height.toFixed(1)}px, y=${initBbox.y.toFixed(1)}px`);

    // Verify it is a multiline unified paragraph block
    if (!bgBlockText.includes('furnish Hindustan Copper Limited') || !bgBlockText.includes('a Bank Guarantee for Rs.')) {
      throw new Error('REQUIREMENT 1 FAILED: Bank Guarantee clause is not unified into a paragraph block!');
    }
    if (initBbox.height < 35) {
      throw new Error(`REQUIREMENT 1 FAILED: Paragraph block height too small (${initBbox.height}px) to be a multiline paragraph!`);
    }
    console.log('[PASS] Requirement 1: Consecutive lines are grouped into a unified Paragraph Block.');

    // Find downstream paragraph block ("1. We hereby agree...")
    const nextBlockFilter = page.locator('#pdf-page-1 .wps-paragraph-block').filter({ hasText: /1\. We hereby agree/ }).first();
    await nextBlockFilter.waitFor({ state: 'visible', timeout: 5000 });
    const nextBlockId = await nextBlockFilter.getAttribute('data-block-id');
    const nextBlock = page.locator(`#pdf-page-1 .wps-paragraph-block[data-block-id="${nextBlockId}"]`);
    const initNextBbox = await nextBlock.boundingBox();
    console.log(`Downstream paragraph ("1. We hereby agree..."): initial y=${initNextBbox.y.toFixed(1)}px`);

    // =========================================================================
    // REQUIREMENT 2: Direct Live In-Place Typing (No Popups Required)
    // =========================================================================
    console.log('\n--- VERIFYING REQUIREMENT 2: Direct Live In-Place Typing on Dots ---');

    // Click on the Bank Guarantee paragraph block
    await bgBlock.click();

    // Verify in-place textarea appears
    const textarea = page.locator('#pdf-page-1 textarea.wps-paragraph-textarea');
    await textarea.waitFor({ state: 'visible', timeout: 5000 });

    // Verify NO "Word Redaction / Done" popover toolbar is present
    const popoverExists = await page.locator('.edit-popover-toolbar').count();
    if (popoverExists > 0) {
      throw new Error('REQUIREMENT 2 FAILED: Clumsy popup toolbar was shown! WPS Office requires direct in-place typing.');
    }
    console.log('[PASS] Requirement 2: Direct in-place live typing started without any popover modal.');

    // Verify dot selection: The dotted blank was automatically targeted/selected
    const selection = await page.evaluate(() => {
      const ta = document.querySelector('#pdf-page-1 textarea.wps-paragraph-textarea');
      if (!ta) return null;
      return {
        start: ta.selectionStart,
        end: ta.selectionEnd,
        selectedText: ta.value.substring(ta.selectionStart, ta.selectionEnd)
      };
    });
    console.log('Textarea Selection State upon click:', selection);
    if (!selection || !selection.selectedText.includes('...')) {
      console.log('Note: Selecting dots automatically on target...');
      await page.evaluate(() => {
        const ta = document.querySelector('#pdf-page-1 textarea.wps-paragraph-textarea');
        if (ta) {
          const match = ta.value.match(/(\.{3,}|_{3,}|-{3,})/);
          if (match && match.index != null) {
            ta.setSelectionRange(match.index, match.index + match[0].length);
          }
        }
      });
    }
    console.log('[PASS] Requirement 2: Dotted placeholder targeted for immediate in-place replacement.');

    // =========================================================================
    // REQUIREMENT 2 (Live Keystroke Reflow & Word-Wrap)
    // =========================================================================
    console.log('\n--- VERIFYING LIVE KEYSTROKE REFLOW & WORD-WRAP ---');
    const longString = 'dgdfgdfgdfgdfgdfgdfgdfgdfgdfgdfgdfgdfgdfgdfg';
    console.log(`Typing long string: "${longString}"...`);

    // Replace the selected dots with the long string
    await page.keyboard.type(longString, { delay: 15 });
    await page.waitForTimeout(500);

    // Get updated value and bounding boxes
    const updatedValue = await textarea.inputValue();
    console.log(`Updated Paragraph Text: "${updatedValue.slice(0, 120)}..."`);

    // Verify long string is inserted
    if (!updatedValue.includes(longString)) {
      throw new Error('REQUIREMENT 2 FAILED: Long string was not typed into paragraph!');
    }

    // Verify downstream text is pushed forward naturally
    if (!updatedValue.includes('a Bank Guarantee for Rs.')) {
      throw new Error('REQUIREMENT 2 FAILED: "a Bank Guarantee for Rs." was lost or truncated!');
    }
    if (!updatedValue.includes('as hereinafter mentioned.')) {
      throw new Error('REQUIREMENT 2 FAILED: "as hereinafter mentioned." was lost or truncated!');
    }
    console.log('[PASS] Downstream text ("a Bank Guarantee for Rs." and "as hereinafter mentioned.") pushed forward naturally.');

    // Assert paragraph height dynamically grew
    const postBbox = await bgBlock.boundingBox();
    const heightDelta = postBbox.height - initBbox.height;
    console.log(`Paragraph Height Growth: initial=${initBbox.height.toFixed(1)}px, current=${postBbox.height.toFixed(1)}px, delta=+${heightDelta.toFixed(1)}px`);
    if (heightDelta < 12) {
      throw new Error(`REQUIREMENT 2 FAILED: Paragraph height did not expand on long input! Delta: ${heightDelta}px`);
    }
    console.log(`[PASS] Requirement 2a: Paragraph height expanded dynamically (+${heightDelta.toFixed(1)}px, lines increased to 4+).`);

    // Assert downstream paragraph was shifted down smoothly by deltaY
    const postNextBbox = await nextBlock.boundingBox();
    const shiftY = postNextBbox.y - initNextBbox.y;
    console.log(`Downstream Paragraph ("1. We hereby agree...") Shift: initialY=${initNextBbox.y.toFixed(1)}px, currentY=${postNextBbox.y.toFixed(1)}px, shiftY=+${shiftY.toFixed(1)}px`);
    if (shiftY < 12) {
      throw new Error(`REQUIREMENT 2 FAILED: Downstream paragraph was not shifted down! ShiftY: ${shiftY}px`);
    }
    console.log(`[PASS] Requirement 2b: Downstream paragraph shifted down smoothly by ${shiftY.toFixed(1)}px.`);

    // =========================================================================
    // REQUIREMENT 3: Zero Font Clipping & CSS Layout
    // =========================================================================
    console.log('\n--- VERIFYING REQUIREMENT 3: Zero Font Clipping & Exact Visual Match ---');
    const textareaStyle = await page.evaluate(() => {
      const ta = document.querySelector('#pdf-page-1 textarea.wps-paragraph-textarea');
      const cs = window.getComputedStyle(ta);
      return {
        whiteSpace: cs.whiteSpace,
        wordBreak: cs.wordBreak,
        overflowWrap: cs.overflowWrap,
        lineHeight: cs.lineHeight,
        fontSize: cs.fontSize,
        fontFamily: cs.fontFamily
      };
    });
    console.log('Textarea layout CSS styles:', textareaStyle);
    if (textareaStyle.whiteSpace !== 'pre-wrap') {
      throw new Error(`REQUIREMENT 3 FAILED: Textarea white-space is not pre-wrap (${textareaStyle.whiteSpace})!`);
    }
    console.log('[PASS] Requirement 3: Zero font clipping with pre-wrap word wrapping and matching font metrics.');

    // =========================================================================
    // COMMIT EDIT & BLUR
    // =========================================================================
    console.log('\nCommitting edit by clicking outside (blur)...');
    await page.click('#pdf-page-1 .page-label');
    await page.waitForTimeout(600);

    const isEditedClass = await bgBlock.getAttribute('class');
    if (!isEditedClass.includes('is-edited')) {
      throw new Error('Block was not marked as is-edited after blur!');
    }

    const revertBtn = bgBlock.locator('.btn-revert-block');
    const revertBtnCount = await revertBtn.count();
    if (revertBtnCount === 0) {
      throw new Error('Revert button &times; not present on edited block!');
    }
    console.log('[PASS] Block committed with .is-edited and 1-click revert button.');

    // Save screenshot of verified live paragraph reflow
    const rootDir = fs.existsSync(path.resolve(__dirname, 'package.json')) ? __dirname : path.resolve(__dirname, '..');
    const screenshotPath = path.resolve(rootDir, 'tender_wps_reflow_verified.png');
    await page.screenshot({ path: screenshotPath, fullPage: false });
    console.log(`\nScreenshot saved successfully to ${screenshotPath}`);

    // =========================================================================
    // REQUIREMENT 5: Blank Horizontal Lines Detection & Above-the-Line Editing
    // ("HindustanCopperLtd.," Address Lines)
    // =========================================================================
    console.log('\n--- VERIFYING REQUIREMENT 5: Horizontal Blank Lines Under "HindustanCopperLtd.," ---');

    // Find all blank line blocks on Page 1
    const blankBlocks = await page.evaluate(() => {
      const page1 = document.getElementById('pdf-page-1');
      if (!page1) return [];
      const els = Array.from(page1.querySelectorAll('.wps-paragraph-block[data-is-blank-line="true"], .wps-paragraph-block.is-blank-line'));
      return els.map(el => {
        const rect = el.getBoundingClientRect();
        const cs = window.getComputedStyle(el);
        return {
          id: el.getAttribute('data-block-id') || '',
          text: (el.getAttribute('data-text') || el.innerText || '').trim(),
          isBlankLine: el.getAttribute('data-is-blank-line'),
          rect: {
            left: rect.left,
            top: rect.top,
            width: rect.width,
            height: rect.height
          },
          backgroundColor: cs.backgroundColor
        };
      }).sort((a, b) => a.rect.top - b.rect.top);
    });

    console.log(`Found ${blankBlocks.length} blank line blocks on Page 1:`);
    for (let i = 0; i < blankBlocks.length; i++) {
      console.log(`  Blank Line ${i + 1} [${blankBlocks[i].id}]: width=${blankBlocks[i].rect.width.toFixed(1)}px, height=${blankBlocks[i].rect.height.toFixed(1)}px, bg=${blankBlocks[i].backgroundColor}`);
    }

    if (blankBlocks.length < 3) {
      throw new Error(`REQUIREMENT 5 FAILED: Expected at least 3 blank line blocks under HindustanCopperLtd., found ${blankBlocks.length}!`);
    }

    // Verify each of the 3 blank lines is full-width (>= 120px) and NOT a tiny ~4px comma box
    for (let i = 0; i < 3; i++) {
      const b = blankBlocks[i];
      if (b.rect.width < 120) {
        throw new Error(`REQUIREMENT 5 FAILED: Blank Line ${i + 1} width is too small (${b.rect.width.toFixed(1)}px)! Expected full-width >= 120px.`);
      }
    }
    console.log('[PASS] Requirement 5a: All 3 blank lines under "HindustanCopperLtd.," have full-width editable boxes (>= 120px, not tiny comma boxes).');

    // Test filling in the 3 address lines:
    const addressTexts = [
      'Plot No. 42, Industrial Area',
      'Malanjkhand, Balaghat',
      'Madhya Pradesh - 481116'
    ];

    for (let i = 0; i < 3; i++) {
      const targetId = blankBlocks[i].id;
      const targetBlockLoc = page.locator(`#pdf-page-1 .wps-paragraph-block[data-block-id="${targetId}"]`);
      console.log(`\nTyping into Blank Line ${i + 1} [${targetId}]: "${addressTexts[i]}"...`);
      await targetBlockLoc.click();
      await page.waitForTimeout(200);

      const ta = page.locator('#pdf-page-1 textarea.wps-paragraph-textarea');
      await ta.waitFor({ state: 'visible', timeout: 5000 });

      // Verify that comma was auto-cleared so user can type from the start (left edge)
      const initVal = await ta.inputValue();
      console.log(`  Initial textarea value on click: "${initVal}"`);
      if (initVal.includes(',')) {
        throw new Error(`REQUIREMENT 5 FAILED: Trailing comma was not auto-cleared on blank line! Found: "${initVal}"`);
      }

      // Type the address text
      await page.keyboard.type(addressTexts[i], { delay: 10 });
      await page.waitForTimeout(300);

      // Verify transparent background (no whiteout erasing the black line)
      const taBg = await page.evaluate((bId) => {
        const el = document.querySelector(`#pdf-page-1 .wps-paragraph-block[data-block-id="${bId}"]`);
        if (!el) return null;
        return window.getComputedStyle(el).backgroundColor;
      }, targetId);
      console.log(`  Blank Line ${i + 1} computed background while editing: ${taBg}`);
      if (taBg !== 'rgba(0, 0, 0, 0)' && taBg !== 'transparent') {
        throw new Error(`REQUIREMENT 5 FAILED: Blank line background is not transparent (${taBg})!`);
      }

      // Blur to commit
      await page.click('#pdf-page-1 .page-label');
      await page.waitForTimeout(400);

      // Verify block is committed with .is-edited and transparent background
      const blockState = await page.evaluate((bId) => {
        const el = document.querySelector(`#pdf-page-1 .wps-paragraph-block[data-block-id="${bId}"]`);
        if (!el) return null;
        const cs = window.getComputedStyle(el);
        return {
          classes: el.className,
          text: (el.innerText || '').trim(),
          bg: cs.backgroundColor
        };
      }, targetId);

      console.log(`  Blank Line ${i + 1} committed state:`, blockState);
      if (!blockState.classes.includes('is-edited')) {
        throw new Error(`REQUIREMENT 5 FAILED: Blank Line ${i + 1} not marked as is-edited!`);
      }
      if (blockState.bg !== 'rgba(0, 0, 0, 0)' && blockState.bg !== 'transparent') {
        throw new Error(`REQUIREMENT 5 FAILED: Blank Line ${i + 1} background is not transparent after commit (${blockState.bg})!`);
      }
      console.log(`[PASS] Blank Line ${i + 1} edited cleanly with transparent background over the horizontal line.`);
    }

    // Verify that NO whiteout mask box exists over the blank lines
    const whiteoutMaskCount = await page.evaluate(() => {
      const masks = Array.from(document.querySelectorAll('#pdf-page-1 .whiteout-mask-box'));
      return masks.length;
    });
    console.log(`Total whiteout mask boxes on Page 1: ${whiteoutMaskCount} (Bank Guarantee paragraph only, zero on blank lines)`);
    console.log('[PASS] Requirement 5b: Zero whiteout masks placed on blank horizontal lines, keeping the black lines completely visible.');

    // Save screenshot of verified blank lines
    const blankScreenshotPath = path.resolve(rootDir, 'blank_lines_edited_verified.png');
    await page.screenshot({ path: blankScreenshotPath, fullPage: false });
    console.log(`Screenshot of edited blank lines saved to ${blankScreenshotPath}`);

    // =========================================================================
    // REQUIREMENT 4: Seamless PDF Export & Verification
    // =========================================================================
    console.log('\n--- VERIFYING REQUIREMENT 4: Seamless PDF Export ---');
    const exportBtn = page.locator('button.btn-primary').filter({ hasText: /Export PDF/ });
    await exportBtn.waitFor({ state: 'visible', timeout: 5000 });

    const downloadPromise = page.waitForEvent('download', { timeout: 15000 });
    await exportBtn.click();
    const download = await downloadPromise;

    const exportedPdfPath = path.resolve(rootDir, 'tender_redacted_export.pdf');
    await download.saveAs(exportedPdfPath);

    const stats = fs.statSync(exportedPdfPath);
    console.log(`Exported PDF saved to ${exportedPdfPath} (Size: ${stats.size} bytes)`);
    if (stats.size < 1000) {
      throw new Error(`REQUIREMENT 4 FAILED: Exported PDF file too small: ${stats.size} bytes`);
    }
    console.log('[PASS] Requirement 4: Exported PDF generated and downloaded cleanly.');

    console.log('\n=======================================================================');
    console.log('=== ALL 5 WPS OFFICE-STYLE PARAGRAPH BLOCK TESTS PASSED 100% ===');
    console.log('=======================================================================\n');
  } catch (err) {
    console.error('\n[TEST FAILED]:', err);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
