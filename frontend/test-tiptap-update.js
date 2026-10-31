import { convertTipTapToDocumentModel } from './src/utils/tipTapToJson.js';

function extractBlockText(block) {
  if (!block) return '';
  if (Array.isArray(block.runs) && block.runs.length > 0) {
    return block.runs.map(r => r.text || '').join('');
  }
  if (block.type === 'TABLE' && Array.isArray(block.cells)) {
    return block.cells
      .map(row => (Array.isArray(row) ? row.map(cell => extractBlockText(cell)).join('\t') : ''))
      .join('\n');
  }
  return typeof block.text === 'string' ? block.text : '';
}

function extractBlockStyle(block, defaultStyle = {}) {
  const firstRun = block?.runs?.find(r => r && r.style) || null;
  const runStyle = firstRun?.style || {};
  return {
    fontSize: runStyle.fontSize || block?.fontSize || block?.customFontSize || defaultStyle.fontSize || 12,
    fontFamily: runStyle.fontFamily || block?.fontFamily || block?.fontName || defaultStyle.fontFamily || 'Helvetica',
    bold: runStyle.bold !== undefined ? runStyle.bold : (block?.bold !== undefined ? block.bold : Boolean(defaultStyle.bold)),
    italic: runStyle.italic !== undefined ? runStyle.italic : (block?.italic !== undefined ? block.italic : Boolean(defaultStyle.italic)),
    color: runStyle.color || block?.color || block?.customColor || defaultStyle.color || '#000000'
  };
}

function nodeHasMarks(node) {
  if (!node) return false;
  if (Array.isArray(node.content)) {
    return node.content.some(child => {
      if (Array.isArray(child.marks) && child.marks.length > 0) return true;
      if (Array.isArray(child.content)) return nodeHasMarks(child);
      return false;
    });
  }
  return false;
}

class MockDocumentStore {
  constructor(initialModel) {
    this.documentModel = initialModel;
    this.edits = [];
    this.blockPatches = new Map();
    this.isCommittedSave = true;
  }

  getPatchKey(pageIndex, blockId) {
    return `${pageIndex}_${blockId}`;
  }

  setPatch(pageIndex, blockId, patchData) {
    const key = this.getPatchKey(pageIndex, blockId);
    const existing = this.blockPatches.get(key) || {};
    const updatedPatch = {
      ...existing,
      ...patchData,
      pageIndex,
      blockId,
      timestamp: Date.now()
    };
    this.blockPatches.set(key, updatedPatch);
    this.blockPatches.set(`${pageIndex}${blockId}`, updatedPatch);
  }

  deletePatch(pageIndex, blockId) {
    const key = this.getPatchKey(pageIndex, blockId);
    this.blockPatches.delete(key);
    this.blockPatches.delete(`${pageIndex}${blockId}`);
  }

  recordEdit(edit) {
    if (!edit || !edit.id) return;
    const index = this.edits.findIndex(e => e.id === edit.id);
    if (edit.newText === edit.originalText && (!edit.style || !edit.isStyleModified)) {
      if (index !== -1) this.edits.splice(index, 1);
      return;
    }
    const payload = { ...edit };
    if (index !== -1) {
      this.edits[index] = payload;
    } else {
      this.edits.push(payload);
    }
  }

  removeEdit(id) {
    const index = this.edits.findIndex(e => e.id === id);
    if (index !== -1) this.edits.splice(index, 1);
  }

