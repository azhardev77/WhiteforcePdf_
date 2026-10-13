/**
 * Converts a backend DocumentModel into a TipTap / ProseMirror document AST.
 *
 * @param {Object} documentModel - Java DocumentModel serialized to JSON
 * @returns {Object} TipTap document node: { type: "doc", content: [...] }
 */
export function convertDocumentModelToTipTap(documentModel) {
  if (!documentModel || !Array.isArray(documentModel.pages) || documentModel.pages.length === 0) {
    return {
      type: "doc",
      content: [
        {
          type: "paragraph",
          attrs: { textAlign: "left", pageNumber: 1 },
          content: []
        }
      ]
    };
  }

  const docContent = [];

  for (let pageIndex = 0; pageIndex < documentModel.pages.length; pageIndex++) {
    const page = documentModel.pages[pageIndex];
    const pageNumber = page.pageNumber || pageIndex + 1;
    const pageWidth = page.width || 612.0;
    const pageHeight = page.height || 792.0;

    // Demarcate page boundaries using a pageBreak node between pages
    if (pageIndex > 0) {
      docContent.push({
        type: "pageBreak",
        attrs: {
          pageNumber,
          width: pageWidth,
          height: pageHeight
        }
      });
    }

    const blocks = Array.isArray(page.blocks) ? page.blocks : [];

    if (blocks.length === 0) {
      docContent.push({
        type: "paragraph",
        attrs: {
          textAlign: "left",
          pageNumber,
          pageWidth,
          pageHeight
        },
        content: []
      });
      continue;
    }

    for (const block of blocks) {
      const tipTapBlock = convertBlockToTipTap(block, pageNumber, pageWidth, pageHeight);
      if (tipTapBlock) {
        docContent.push(tipTapBlock);
      }
    }
  }

  if (docContent.length === 0) {
    docContent.push({
      type: "paragraph",
      attrs: { textAlign: "left", pageNumber: 1 },
      content: []
    });
  }

  return {
    type: "doc",
    content: docContent
  };
}

/**
 * Converts a single Block (ParagraphBlock, HeadingBlock, TableBlock) to a TipTap node.
 */
function convertBlockToTipTap(block, pageNumber, pageWidth, pageHeight) {
  if (!block) return null;

  const blockType = (block.type || "").toUpperCase();
  const blockAttrs = {
    blockId: block.id || null,
    bbox: block.bbox || null,
    pageNumber,
    pageWidth,
    pageHeight
  };

  if (blockType === "TABLE") {
    return convertTableBlockToTipTap(block, pageNumber, pageWidth, pageHeight);
  }

  const runs = Array.isArray(block.runs) ? block.runs : [];
  const runNodes = [];

  for (const run of runs) {
    const nodes = convertTextRunToTipTapNodes(run);
    if (nodes && nodes.length > 0) {
      runNodes.push(...nodes);
    }
  }

  if (blockType === "HEADING") {
    const level = Math.min(6, Math.max(1, parseInt(block.level, 10) || 1));
    return {
      type: "heading",
      attrs: {
        ...blockAttrs,
        level,
        textAlign: block.textAlign || "left"
      },
      content: runNodes
    };
  }

  return {
    type: "paragraph",
    attrs: {
      ...blockAttrs,
      textAlign: block.textAlign || "left"
    },
    content: runNodes
  };
}

/**
 * Converts a TableBlock into a TipTap table node with tableRow and tableCell children.
 */
function convertTableBlockToTipTap(block, pageNumber, pageWidth, pageHeight) {
  const blockAttrs = {
    blockId: block.id || null,
    bbox: block.bbox || null,
    pageNumber,
    pageWidth,
    pageHeight
  };

  const rows = Array.isArray(block.cells) ? block.cells : [];
  const tableRowNodes = [];

  for (const row of rows) {
    if (!Array.isArray(row)) continue;
    const tableCellNodes = [];

    for (const cell of row) {
      const cellParagraph = convertBlockToTipTap(cell, pageNumber, pageWidth, pageHeight) || {
        type: "paragraph",
        content: []
      };

      tableCellNodes.push({
        type: "tableCell",
        attrs: {
          colspan: 1,
          rowspan: 1,
          colwidth: null
        },
        content: [cellParagraph]
      });
    }

    if (tableCellNodes.length > 0) {
      tableRowNodes.push({
        type: "tableRow",
        content: tableCellNodes
      });
    }
  }

  if (tableRowNodes.length === 0) {
    tableRowNodes.push({
      type: "tableRow",
      content: [
        {
          type: "tableCell",
          attrs: { colspan: 1, rowspan: 1, colwidth: null },
          content: [{ type: "paragraph", content: [] }]
        }
      ]
    });
  }

  return {
    type: "table",
    attrs: blockAttrs,
    content: tableRowNodes
  };
}

/**
 * Converts a TextRun into TipTap inline nodes, handling newlines via hardBreak.
 */
function convertTextRunToTipTapNodes(run) {
  if (!run || run.text == null || run.text === "") {
    return [];
  }

  const marks = [];
  const style = run.style || {};

  if (style.bold === true) {
    marks.push({ type: "bold" });
  }

  if (style.italic === true) {
    marks.push({ type: "italic" });
  }

  const textStyleAttrs = {};

  if (style.fontSize != null && style.fontSize > 0) {
    textStyleAttrs.fontSize = typeof style.fontSize === "number"
      ? `${style.fontSize}pt`
      : String(style.fontSize);
  }

  if (style.fontFamily && style.fontFamily.trim() !== "") {
    textStyleAttrs.fontFamily = style.fontFamily;
  }

  if (style.color && style.color.trim() !== "") {
    textStyleAttrs.color = style.color;
  }

  if (Object.keys(textStyleAttrs).length > 0) {
    marks.push({
      type: "textStyle",
      attrs: textStyleAttrs
    });
  }

  const textStr = String(run.text);
  if (!textStr.includes("\n")) {
    const node = { type: "text", text: textStr };
    if (marks.length > 0) node.marks = marks;
    return [node];
  }

  const parts = textStr.split("\n");
  const nodes = [];

  for (let i = 0; i < parts.length; i++) {
    const part = parts[i];
    if (part !== "") {
      const node = { type: "text", text: part };
      if (marks.length > 0) node.marks = marks;
      nodes.push(node);
    }
    if (i < parts.length - 1) {
      nodes.push({ type: "hardBreak" });
    }
  }

  return nodes;
}
