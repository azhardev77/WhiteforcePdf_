/**
 * Converts a TipTap / ProseMirror document AST back into a DocumentModel.
 *
 * @param {Object} tiptapDoc - TipTap document node: { type: "doc", content: [...] }
 * @param {Object} [originalDocumentModel] - Optional original DocumentModel to preserve metadata and page dimensions
 * @returns {Object} Reconstructed DocumentModel
 */
export function convertTipTapToDocumentModel(tiptapDoc, originalDocumentModel) {
  const documentId = originalDocumentModel?.id || generateUUID();
  const documentName = originalDocumentModel?.name || "document.pdf";
  const originalPages = Array.isArray(originalDocumentModel?.pages) ? originalDocumentModel.pages : [];

  if (!tiptapDoc || !Array.isArray(tiptapDoc.content) || tiptapDoc.content.length === 0) {
    const defaultPage = {
      pageNumber: 1,
      width: originalPages[0]?.width || 612.0,
      height: originalPages[0]?.height || 792.0,
      blocks: []
    };
    return {
      id: documentId,
      name: documentName,
      pages: [defaultPage]
    };
  }

  const pageBlocksMap = new Map();
  let currentPageNumber = 1;
  pageBlocksMap.set(currentPageNumber, []);

  for (const node of tiptapDoc.content) {
    if (!node) continue;

    // Handle pageBreak node demarcation
    if (node.type === "pageBreak") {
      const nextPg = parseInt(node.attrs?.pageNumber, 10) || (currentPageNumber + 1);
      currentPageNumber = nextPg;
      if (!pageBlocksMap.has(currentPageNumber)) {
        pageBlocksMap.set(currentPageNumber, []);
      }
      continue;
    }

    // Check if block node carries a different pageNumber attribute
    if (node.attrs?.pageNumber) {
      const nodePg = parseInt(node.attrs.pageNumber, 10);
      if (nodePg && nodePg !== currentPageNumber) {
        currentPageNumber = nodePg;
        if (!pageBlocksMap.has(currentPageNumber)) {
          pageBlocksMap.set(currentPageNumber, []);
        }
      }
    }

    const block = convertTipTapNodeToBlock(node);
    if (block) {
      const currentList = pageBlocksMap.get(currentPageNumber) || [];
      currentList.push(block);
      pageBlocksMap.set(currentPageNumber, currentList);
    }
  }

  // Build PageModel array sorted by pageNumber
  const sortedPageNumbers = Array.from(pageBlocksMap.keys()).sort((a, b) => a - b);
  const pages = [];

  for (const pageNumber of sortedPageNumbers) {
    const blocks = pageBlocksMap.get(pageNumber) || [];
    const origPage = originalPages.find((p) => p.pageNumber === pageNumber) || originalPages[pageNumber - 1];

    pages.push({
      pageNumber,
      width: origPage?.width || 612.0,
      height: origPage?.height || 792.0,
      blocks
    });
  }

  if (pages.length === 0) {
    pages.push({
      pageNumber: 1,
      width: 612.0,
      height: 792.0,
      blocks: []
    });
  }

  return {
    id: documentId,
    name: documentName,
    pages
  };
}

/**
 * Converts a TipTap block node (heading, paragraph, etc.) to a Block model.
 */
function convertTipTapNodeToBlock(node) {
  if (!node) return null;

  if (node.type === "table") {
    return convertTipTapTableToBlock(node);
  }

  const runs = extractTextRuns(node.content);
  const blockId = node.attrs?.blockId || generateUUID();
  const bbox = Array.isArray(node.attrs?.bbox) ? node.attrs.bbox : computeBboxFromRuns(runs);

  if (node.type === "heading") {
    const level = Math.min(6, Math.max(1, parseInt(node.attrs?.level, 10) || 1));
    return {
      id: blockId,
      type: "HEADING",
      level,
      bbox,
      runs
    };
  }

  // Default to PARAGRAPH
  return {
    id: blockId,
    type: "PARAGRAPH",
    textAlign: node.attrs?.textAlign || "left",
    bbox,
    runs
  };
}

/**
 * Converts a TipTap table node into a TableBlock.
 */
function convertTipTapTableToBlock(node) {
  const blockId = node.attrs?.blockId || generateUUID();
  const bbox = Array.isArray(node.attrs?.bbox) ? node.attrs.bbox : [0.0, 0.0, 0.0, 0.0];
  const rowNodes = Array.isArray(node.content) ? node.content : [];
  const cells = [];

  for (const rowNode of rowNodes) {
    if (rowNode.type !== "tableRow") continue;
    const rowCells = [];
    const cellNodes = Array.isArray(rowNode.content) ? rowNode.content : [];

    for (const cellNode of cellNodes) {
      if (cellNode.type === "tableCell" || cellNode.type === "tableHeader") {
        const cellRuns = [];
        if (Array.isArray(cellNode.content)) {
          for (let pIdx = 0; pIdx < cellNode.content.length; pIdx++) {
            const childNode = cellNode.content[pIdx];
            if (pIdx > 0 && cellRuns.length > 0) {
              cellRuns.push({
                text: "\n",
                style: {
                  fontFamily: "sans-serif",
                  fontSize: 12.0,
                  bold: false,
                  italic: false,
                  color: "#000000",
                  lineHeight: 1.2
                },
                bbox: [0.0, 0.0, 0.0, 0.0]
              });
            }
            const runs = extractTextRuns(childNode.content);
            cellRuns.push(...runs);
          }
        }

        const cellBbox = computeBboxFromRuns(cellRuns);
        rowCells.push({
          id: generateUUID(),
          type: "PARAGRAPH",
          bbox: cellBbox,
          runs: cellRuns
        });
      }
    }
    if (rowCells.length > 0) {
      cells.push(rowCells);
    }
  }

  const rowsCount = cells.length;
  const colsCount = rowsCount > 0 ? cells[0].length : 0;

  return {
    id: blockId,
    type: "TABLE",
    bbox,
    rows: rowsCount,
    cols: colsCount,
    cells
  };
}

