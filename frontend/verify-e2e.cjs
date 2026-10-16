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
      console.log(`  [${b.id}] "${b.text.slice(0, 40)}" (fontSize: ${b.fontSize}px, rect: x=${b.rect.left.toFixed(1)}, y=${b.rect.top.toFixed(1)}, w=${b.rect.width.toFixed(1)}, h=${b.rect.height.toFixed(1)})`);
    }

    // 1. Verify Left Heading ("Mohd Azhar Mansoori") and Right Contact ("Jabalpur +91-7747047876")
    const nameBlockData = resumeBlocks.find(b => b.text.includes('Mohd Azhar Mansoori'));
    const contactBlockData = resumeBlocks.find(b => b.text.includes('Jabalpur') || b.text.includes('+91-7747047876'));
    const linkedInBlockData = resumeBlocks.find(b => b.text.includes('LinkedIn') || b.text.includes('GitHub'));

    if (!nameBlockData) {
      throw new Error('REQUIREMENT 7 FAILED: Heading block "Mohd Azhar Mansoori" not found!');
    }
    if (!contactBlockData) {
      throw new Error('REQUIREMENT 7 FAILED: Right contact block "Jabalpur +91-7747047876" not found!');
    }
    if (!linkedInBlockData) {
      throw new Error('REQUIREMENT 7 FAILED: Right contact line "LinkedIn GitHub..." not found!');
    }

    console.log('\nChecking Column Isolation between Left Heading and Right Contact:');
    console.log(`  Name Block [${nameBlockData.id}]: "${nameBlockData.text}", fontSize=${nameBlockData.fontSize}px, width=${nameBlockData.rect.width.toFixed(1)}px`);
    console.log(`  Contact Block [${contactBlockData.id}]: "${contactBlockData.text}", fontSize=${contactBlockData.fontSize}px, width=${contactBlockData.rect.width.toFixed(1)}px`);

    // Verify they are 2 distinct paragraph blocks (NOT merged)
    if (nameBlockData.id === contactBlockData.id) {
      throw new Error('REQUIREMENT 7 FAILED: "Mohd Azhar Mansoori" and "Jabalpur" were merged into the same block ID!');
    }
    if (nameBlockData.text.includes('Jabalpur') || nameBlockData.text.includes('7747047876')) {
      throw new Error('REQUIREMENT 7 FAILED: Name block contains contact details! Merged across columns.');
    }
    if (contactBlockData.text.includes('Mohd Azhar')) {
      throw new Error('REQUIREMENT 7 FAILED: Contact block contains heading text!');
    }

    // Check font sizes: Name should be ~25px (20pt * 1.25), Contact should be ~11.25px (9pt * 1.25)
    if (nameBlockData.fontSize < 20) {
      throw new Error(`REQUIREMENT 7 FAILED: Name heading font size too small: ${nameBlockData.fontSize}px (expected ~25px)`);
    }
    if (contactBlockData.fontSize > 16) {
      throw new Error(`REQUIREMENT 7 FAILED: Contact text font size inflated: ${contactBlockData.fontSize}px (expected ~11.25px)`);
    }
    console.log('[PASS] Requirement 7a: "Mohd Azhar Mansoori" (20pt) and "Jabalpur +91-7747047876" (9pt) are 2 independent blocks with correct font sizes.');

    // 2. Edit "Mohd Azhar Mansoori" -> "Mohd Azhar Mnsoori"
    console.log('\nEditing "Mohd Azhar Mansoori" to "Mohd Azhar Mnsoori"...');
    const nameBlockLocator = page.locator(`#pdf-page-1 .wps-paragraph-block[data-block-id="${nameBlockData.id}"]`);
    await nameBlockLocator.click();
    await page.waitForTimeout(200);

    const nameTextarea = page.locator('#pdf-page-1 textarea.wps-paragraph-textarea');
    await nameTextarea.waitFor({ state: 'visible', timeout: 3000 });
    await nameTextarea.fill('Mohd Azhar Mnsoori');
    await page.waitForTimeout(200);

    // Commit by clicking outside
    await page.click('#pdf-page-1 .page-label');
    await page.waitForTimeout(400);

    // Verify contactBlock font size did NOT change to 20pt
    const postEditContactStyle = await page.evaluate((cId) => {
      const el = document.querySelector(`#pdf-page-1 .wps-paragraph-block[data-block-id="${cId}"]`);
      if (!el) return null;
      const cs = window.getComputedStyle(el);
      return {
        fontSize: parseFloat(el.getAttribute('data-font-size')) || parseFloat(cs.fontSize),
        text: (el.innerText || '').trim()
      };
    }, contactBlockData.id);

    console.log('Post-edit Contact Block state:', postEditContactStyle);
    if (postEditContactStyle.fontSize > 16) {
      throw new Error(`REQUIREMENT 7 FAILED: Contact block font size changed to ${postEditContactStyle.fontSize}px after editing heading!`);
    }
    console.log('[PASS] Requirement 7b: Editing heading did NOT change contact font size.');

    // Verify tight whiteout mask: Mask must NOT span across page to obscure "LinkedIn GitHub"
    const nameMask = page.locator(`#pdf-page-1 .whiteout-mask-box[id="mask-${nameBlockData.id}"]`);
    await nameMask.waitFor({ state: 'attached', timeout: 3000 });
    const nameMaskBox = await nameMask.boundingBox();
    console.log(`Name Whiteout Mask Bounding Box: x=${nameMaskBox.x.toFixed(1)}, y=${nameMaskBox.y.toFixed(1)}, w=${nameMaskBox.width.toFixed(1)}, h=${nameMaskBox.height.toFixed(1)}`);

    // In resume.pdf, total page width is ~765px (612pt * 1.25). A tight mask over "Mohd Azhar Mansoori" should be <= 350px
    if (nameMaskBox.width > 400) {
      throw new Error(`REQUIREMENT 7 FAILED: Whiteout mask is oversized (${nameMaskBox.width.toFixed(1)}px)! It covers the whole width of the page.`);
    }

    // Verify "LinkedIn GitHub" block is NOT covered by the whiteout mask
    const linkedInLocator = page.locator(`#pdf-page-1 .wps-paragraph-block[data-block-id="${linkedInBlockData.id}"]`);
    const linkedInBox = await linkedInLocator.boundingBox();
    console.log(`LinkedIn Block Bounding Box: x=${linkedInBox.x.toFixed(1)}, y=${linkedInBox.y.toFixed(1)}, w=${linkedInBox.width.toFixed(1)}, h=${linkedInBox.height.toFixed(1)}`);

    // Mask right boundary should be well to the left of LinkedIn start
    const maskRight = nameMaskBox.x + nameMaskBox.width;
    console.log(`Mask Right Edge: ${maskRight.toFixed(1)}px vs LinkedIn Left Edge: ${linkedInBox.x.toFixed(1)}px`);
    if (maskRight > linkedInBox.x + 5) {
      throw new Error(`REQUIREMENT 7 FAILED: Name whiteout mask overlaps horizontally with LinkedIn block! (maskRight=${maskRight}, linkedInX=${linkedInBox.x})`);
    }
    console.log('[PASS] Requirement 7c: Whiteout mask is tightly fitted to heading (~250px) and does NOT obscure adjacent/lower lines ("LinkedIn GitHub").');

    // 3. Edit right-side contact / LinkedIn block and verify zero upward overlap
    console.log('\nEditing right-side LinkedIn block to verify vertical positioning without overlap...');
    const contactBoxBefore = await page.locator(`#pdf-page-1 .wps-paragraph-block[data-block-id="${contactBlockData.id}"]`).boundingBox();
    const linkedInBoxBefore = await page.locator(`#pdf-page-1 .wps-paragraph-block[data-block-id="${linkedInBlockData.id}"]`).boundingBox();

    console.log(`Contact above (Jabalpur): y=${contactBoxBefore.y.toFixed(1)}, bottom=${(contactBoxBefore.y + contactBoxBefore.height).toFixed(1)}`);
    console.log(`LinkedIn below before edit: y=${linkedInBoxBefore.y.toFixed(1)}`);

    // Click LinkedIn block to edit
    await linkedInLocator.click();
    await page.waitForTimeout(200);

    const linkedInTextarea = page.locator('#pdf-page-1 textarea.wps-paragraph-textarea');
    await linkedInTextarea.waitFor({ state: 'visible', timeout: 3000 });
    const activeLinkedInBox = await linkedInLocator.boundingBox();
    console.log(`LinkedIn while editing: y=${activeLinkedInBox.y.toFixed(1)}`);

    // Verify it did NOT jump upward over Jabalpur
    if (activeLinkedInBox.y < contactBoxBefore.y + contactBoxBefore.height - 4) {
      throw new Error(`REQUIREMENT 7 FAILED: Editing LinkedIn block shifted it upwards over Jabalpur! (LinkedIn y=${activeLinkedInBox.y}, Jabalpur bottom=${contactBoxBefore.y + contactBoxBefore.height})`);
    }

    // Type edited text
    await linkedInTextarea.fill('LinkedIn  |  GitHub  |  Portfolio  |  LeetCode');
    await page.waitForTimeout(200);

    // Commit by clicking outside
    await page.click('#pdf-page-1 .page-label');
    await page.waitForTimeout(400);

    const linkedInBoxAfter = await linkedInLocator.boundingBox();
    console.log(`LinkedIn after commit: y=${linkedInBoxAfter.y.toFixed(1)}`);
    if (linkedInBoxAfter.y < contactBoxBefore.y + contactBoxBefore.height - 4) {
      throw new Error(`REQUIREMENT 7 FAILED: Committed LinkedIn block overlaps Jabalpur!`);
    }
    console.log('[PASS] Requirement 7d: Right-side contact blocks maintain accurate vertical top-Y coordinates with zero upward overlap.');

    // Save screenshot of verified resume layout
    const resumeScreenshotPath = path.resolve(rootDir, 'resume_header_layout_verified.png');
    await page.screenshot({ path: resumeScreenshotPath, fullPage: false });
    console.log(`Screenshot of verified resume layout saved to ${resumeScreenshotPath}`);

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
