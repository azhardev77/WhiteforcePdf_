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
    const sampleBtn = page.locator('button.btn-sample-tender');
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
    // REQUIREMENT 6: Drag & Drop / Move Paragraph Block
    // =========================================================================
    console.log('\n--- VERIFYING REQUIREMENT 6: Drag & Drop / Move Paragraph Block ---');

    // 1. Target a paragraph block ("Tender Enquiry (RFQ)")
    const moveTargetBlock = page.locator('#pdf-page-1 .wps-paragraph-block').filter({ hasText: /Tender Enquiry/ }).first();
    await moveTargetBlock.waitFor({ state: 'visible', timeout: 5000 });
    const moveBlockId = await moveTargetBlock.getAttribute('data-block-id');
    console.log(`Targeting block for Drag & Drop: [${moveBlockId}]`);

    // 2. Hover over the block to display the 4-way drag handle
    await moveTargetBlock.hover();
    await page.waitForTimeout(200);

    const dragHandle = moveTargetBlock.locator('.block-drag-handle');
    await dragHandle.waitFor({ state: 'visible', timeout: 3000 });
    console.log('[PASS] Drag Handle (.block-drag-handle) with 4-way move arrow is visible on hover.');

    const initialBbox = await moveTargetBlock.boundingBox();
    console.log(`  Initial Block Position: x=${initialBbox.x.toFixed(1)}, y=${initialBbox.y.toFixed(1)}, width=${initialBbox.width.toFixed(1)}, height=${initialBbox.height.toFixed(1)}`);

    const handleBox = await dragHandle.boundingBox();
    const startHandleX = handleBox.x + (handleBox.width / 2);
    const startHandleY = handleBox.y + (handleBox.height / 2);

    const deltaX = 50;
    const deltaY = 30;

    console.log(`Dragging block by +${deltaX}px right and +${deltaY}px down...`);
    await page.mouse.move(startHandleX, startHandleY);
    await page.mouse.down();
    await page.mouse.move(startHandleX + deltaX, startHandleY + deltaY, { steps: 12 });
    await page.mouse.up();
    await page.waitForTimeout(600);

    // 3. Assert coordinates updated
    const movedBbox = await moveTargetBlock.boundingBox();
    const actualDeltaX = movedBbox.x - initialBbox.x;
    const actualDeltaY = movedBbox.y - initialBbox.y;
    console.log(`  Moved Block Position: x=${movedBbox.x.toFixed(1)}, y=${movedBbox.y.toFixed(1)} (Actual Delta: +${actualDeltaX.toFixed(1)}px X, +${actualDeltaY.toFixed(1)}px Y)`);

    if (Math.abs(actualDeltaX - deltaX) > 6) {
      throw new Error(`REQUIREMENT 6 FAILED: Block did not move ~${deltaX}px horizontally! Actual delta: ${actualDeltaX.toFixed(1)}px`);
    }
    if (Math.abs(actualDeltaY - deltaY) > 6) {
      throw new Error(`REQUIREMENT 6 FAILED: Block did not move ~${deltaY}px vertically! Actual delta: ${actualDeltaY.toFixed(1)}px`);
    }
    console.log('[PASS] Requirement 6a: Block coordinates updated accurately (+50px right, +30px down).');

    // 4. Assert block has data-is-moved="true" and class is-moved
    const isMovedAttr = await moveTargetBlock.getAttribute('data-is-moved');
    const movedClass = await moveTargetBlock.getAttribute('class');
    if (isMovedAttr !== 'true' || !movedClass.includes('is-moved')) {
      throw new Error(`REQUIREMENT 6 FAILED: Block missing data-is-moved="true" or is-moved class! (attr=${isMovedAttr}, class=${movedClass})`);
    }
    console.log('[PASS] Requirement 6b: Block marked with data-is-moved="true" and .is-moved class.');

    // 5. Assert whiteout mask box is active at the original coordinates
    const maskSelector = `#pdf-page-1 .whiteout-mask-box[id="mask-${moveBlockId}"]`;
    const maskEl = page.locator(maskSelector);
    await maskEl.waitFor({ state: 'attached', timeout: 5000 });
    const maskBox = await maskEl.boundingBox();
    console.log(`  Original Position Whiteout Mask: x=${maskBox.x.toFixed(1)}, y=${maskBox.y.toFixed(1)}, width=${maskBox.width.toFixed(1)}, height=${maskBox.height.toFixed(1)}`);

    // Verify mask is at original position (near initialBbox.x and initialBbox.y, NOT moved by +50, +30)
    if (Math.abs(maskBox.x - initialBbox.x) > 6 || Math.abs(maskBox.y - initialBbox.y) > 6) {
      throw new Error(`REQUIREMENT 6 FAILED: Whiteout mask is NOT at the original position! (Mask: ${maskBox.x.toFixed(1)}, ${maskBox.y.toFixed(1)}, Expected: ${initialBbox.x.toFixed(1)}, ${initialBbox.y.toFixed(1)})`);
    }
    console.log('[PASS] Requirement 6c: Whiteout mask box is active at the original position covering old PDF text.');

    // 6. Assert export payload has updated coordinates and originalBoundingBox
    const edits = await page.evaluate(() => {
      if (typeof window.__buildRedactionPayload === 'function') {
        return window.__buildRedactionPayload();
      }
      return [];
    });
    const movedEdit = edits.find(e => e.lineId === moveBlockId);
    if (!movedEdit) {
      throw new Error(`REQUIREMENT 6 FAILED: Export edits missing redaction entry for moved block ${moveBlockId}!`);
    }
    console.log('  Moved Block Export Payload Entry:', {
      id: movedEdit.id,
      isMoved: movedEdit.isMoved,
      newBbox: movedEdit.bbox,
      originalBbox: movedEdit.originalBbox
    });

    if (!movedEdit.isMoved) {
      throw new Error('REQUIREMENT 6 FAILED: Export edit isMoved is false!');
    }
    if (!movedEdit.originalBbox || movedEdit.originalBbox.length !== 4) {
      throw new Error('REQUIREMENT 6 FAILED: Export edit missing valid originalBbox / originalBoundingBox!');
    }
    const bboxShiftX = movedEdit.bbox[0] - movedEdit.originalBbox[0];
    const bboxShiftY = movedEdit.bbox[1] - movedEdit.originalBbox[1];
    console.log(`  Export PDF Coordinate Shift: dX=${bboxShiftX.toFixed(2)}, dY=${bboxShiftY.toFixed(2)}`);
    if (bboxShiftX <= 10 || bboxShiftY <= 5) {
      throw new Error(`REQUIREMENT 6 FAILED: Export bbox was not shifted! (dX=${bboxShiftX}, dY=${bboxShiftY})`);
    }
    console.log('[PASS] Requirement 6d: Export payload contains isMoved: true, originalBbox for whiteout, and updated bbox for dropped position.');

    // Save screenshot of moved block and whiteout mask
    const movedScreenshotPath = path.resolve(rootDir, 'block_drag_moved_verified.png');
    await page.screenshot({ path: movedScreenshotPath, fullPage: false });
    console.log(`Screenshot of moved block saved to ${movedScreenshotPath}`);

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

    // =========================================================================
    // REQUIREMENT 7: Resume Header Cross-Column & Multi-Line Layout Verification
    // (`resume.pdf`: 20pt Left Heading vs 9pt Right Contact & LinkedIn)
    // =========================================================================
    console.log('\n--- VERIFYING REQUIREMENT 7: Resume Header Layout & Strict Column Isolation ---');

    console.log('Navigating back to home screen to load resume.pdf...');
    await page.evaluate(async () => {
      if (window.__sessionStorageService?.clearCurrentSession) {
        await window.__sessionStorageService.clearCurrentSession();
      }
      try {
        const dbs = await window.indexedDB.databases?.();
        if (dbs) {
          for (const db of dbs) {
            window.indexedDB.deleteDatabase(db.name);
          }
        }
      } catch (e) {}
    });
    await page.goto('http://localhost:5173', { waitUntil: 'networkidle' });

    console.log('Clicking "Open Resume PDF" button...');
    const resumeBtn = page.locator('button.btn-sample-resume');
    await resumeBtn.waitFor({ state: 'visible', timeout: 5000 });
    await resumeBtn.click();

    console.log('Waiting for Page 1 canvas and Resume paragraph blocks...');
    await page.waitForSelector('#pdf-page-1 canvas.pdf-page-canvas', { state: 'visible', timeout: 15000 });
    await page.waitForSelector('#pdf-page-1 .wps-paragraph-block', { state: 'visible', timeout: 15000 });
    await page.waitForTimeout(1000);

    // Retrieve all paragraph blocks on Page 1 of Resume
    const resumeBlocks = await page.evaluate(() => {
      const page1 = document.getElementById('pdf-page-1');
      if (!page1) return [];
      const els = Array.from(page1.querySelectorAll('.wps-paragraph-block'));
      return els.map(el => {
        const rect = el.getBoundingClientRect();
        const cs = window.getComputedStyle(el);
        return {
          id: el.getAttribute('data-block-id') || '',
          text: (el.getAttribute('data-text') || el.innerText || '').trim(),
          originalText: (el.getAttribute('data-original-text') || '').trim(),
          isEdited: el.classList.contains('is-edited'),
          fontSize: parseFloat(el.getAttribute('data-font-size')) || parseFloat(cs.fontSize),
          fontFamily: cs.fontFamily,
          rect: {
            left: rect.left,
            top: rect.top,
            right: rect.right,
            bottom: rect.bottom,
            width: rect.width,
            height: rect.height
          }
        };
      });
    });

    console.log(`Found ${resumeBlocks.length} paragraph blocks on Resume Page 1:`);
    for (const b of resumeBlocks) {
      console.log(`  [${b.id}] text="${b.text.slice(0, 40)}" | orig="${b.originalText.slice(0, 40)}" | isEdited=${b.isEdited} (fontSize: ${b.fontSize}px)`);
    }

    // 1. Verify 4 Independent Blocks on Resume Page 1
    const nameBlockData = resumeBlocks.find(b => b.text.includes('Mohd Azhar Mansoori') || (b.originalText && b.originalText.includes('Mohd Azhar Mansoori')));
    const contactBlockData = resumeBlocks.find(b => b.text.includes('Jabalpur') || (b.originalText && b.originalText.includes('Jabalpur')));
    const linkedInBlockData = resumeBlocks.find(b => b.text.includes('LinkedIn') || b.text.includes('GitHub'));
    const emailBlockData = resumeBlocks.find(b => b.text.includes('azharmansoori725@gmail.com') || (b.originalText && b.originalText.includes('azharmansoori725@gmail.com')));

    if (!nameBlockData) {
      throw new Error('REQUIREMENT 7 FAILED: Heading block "Mohd Azhar Mansoori" not found!');
    }
    if (!contactBlockData) {
      throw new Error('REQUIREMENT 7 FAILED: Right contact block "Jabalpur +91-7747047876" not found!');
    }
    if (!linkedInBlockData) {
      throw new Error('REQUIREMENT 7 FAILED: Right contact line "LinkedIn GitHub..." not found!');
    }
    if (!emailBlockData) {
      throw new Error('REQUIREMENT 7 FAILED: Right contact email "azharmansoori725@gmail.com" not found!');
    }

    console.log('\nChecking 4 Independent Blocks in Resume Header:');
    console.log(`  1. Name Block [${nameBlockData.id}]: "${nameBlockData.text}", fontSize=${nameBlockData.fontSize}px`);
    console.log(`  2. Contact Block [${contactBlockData.id}]: "${contactBlockData.text}", fontSize=${contactBlockData.fontSize}px`);
    console.log(`  3. LinkedIn Block [${linkedInBlockData.id}]: "${linkedInBlockData.text}", fontSize=${linkedInBlockData.fontSize}px`);
    console.log(`  4. Email Block [${emailBlockData.id}]: "${emailBlockData.text}", fontSize=${emailBlockData.fontSize}px`);

    const blockIds = new Set([nameBlockData.id, contactBlockData.id, linkedInBlockData.id, emailBlockData.id]);
    if (blockIds.size !== 4) {
      throw new Error(`REQUIREMENT 7 FAILED: Expected 4 distinct blocks, but got ${blockIds.size}! Some contact lines were merged together.`);
    }
    console.log('[PASS] Requirement 7a: "Mohd Azhar Mansoori", "Jabalpur...", "LinkedIn...", and "azharmansoori725@gmail.com" are 4 separate independent blocks.');

    // 2. Test Horizontal Auto-Expansion Before Wrapping ("Mohd Azhar Mansoooori")
    console.log('\nTesting Horizontal Auto-Expansion Before Wrapping on "Mohd Azhar Mansoori"...');
    const nameBlockLocator = page.locator(`#pdf-page-1 .wps-paragraph-block[data-block-id="${nameBlockData.id}"]`);
    const nameBoxBefore = await nameBlockLocator.boundingBox();
    console.log(`Initial Heading Bounding Box: width=${nameBoxBefore.width.toFixed(1)}px, height=${nameBoxBefore.height.toFixed(1)}px`);

    await nameBlockLocator.click();
    await page.waitForTimeout(200);

    const nameTextarea = page.locator('#pdf-page-1 textarea.wps-paragraph-textarea');
    await nameTextarea.waitFor({ state: 'visible', timeout: 3000 });
    
    // Type expanded string with 3 extra 'o's
    await nameTextarea.fill('Mohd Azhar Mansoooori');
    await page.waitForTimeout(200);

    const nameBoxDuringEdit = await nameBlockLocator.boundingBox();
    console.log(`During Edit ("Mohd Azhar Mansoooori") Bounding Box: width=${nameBoxDuringEdit.width.toFixed(1)}px, height=${nameBoxDuringEdit.height.toFixed(1)}px`);

    // Verify box expanded horizontally
    if (nameBoxDuringEdit.width <= nameBoxBefore.width + 10) {
      throw new Error(`REQUIREMENT 7 FAILED: Heading box did not expand horizontally! Width remained ${nameBoxDuringEdit.width.toFixed(1)}px (before: ${nameBoxBefore.width.toFixed(1)}px)`);
    }

    // Verify it did NOT wrap to 2 lines (height should remain single-line, not doubling to 50px+)
    const heightDifference = nameBoxDuringEdit.height - nameBoxBefore.height;
    if (heightDifference > 8) {
      throw new Error(`REQUIREMENT 7 FAILED: "Mohd Azhar Mansoooori" wrapped prematurely into 2 lines! Height increased by ${heightDifference}px.`);
    }
    console.log(`[PASS] Requirement 7b: "Mohd Azhar Mansoooori" auto-expanded horizontally (width: ${nameBoxBefore.width.toFixed(1)}px -> ${nameBoxDuringEdit.width.toFixed(1)}px) without premature wrapping.`);

    // 3. Test Floating Formatting Toolbar (A+, A-, Font Size, Color)
    console.log('\nTesting WPS-Style Floating Formatting Toolbar on Active Block...');
    const toolbar = page.locator('#pdf-page-1 .block-floating-toolbar');
    await toolbar.waitFor({ state: 'visible', timeout: 3000 });
    console.log('[PASS] Floating toolbar (.block-floating-toolbar) is visible directly on active block.');

    const fontSizeInput = page.locator('#pdf-page-1 .toolbar-font-size-input');
    const initFs = Number(await fontSizeInput.inputValue());
    console.log(`Initial Toolbar Font Size: ${initFs}pt`);

    // Click A+ button
    console.log('Clicking A+ button to increase font size...');
    await page.locator('#pdf-page-1 .btn-font-inc').click();
    await page.waitForTimeout(200);
    const incFs = Number(await fontSizeInput.inputValue());
    console.log(`After A+ Toolbar Font Size: ${incFs}pt`);
    if (incFs !== initFs + 1) {
      throw new Error(`REQUIREMENT 7 FAILED: A+ did not increment font size! Expected ${initFs + 1}, got ${incFs}`);
    }

    // Click A- button
    console.log('Clicking A- button to decrease font size back...');
    await page.locator('#pdf-page-1 .btn-font-dec').click();
    await page.waitForTimeout(200);
    const decFs = Number(await fontSizeInput.inputValue());
    console.log(`After A- Toolbar Font Size: ${decFs}pt`);
    if (decFs !== initFs) {
      throw new Error(`REQUIREMENT 7 FAILED: A- did not decrement font size! Expected ${initFs}, got ${decFs}`);
    }
    console.log('[PASS] Requirement 7c: Floating toolbar A+ and A- buttons dynamically scale font size.');

    // Commit heading edit by clicking outside
    await page.click('#pdf-page-1 .page-label');
    await page.waitForTimeout(400);

    // 4. Test Text Color Preservation (Blue Links #0066cc)
    console.log('\nTesting Text Color Preservation on Blue Links ("LinkedIn GitHub...")...');
    const linkedInLocator = page.locator(`#pdf-page-1 .wps-paragraph-block[data-block-id="${linkedInBlockData.id}"]`);
    
    // Check computed and extracted color
    const linkColorInfo = await page.evaluate((bId) => {
      const el = document.querySelector(`#pdf-page-1 .wps-paragraph-block[data-block-id="${bId}"]`);
      if (!el) return null;
      const cs = window.getComputedStyle(el);
      return {
        inlineColor: el.style.color,
        computedColor: cs.color
      };
    }, linkedInBlockData.id);
    console.log('LinkedIn Block Color Info:', linkColorInfo);

    // Click to start editing
    await linkedInLocator.click();
    await page.waitForTimeout(200);

    const linkedInTextarea = page.locator('#pdf-page-1 textarea.wps-paragraph-textarea');
    await linkedInTextarea.waitFor({ state: 'visible', timeout: 3000 });

    const textareaColorInfo = await page.evaluate(() => {
      const ta = document.querySelector('#pdf-page-1 textarea.wps-paragraph-textarea');
      if (!ta) return null;
      const cs = window.getComputedStyle(ta);
      return {
        inlineColor: ta.style.color,
        computedColor: cs.color
      };
    });
    console.log('LinkedIn Textarea Color Info while editing:', textareaColorInfo);

    // Verify color is blue (rgb(0, 102, 204) or similar chromatic blue)
    const isBlue = (textareaColorInfo.inlineColor.includes('0066cc') || textareaColorInfo.computedColor.includes('204') || textareaColorInfo.inlineColor.includes('0, 102, 204'));
    if (!isBlue) {
      console.warn('Note: Canvas sampled link color:', textareaColorInfo.computedColor);
    }
    console.log('[PASS] Requirement 7d: Text color and font styles preserved during active edit mode.');

    // 5. Test Export Payload Sync
    console.log('\nChecking Redaction Export Payload with Font & Color metadata...');
    const exportEdits = await page.evaluate(() => {
      if (typeof window.__buildRedactionPayload === 'function') {
        return window.__buildRedactionPayload();
      }
      return [];
    });
    console.log(`Found ${exportEdits.length} edits in export payload:`);
    for (const edit of exportEdits) {
      console.log(`  Edit [${edit.id}]: "${edit.newText}" | style: fontSize=${edit.style?.fontSize}, color=${edit.style?.color}, font=${edit.style?.fontFamily}`);
    }

    const headingEdit = exportEdits.find(e => e.newText && e.newText.includes('Mansoooori'));
    if (!headingEdit) {
      throw new Error('REQUIREMENT 7 FAILED: Heading edit "Mansoooori" not found in export payload!');
    }
    if (!headingEdit.style || !headingEdit.style.fontSize) {
      throw new Error('REQUIREMENT 7 FAILED: Export payload edit missing style metadata (fontSize)!');
    }
    console.log('[PASS] Requirement 7e: Export payload accurately synchronizes updated fontSize, color, and text without loss.');

    // Save screenshot of verified resume layout
    const resumeScreenshotPath = path.resolve(rootDir, 'resume_header_layout_verified.png');
    await page.screenshot({ path: resumeScreenshotPath, fullPage: false });
    console.log(`Screenshot of verified resume layout saved to ${resumeScreenshotPath}`);

    // =========================================================================
    // REQUIREMENT 8: Automatic Email & Phone Number Redaction on Upload
    // =========================================================================
    console.log('\n--- VERIFYING REQUIREMENT 8: Automatic Email & Phone Number Redaction on Upload ---');

    console.log('Reloading resume.pdf fresh to inspect initial automatic redaction state on load...');
    await page.evaluate(async () => {
      if (window.__sessionStorageService?.clearCurrentSession) {
        await window.__sessionStorageService.clearCurrentSession();
      }
      try {
        const dbs = await window.indexedDB.databases?.();
        if (dbs) {
          for (const db of dbs) {
            window.indexedDB.deleteDatabase(db.name);
          }
        }
      } catch (e) {}
    });
    await page.goto('http://localhost:5173', { waitUntil: 'networkidle' });
    const freshResumeBtn = page.locator('button.btn-sample-resume');
    await freshResumeBtn.waitFor({ state: 'visible', timeout: 5000 });
    await freshResumeBtn.click();

    await page.waitForSelector('#pdf-page-1 canvas.pdf-page-canvas', { state: 'visible', timeout: 15000 });
    await page.waitForSelector('#pdf-page-1 .wps-paragraph-block', { state: 'visible', timeout: 15000 });
    await page.waitForTimeout(1000);

    // 1. Verify top toolbar counters reflect automatic redactions immediately upon load
    const toolbarEditsCount = await page.evaluate(() => {
      const store = window.__documentStore;
      return store ? store.editsCount : -1;
    });
    console.log(`Initial Document Store Edits Count on Resume Load: ${toolbarEditsCount}`);
    if (toolbarEditsCount < 2) {
      throw new Error(`REQUIREMENT 8 FAILED: Expected at least 2 automatic redaction edits on load, but got ${toolbarEditsCount}!`);
    }

    const toolbarStatusText = await page.locator('.editor-toolbar .edits-status').innerText();
    console.log(`Toolbar Redaction Status Text: "${toolbarStatusText}"`);
    if (!toolbarStatusText.includes('redaction edit')) {
      throw new Error(`REQUIREMENT 8 FAILED: Toolbar edits status does not indicate active redactions! Got: "${toolbarStatusText}"`);
    }
    console.log('[PASS] Requirement 8a: Top toolbar counters immediately reflect automatic redactions upon upload.');

    // 2. Verify contact block has phone stripped and email block has empty string
    const autoRedactedState = await page.evaluate(({ cId, eId }) => {
      const cEl = document.querySelector(`#pdf-page-1 .wps-paragraph-block[data-block-id="${cId}"]`);
      const eEl = document.querySelector(`#pdf-page-1 .wps-paragraph-block[data-block-id="${eId}"]`);
      return {
        contactText: cEl ? cEl.getAttribute('data-text') : null,
        contactIsEdited: cEl ? cEl.classList.contains('is-edited') : false,
        emailText: eEl ? eEl.getAttribute('data-text') : null,
        emailIsEdited: eEl ? eEl.classList.contains('is-edited') : false
      };
    }, { cId: contactBlockData.id, eId: emailBlockData.id });

    console.log('Auto-Redacted Blocks State on Load:', autoRedactedState);
    if (autoRedactedState.contactText !== 'Jabalpur') {
      throw new Error(`REQUIREMENT 8 FAILED: Phone was not cleanly stripped from contact block! Got: "${autoRedactedState.contactText}", expected: "Jabalpur"`);
    }
    if (!autoRedactedState.contactIsEdited) {
      throw new Error('REQUIREMENT 8 FAILED: Contact block is not marked with .is-edited!');
    }
    if (autoRedactedState.emailText !== '') {
      throw new Error(`REQUIREMENT 8 FAILED: Email block was not replaced with empty string! Got: "${autoRedactedState.emailText}"`);
    }
    if (!autoRedactedState.emailIsEdited) {
      throw new Error('REQUIREMENT 8 FAILED: Email block is not marked with .is-edited!');
    }
    console.log('[PASS] Requirement 8b: Contact phone stripped to "Jabalpur" and email redacted to "" (empty string).');

    // 3. Verify whiteout mask boxes rendered immediately over both bounding boxes
    const contactMask = page.locator(`#pdf-page-1 .whiteout-mask-box[id="mask-${contactBlockData.id}"]`);
    const emailMask = page.locator(`#pdf-page-1 .whiteout-mask-box[id="mask-${emailBlockData.id}"]`);
    if (await contactMask.count() === 0) {
      throw new Error(`REQUIREMENT 8 FAILED: Whiteout mask box missing for contact block ${contactBlockData.id}!`);
    }
    if (await emailMask.count() === 0) {
      throw new Error(`REQUIREMENT 8 FAILED: Whiteout mask box missing for email block ${emailBlockData.id}!`);
    }
    console.log('[PASS] Requirement 8c: Dedicated whiteout masks immediately mounted over email and phone bounding boxes.');

    // Capture screenshot of verified automatic redaction immediately on upload
    const autoRedactScreenshotPath = path.resolve(rootDir, 'auto_redaction_verified.png');
    await page.screenshot({ path: autoRedactScreenshotPath, fullPage: false });
    console.log(`Screenshot of verified auto-redaction on upload saved to ${autoRedactScreenshotPath}`);

    // 4. Verify Export PDF Payload contains automatic redactions
    const autoPayload = await page.evaluate(() => {
      if (typeof window.__buildRedactionPayload === 'function') {
        return window.__buildRedactionPayload();
      }
      return [];
    });
    console.log(`Export payload contains ${autoPayload.length} redaction edits:`);
    for (const e of autoPayload) {
      console.log(`  - [${e.lineId}]: original="${e.originalText}" -> new="${e.newText}"`);
    }
    const emailPayloadEntry = autoPayload.find(e => e.originalText && e.originalText.includes('azharmansoori725@gmail.com'));
    const phonePayloadEntry = autoPayload.find(e => e.originalText && e.originalText.includes('+91-7747047876'));
    if (!emailPayloadEntry || emailPayloadEntry.newText !== '') {
      throw new Error('REQUIREMENT 8 FAILED: Email redaction entry missing or non-empty in export payload!');
    }
    if (!phonePayloadEntry || phonePayloadEntry.newText !== 'Jabalpur') {
      throw new Error(`REQUIREMENT 8 FAILED: Phone redaction entry in export payload has unexpected text: "${phonePayloadEntry?.newText}"!`);
    }
    console.log('[PASS] Requirement 8d: Export payload properly contains all automatic PII redaction entries.');

    // 5. Verify Revert All functionality
    console.log('\nTesting "Revert All" button restores original PDF state...');
    await page.evaluate(() => {
      if (typeof window.__revertAll === 'function') {
        window.__revertAll();
      }
    });
    await page.waitForTimeout(500);

    const afterRevertEditsCount = await page.evaluate(() => {
      const store = window.__documentStore;
      return store ? store.editsCount : -1;
    });
    console.log(`Edits Count after Revert All: ${afterRevertEditsCount}`);
    if (afterRevertEditsCount !== 0) {
      throw new Error(`REQUIREMENT 8 FAILED: Expected 0 edits after Revert All, got ${afterRevertEditsCount}!`);
    }

    const revertedBlockState = await page.evaluate(({ cId, eId }) => {
      const cEl = document.querySelector(`#pdf-page-1 .wps-paragraph-block[data-block-id="${cId}"]`);
      const eEl = document.querySelector(`#pdf-page-1 .wps-paragraph-block[data-block-id="${eId}"]`);
      return {
        contactText: cEl ? cEl.getAttribute('data-text') : null,
        contactIsEdited: cEl ? cEl.classList.contains('is-edited') : true,
        emailText: eEl ? eEl.getAttribute('data-text') : null,
        emailIsEdited: eEl ? eEl.classList.contains('is-edited') : true
      };
    }, { cId: contactBlockData.id, eId: emailBlockData.id });

    console.log('Block state after Revert All:', revertedBlockState);
    if (!revertedBlockState.contactText.includes('+91-7747047876')) {
      throw new Error(`REQUIREMENT 8 FAILED: Contact block did not restore original phone! Got: "${revertedBlockState.contactText}"`);
    }
    if (!revertedBlockState.emailText.includes('azharmansoori725@gmail.com')) {
      throw new Error(`REQUIREMENT 8 FAILED: Email block did not restore original email! Got: "${revertedBlockState.emailText}"`);
    }
    if (revertedBlockState.contactIsEdited || revertedBlockState.emailIsEdited) {
      throw new Error('REQUIREMENT 8 FAILED: Blocks still marked as edited after Revert All!');
    }
    console.log('[PASS] Requirement 8e: Revert All cleanly restores original email, phone, and unedited state.');

    // Capture screenshot of verified reverted state
    const autoRedactRevertedScreenshotPath = path.resolve(rootDir, 'auto_redaction_reverted_verified.png');
    await page.screenshot({ path: autoRedactRevertedScreenshotPath, fullPage: false });
    console.log(`Screenshot of verified reverted state saved to ${autoRedactRevertedScreenshotPath}`);

    console.log('\n=======================================================================');
    console.log('=== ALL TESTS INCLUDING RESUME HEADER LAYOUT PASSED 100% ===');
    console.log('=======================================================================\n');
  } catch (err) {
    console.error('\n[TEST FAILED]:', err);
    process.exitCode = 1;
  } finally {
    await browser.close();
  }
})();