  updateFromTipTap(tipTapJson) {
    if (!tipTapJson) return null;

    const existingBlocksById = new Map();
    const existingBlocksByIndex = new Map();

    if (this.documentModel?.pages) {
      for (const page of this.documentModel.pages) {
        const pageNum = page.pageNumber || 1;
        const blocks = Array.isArray(page.blocks) ? page.blocks : [];
        blocks.forEach((blk, idx) => {
          if (blk.id) {
            existingBlocksById.set(blk.id, { block: blk, pageNumber: pageNum, index: idx });
          }
          existingBlocksByIndex.set(`${pageNum}_${idx}`, { block: blk, pageNumber: pageNum, index: idx });
        });
      }
    }

    const tipTapNodeMap = new Map();
    if (Array.isArray(tipTapJson.content)) {
      let currPg = 1;
      let blkIdx = 0;
      for (const node of tipTapJson.content) {
        if (!node) continue;
        if (node.type === 'pageBreak') {
          currPg = parseInt(node.attrs?.pageNumber, 10) || (currPg + 1);
          blkIdx = 0;
          continue;
        }
        if (node.attrs?.pageNumber) {
          const p = parseInt(node.attrs.pageNumber, 10);
          if (p && p !== currPg) {
            currPg = p;
            blkIdx = 0;
          }
        }
        if (node.attrs?.blockId) {
          tipTapNodeMap.set(node.attrs.blockId, node);
        }
        tipTapNodeMap.set(`${currPg}_${blkIdx}`, node);
        blkIdx++;
      }
    }

    const parsedModel = convertTipTapToDocumentModel(tipTapJson, this.documentModel);
    if (!parsedModel || !Array.isArray(parsedModel.pages)) {
      return null;
    }

    let hasAnyChanges = false;
    const seenBlockIds = new Set();

    for (const page of parsedModel.pages) {
      const pageNum = page.pageNumber || 1;
      const pageBlocks = Array.isArray(page.blocks) ? page.blocks : [];

      for (let idx = 0; idx < pageBlocks.length; idx++) {
        const block = pageBlocks[idx];
        if (!block) continue;

        let match = block.id ? existingBlocksById.get(block.id) : null;
        if (!match) {
          match = existingBlocksByIndex.get(`${pageNum}_${idx}`);
          if (match && match.block) {
            block.id = match.block.id;
          }
        }

        const existingBlock = match ? match.block : null;
        if (existingBlock) {
          block.id = existingBlock.id;
          seenBlockIds.add(block.id);

          const hasParsedBbox = Array.isArray(block.bbox) && block.bbox.length === 4 &&
            (block.bbox[0] !== 0 || block.bbox[1] !== 0 || block.bbox[2] !== 0 || block.bbox[3] !== 0);

          if (!hasParsedBbox && existingBlock.bbox) {
            block.bbox = Array.isArray(existingBlock.bbox) ? [...existingBlock.bbox] : existingBlock.bbox;
          }

          block.originalBbox = existingBlock.originalBbox || (existingBlock.bbox ? [...existingBlock.bbox] : block.bbox);

          if (existingBlock.x !== undefined) block.x = existingBlock.x;
          else if (Array.isArray(block.bbox)) block.x = block.bbox[0];

          if (existingBlock.y !== undefined) block.y = existingBlock.y;
          else if (Array.isArray(block.bbox)) block.y = block.bbox[1];

          if (existingBlock.width !== undefined) block.width = existingBlock.width;
          else if (Array.isArray(block.bbox)) block.width = block.bbox[2];

          if (existingBlock.height !== undefined) block.height = existingBlock.height;
          else if (Array.isArray(block.bbox)) block.height = block.bbox[3];

          if (existingBlock.originalX !== undefined) block.originalX = existingBlock.originalX;
          if (existingBlock.originalY !== undefined) block.originalY = existingBlock.originalY;
          if (existingBlock.originalWidth !== undefined) block.originalWidth = existingBlock.originalWidth;
          if (existingBlock.originalHeight !== undefined) block.originalHeight = existingBlock.originalHeight;

          if (existingBlock.originalPdfX !== undefined) block.originalPdfX = existingBlock.originalPdfX;
          if (existingBlock.originalPdfY !== undefined) block.originalPdfY = existingBlock.originalPdfY;
          if (existingBlock.originalPdfWidth !== undefined) block.originalPdfWidth = existingBlock.originalPdfWidth;
          if (existingBlock.originalPdfHeight !== undefined) block.originalPdfHeight = existingBlock.originalPdfHeight;

          if (existingBlock.customX !== undefined) block.customX = existingBlock.customX;
          if (existingBlock.customY !== undefined) block.customY = existingBlock.customY;
          if (existingBlock.customWidth !== undefined) block.customWidth = existingBlock.customWidth;
          if (existingBlock.customFontSize !== undefined) block.customFontSize = existingBlock.customFontSize;
          if (existingBlock.customColor !== undefined) block.customColor = existingBlock.customColor;

          if (existingBlock.lines && !block.lines) block.lines = existingBlock.lines;

          block.originalText = existingBlock.originalText !== undefined
            ? existingBlock.originalText
            : extractBlockText(existingBlock);
        } else {
          if (block.id) {
            seenBlockIds.add(block.id);
          }
          if (Array.isArray(block.bbox)) {
            if (block.x === undefined) block.x = block.bbox[0];
            if (block.y === undefined) block.y = block.bbox[1];
            if (block.width === undefined) block.width = block.bbox[2];
            if (block.height === undefined) block.height = block.bbox[3];
          }
          block.originalBbox = block.bbox ? [...block.bbox] : [0, 0, 0, 0];
          block.originalText = '';
        }

        if (!Array.isArray(block.bbox) || block.bbox.length !== 4) {
          block.bbox = [block.x || 0, block.y || 0, block.width || 0, block.height || 0];
        }
        if (block.x === undefined) block.x = block.bbox[0];
        if (block.y === undefined) block.y = block.bbox[1];
        if (block.width === undefined) block.width = block.bbox[2];
        if (block.height === undefined) block.height = block.bbox[3];

        const newText = extractBlockText(block);
        block.text = newText;
        const origText = block.originalText !== undefined ? block.originalText : '';

        const defaultStyle = existingBlock ? extractBlockStyle(existingBlock) : {};
        const rawTipTapNode = (block.id ? tipTapNodeMap.get(block.id) : null) || tipTapNodeMap.get(`${pageNum}_${idx}`);
        const hasMarks = nodeHasMarks(rawTipTapNode);

        let blockStyle;
        let hasStyleDiff = false;
        if (!hasMarks && existingBlock) {
          blockStyle = { ...defaultStyle };
          hasStyleDiff = false;
        } else {
          blockStyle = extractBlockStyle(block, defaultStyle);
          hasStyleDiff = Boolean(
            (blockStyle.fontSize && defaultStyle.fontSize && Math.abs(blockStyle.fontSize - defaultStyle.fontSize) > 0.1) ||
            (blockStyle.fontFamily && defaultStyle.fontFamily && blockStyle.fontFamily !== defaultStyle.fontFamily) ||
            (blockStyle.bold !== undefined && defaultStyle.bold !== undefined && blockStyle.bold !== defaultStyle.bold) ||
            (blockStyle.italic !== undefined && defaultStyle.italic !== undefined && blockStyle.italic !== defaultStyle.italic) ||
            (blockStyle.color && defaultStyle.color && blockStyle.color.toLowerCase() !== defaultStyle.color.toLowerCase())
          );
        }

        const hasTextDiff = newText !== origText;
        const isModified = hasTextDiff || hasStyleDiff;

        if (isModified) {
          block.isEdited = true;
          block.isDirty = true;
          hasAnyChanges = true;

          this.setPatch(pageNum, block.id, {
            originalText: origText,
            newText: newText,
            fontSize: blockStyle.fontSize,
            fontFamily: blockStyle.fontFamily,
            bold: blockStyle.bold,
            italic: blockStyle.italic,
            color: blockStyle.color,
            customFontSize: blockStyle.fontSize,
            customColor: blockStyle.color,
            isEdited: true,
            isDirty: true
          });

          const effBbox = Array.isArray(block.bbox) && block.bbox.length === 4
            ? block.bbox
            : [block.x || 0, block.y || 0, block.width || 0, block.height || 0];

          this.recordEdit({
            id: `redact-block-${block.id}`,
            lineId: block.id,
            pageNumber: pageNum,
            bbox: effBbox,
            originalBbox: block.originalBbox || effBbox,
            originalText: origText,
            newText: newText,
            style: {
              fontSize: blockStyle.fontSize || 12,
              fontFamily: blockStyle.fontFamily || 'Helvetica',
              bold: Boolean(blockStyle.bold),
              italic: Boolean(blockStyle.italic),
              color: blockStyle.color || '#000000'
            },
            isStyleModified: Boolean(hasStyleDiff),
            transparentBackground: false
          });
        } else {
          block.isEdited = false;
          block.isDirty = false;
          this.deletePatch(pageNum, block.id);
          this.removeEdit(`redact-block-${block.id}`);
        }
      }
    }

    for (const [id, info] of existingBlocksById.entries()) {
      if (!seenBlockIds.has(id)) {
        const origBlock = info.block;
        const origText = origBlock.originalText !== undefined ? origBlock.originalText : extractBlockText(origBlock);
        if (origText && origText.trim().length > 0) {
          hasAnyChanges = true;
          const effBbox = Array.isArray(origBlock.bbox) && origBlock.bbox.length === 4
            ? origBlock.bbox
            : [origBlock.x || 0, origBlock.y || 0, origBlock.width || 0, origBlock.height || 0];

          this.recordEdit({
            id: `redact-block-${id}`,
            lineId: id,
            pageNumber: info.pageNumber,
            bbox: effBbox,
            originalBbox: origBlock.originalBbox || effBbox,
            originalText: origText,
            newText: '',
            style: extractBlockStyle(origBlock),
            isStyleModified: false,
            transparentBackground: false
          });

          this.setPatch(info.pageNumber, id, {
            originalText: origText,
            newText: '',
            isEdited: true,
            isDirty: true
          });
        }
      }
    }

    if (hasAnyChanges) {
      this.isCommittedSave = false;
    }

    if (this.documentModel) {
      if (this.documentModel.pageCount && !parsedModel.pageCount) {
        parsedModel.pageCount = parsedModel.pages.length;
      }
      if (this.documentModel.fileName && !parsedModel.fileName) {
        parsedModel.fileName = this.documentModel.fileName;
      }
    }

    this.documentModel = parsedModel;
    return parsedModel;
  }
}