/**
 * Extracts TextRun objects from TipTap node's inline content.
 */
function extractTextRuns(content) {
  if (!Array.isArray(content) || content.length === 0) {
    return [];
  }

  const runs = [];

  for (const child of content) {
    if (!child) continue;

    if (child.type === "text" && child.text != null && child.text !== "") {
      const style = extractStyleFromMarks(child.marks);
      const bbox = Array.isArray(child.attrs?.bbox) ? child.attrs.bbox : [0.0, 0.0, 0.0, 0.0];

      runs.push({
        text: String(child.text),
        style,
        bbox
      });
    } else if (child.type === "hardBreak") {
      // Represent hard line breaks as a newline in the text run
      runs.push({
        text: "\n",
        style: {
          fontFamily: "sans-serif",
          fontSize: 12.0,
          bold: false,
          italic: false,
          color: "#000000",
          lineHeight: 1.2
        },
        bbox: [0.0, 0.0, 0.0, 0.0]
      });
    }
  }

  return runs;
}

/**
 * Extracts typography and color styles from ProseMirror/TipTap marks.
 */
function extractStyleFromMarks(marks) {
  let bold = false;
  let italic = false;
  let fontSize = 12.0;
  let fontFamily = "sans-serif";
  let color = "#000000";
  const lineHeight = 1.2;

  if (Array.isArray(marks)) {
    for (const mark of marks) {
      if (!mark) continue;

      if (mark.type === "bold") {
        bold = true;
      }

      if (mark.type === "italic") {
        italic = true;
      }

      if (mark.type === "textStyle" && mark.attrs) {
        if (mark.attrs.fontSize != null) {
          fontSize = parseFontSize(mark.attrs.fontSize);
        }
        if (mark.attrs.fontFamily && typeof mark.attrs.fontFamily === "string") {
          fontFamily = mark.attrs.fontFamily;
        }
        if (mark.attrs.color && typeof mark.attrs.color === "string") {
          color = mark.attrs.color;
        }
      }

      // Individual mark fallbacks
      if (mark.type === "fontSize" && mark.attrs?.fontSize != null) {
        fontSize = parseFontSize(mark.attrs.fontSize);
      }
      if (mark.type === "fontFamily" && mark.attrs?.fontFamily) {
        fontFamily = mark.attrs.fontFamily;
      }
      if (mark.type === "color" && mark.attrs?.color) {
        color = mark.attrs.color;
      }
    }
  }

  return {
    fontFamily,
    fontSize,
    bold,
    italic,
    color,
    lineHeight
  };
}

/**
 * Parses font size string (e.g. "12pt", "16px") or numeric into a double.
 */
function parseFontSize(val) {
  if (typeof val === "number") {
    return val > 0 ? val : 12.0;
  }
  if (typeof val === "string") {
    const parsed = parseFloat(val);
    if (!isNaN(parsed) && parsed > 0) {
      return parsed;
    }
  }
  return 12.0;
}

/**
 * Computes bounding box union from constituent TextRuns if not provided.
 */
function computeBboxFromRuns(runs) {
  if (!Array.isArray(runs) || runs.length === 0) {
    return [0.0, 0.0, 0.0, 0.0];
  }

  let minX = Infinity;
  let minY = Infinity;
  let maxX = -Infinity;
  let maxY = -Infinity;
  let hasValidBbox = false;

  for (const run of runs) {
    if (Array.isArray(run.bbox) && run.bbox.length === 4) {
      const [x, y, w, h] = run.bbox;
      if (w > 0 || h > 0) {
        minX = Math.min(minX, x);
        minY = Math.min(minY, y);
        maxX = Math.max(maxX, x + w);
        maxY = Math.max(maxY, y + h);
        hasValidBbox = true;
      }
    }
  }

  if (!hasValidBbox) {
    return [0.0, 0.0, 0.0, 0.0];
  }

  return [minX, minY, Math.max(0, maxX - minX), Math.max(0, maxY - minY)];
}

/**
 * Generates a standard RFC4122 v4 UUID string.
 */
function generateUUID() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return crypto.randomUUID();
  }
  return "xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx".replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0;
    const v = c === "x" ? r : (r & 0x3) | 0x8;
    return v.toString(16);
  });
}