// -------------------------------------------------------------
// Test 1: Bounding box preservation when TipTap AST lacks bbox
// -------------------------------------------------------------
const initialModel = {
  id: "doc-1",
  name: "test.pdf",
  pages: [
    {
      pageNumber: 1,
      width: 612.0,
      height: 792.0,
      blocks: [
        {
          id: "blk-heading",
          type: "HEADING",
          level: 1,
          bbox: [50.0, 700.0, 200.0, 24.0],
          x: 50.0,
          y: 700.0,
          width: 200.0,
          height: 24.0,
          originalBbox: [50.0, 700.0, 200.0, 24.0],
          runs: [
            {
              text: "Original Heading",
              style: { fontFamily: "Helvetica", fontSize: 18.0, bold: true, color: "#111827" },
              bbox: [50.0, 700.0, 200.0, 24.0]
            }
          ]
        },
        {
          id: "blk-para",
          type: "PARAGRAPH",
          bbox: [50.0, 500.0, 400.0, 30.0],
          x: 50.0,
          y: 500.0,
          width: 400.0,
          height: 30.0,
          originalBbox: [50.0, 500.0, 400.0, 30.0],
          runs: [
            {
              text: "Original Paragraph Text",
              style: { fontFamily: "Helvetica", fontSize: 12.0, bold: false, color: "#374151" },
              bbox: [50.0, 500.0, 400.0, 30.0]
            }
          ]
        }
      ]
    }
  ]
};

const store = new MockDocumentStore(initialModel);

// TipTap JSON where user updated the paragraph text, and node has NO bbox attribute:
const tipTapUpdate = {
  type: "doc",
  content: [
    {
      type: "heading",
      attrs: { blockId: "blk-heading", level: 1, pageNumber: 1 },
      content: [{ type: "text", text: "Original Heading" }]
    },
    {
      type: "paragraph",
      attrs: { blockId: "blk-para", pageNumber: 1 }, // No bbox in attrs!
      content: [{ type: "text", text: "Updated Paragraph Content!" }]
    }
  ]
};

console.log("Running Test 1: updateFromTipTap with text edit and missing bbox in TipTap...");
const result = store.updateFromTipTap(tipTapUpdate);

if (!result) {
  console.error("FAIL: updateFromTipTap returned null");
  process.exit(1);
}

const updatedHeading = result.pages[0].blocks[0];
const updatedPara = result.pages[0].blocks[1];

// Verify coordinate preservation on heading (unchanged)
if (!updatedHeading.bbox || updatedHeading.bbox[0] !== 50.0 || updatedHeading.bbox[1] !== 700.0) {
  console.error("FAIL: Heading bbox was wiped:", updatedHeading.bbox);
  process.exit(1);
}

// Verify coordinate preservation on paragraph (edited, no bbox in TipTap)
if (!updatedPara.bbox || updatedPara.bbox[0] !== 50.0 || updatedPara.bbox[1] !== 500.0 || updatedPara.bbox[2] !== 400.0 || updatedPara.bbox[3] !== 30.0) {
  console.error("FAIL: Paragraph bbox was wiped when TipTap lacked bbox:", updatedPara.bbox);
  process.exit(1);
}

// Verify explicit x, y, width, height
if (updatedPara.x !== 50.0 || updatedPara.y !== 500.0 || updatedPara.width !== 400.0 || updatedPara.height !== 30.0) {
  console.error("FAIL: Explicit x,y,width,height not preserved:", { x: updatedPara.x, y: updatedPara.y, width: updatedPara.width, height: updatedPara.height });
  process.exit(1);
}

// Verify dirty / edited flags
if (updatedHeading.isEdited !== false) {
  console.error("FAIL: Heading marked edited when text did not change");
  process.exit(1);
}

if (updatedPara.isEdited !== true || updatedPara.isDirty !== true) {
  console.error("FAIL: Paragraph not marked isEdited/isDirty after text change");
  process.exit(1);
}

if (store.isCommittedSave !== false) {
  console.error("FAIL: store.isCommittedSave should be false after edits");
  process.exit(1);
}

// Verify blockPatches
const patch = store.blockPatches.get("1_blk-para");
if (!patch || patch.newText !== "Updated Paragraph Content!" || patch.originalText !== "Original Paragraph Text") {
  console.error("FAIL: Block patch incorrect:", patch);
  process.exit(1);
}

// Verify edits array for redaction endpoint
const edit = store.edits.find(e => e.lineId === "blk-para");
if (!edit || edit.newText !== "Updated Paragraph Content!" || !edit.bbox) {
  console.error("FAIL: Redaction edit incorrect:", edit);
  process.exit(1);
}

console.log("PASS: Test 1 passed cleanly!");

// -------------------------------------------------------------
// Test 2: Index fallback matching when blockId attribute is absent
// -------------------------------------------------------------
console.log("Running Test 2: Index fallback matching...");
const tipTapWithoutBlockId = {
  type: "doc",
  content: [
    {
      type: "heading",
      attrs: { level: 1, pageNumber: 1 }, // No blockId!
      content: [{ type: "text", text: "New Heading Text" }]
    },
    {
      type: "paragraph",
      attrs: { pageNumber: 1 }, // No blockId!
      content: [{ type: "text", text: "Updated Paragraph Content!" }]
    }
  ]
};

store.updateFromTipTap(tipTapWithoutBlockId);
const matchedHeading = store.documentModel.pages[0].blocks[0];
if (matchedHeading.id !== "blk-heading") {
  console.error("FAIL: Index fallback did not preserve original block id:", matchedHeading.id);
  process.exit(1);
}
if (!matchedHeading.bbox || matchedHeading.bbox[0] !== 50.0 || matchedHeading.bbox[1] !== 700.0) {
  console.error("FAIL: Index fallback did not preserve coordinates:", matchedHeading.bbox);
  process.exit(1);
}
if (matchedHeading.isEdited !== true) {
  console.error("FAIL: Heading should be marked isEdited after text change");
  process.exit(1);
}
console.log("PASS: Test 2 passed cleanly!");

// -------------------------------------------------------------
// Test 3: Reverting text back to original clears edit flags
// -------------------------------------------------------------
console.log("Running Test 3: Reverting text to original...");
const tipTapReverted = {
  type: "doc",
  content: [
    {
      type: "heading",
      attrs: { blockId: "blk-heading", level: 1, pageNumber: 1 },
      content: [{ type: "text", text: "Original Heading" }]
    },
    {
      type: "paragraph",
      attrs: { blockId: "blk-para", pageNumber: 1 },
      content: [{ type: "text", text: "Original Paragraph Text" }]
    }
  ]
};

store.updateFromTipTap(tipTapReverted);
const revertedHeading = store.documentModel.pages[0].blocks[0];
const revertedPara = store.documentModel.pages[0].blocks[1];

if (revertedHeading.isEdited || revertedPara.isEdited) {
  console.error("FAIL: Reverted blocks should not have isEdited=true");
  process.exit(1);
}
if (store.edits.length !== 0) {
  console.error("FAIL: edits array should be empty after revert, got:", store.edits);
  process.exit(1);
}
if (store.blockPatches.size !== 0) {
  console.error("FAIL: blockPatches should be empty after revert, got:", store.blockPatches);
  process.exit(1);
}
console.log("PASS: Test 3 passed cleanly!");

// -------------------------------------------------------------
// Test 4: Deleted blocks handling
// -------------------------------------------------------------
console.log("Running Test 4: Block deletion handling...");
const tipTapDeleted = {
  type: "doc",
  content: [
    {
      type: "heading",
      attrs: { blockId: "blk-heading", level: 1, pageNumber: 1 },
      content: [{ type: "text", text: "Original Heading" }]
    }
    // Paragraph blk-para is completely omitted (deleted by user in editor)!
  ]
};

store.updateFromTipTap(tipTapDeleted);
if (store.documentModel.pages[0].blocks.length !== 1) {
  console.error("FAIL: Deleted block still in documentModel blocks");
  process.exit(1);
}

const deleteEdit = store.edits.find(e => e.lineId === "blk-para");
if (!deleteEdit || deleteEdit.newText !== '' || deleteEdit.originalText !== "Original Paragraph Text") {
  console.error("FAIL: Deletion redaction edit missing or incorrect:", deleteEdit);
  process.exit(1);
}
console.log("PASS: Test 4 passed cleanly!");

console.log("ALL TESTS PASSED SUCCESSFULLY!");
