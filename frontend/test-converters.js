import { convertDocumentModelToTipTap } from './src/utils/jsonToTipTap.js';
import { convertTipTapToDocumentModel } from './src/utils/tipTapToJson.js';

const sampleModel = {
  id: "test-doc-123",
  name: "sample.pdf",
  pages: [
    {
      pageNumber: 1,
      width: 612.0,
      height: 792.0,
      blocks: [
        {
          id: "blk-1",
          type: "HEADING",
          level: 1,
          bbox: [50.0, 700.0, 200.0, 24.0],
          runs: [
            {
              text: "Hello World Heading",
              style: {
                fontFamily: "Helvetica",
                fontSize: 18.0,
                bold: true,
                italic: false,
                color: "#111827",
                lineHeight: 1.2
              },
              bbox: [50.0, 700.0, 200.0, 24.0]
            }
          ]
        },
        {
          id: "blk-2",
          type: "TABLE",
          bbox: [50.0, 600.0, 500.0, 80.0],
          rows: 1,
          cols: 2,
          cells: [
            [
              {
                id: "cell-left",
                type: "PARAGRAPH",
                bbox: [50.0, 600.0, 240.0, 80.0],
                runs: [
                  {
                    text: "HINDUSTAN COPPER LIMITED\n",
                    style: { fontFamily: "Helvetica", fontSize: 12.0, bold: true, italic: false, color: "#111827", lineHeight: 1.2 },
                    bbox: [50.0, 600.0, 240.0, 40.0]
                  },
                  {
                    text: "(A Govt. of India Enterprise)",
                    style: { fontFamily: "Helvetica", fontSize: 10.0, bold: false, italic: false, color: "#475569", lineHeight: 1.2 },
                    bbox: [50.0, 640.0, 240.0, 40.0]
                  }
                ]
              },
              {
                id: "cell-right",
                type: "PARAGRAPH",
                bbox: [310.0, 600.0, 240.0, 80.0],
                runs: [
                  {
                    text: "Enquiry No.: M19282\n",
                    style: { fontFamily: "Helvetica", fontSize: 11.0, bold: true, italic: false, color: "#111827", lineHeight: 1.2 },
                    bbox: [310.0, 600.0, 240.0, 40.0]
                  },
                  {
                    text: "Date: 23-12-2025",
                    style: { fontFamily: "Helvetica", fontSize: 10.0, bold: false, italic: false, color: "#475569", lineHeight: 1.2 },
                    bbox: [310.0, 640.0, 240.0, 40.0]
                  }
                ]
              }
            ]
          ]
        },
        {
          id: "blk-3",
          type: "PARAGRAPH",
          textAlign: "left",
          bbox: [50.0, 500.0, 400.0, 30.0],
          runs: [
            {
              text: "This is a paragraph run.",
              style: {
                fontFamily: "Helvetica",
                fontSize: 12.0,
                bold: false,
                italic: false,
                color: "#374151",
                lineHeight: 1.2
              },
              bbox: [50.0, 500.0, 400.0, 30.0]
            }
          ]
        }
      ]
    }
  ]
};

console.log("Converting DocumentModel -> TipTap AST...");
const tiptapAst = convertDocumentModelToTipTap(sampleModel);
console.log("TipTap AST node count:", tiptapAst.content.length);

if (tiptapAst.type !== 'doc' || tiptapAst.content.length !== 3) {
  console.error("FAIL: TipTap AST did not match expected structure");
  process.exit(1);
}

const tableNode = tiptapAst.content[1];
if (tableNode.type !== 'table' || tableNode.content[0].type !== 'tableRow' || tableNode.content[0].content.length !== 2) {
  console.error("FAIL: Table node structure incorrect:", JSON.stringify(tableNode, null, 2));
  process.exit(1);
}

console.log("Converting TipTap AST -> DocumentModel...");
const restoredModel = convertTipTapToDocumentModel(tiptapAst, sampleModel);

if (restoredModel.pages.length !== 1 || restoredModel.pages[0].blocks.length !== 3) {
  console.error("FAIL: Restored model did not retain blocks correctly", restoredModel);
  process.exit(1);
}

const restoredTable = restoredModel.pages[0].blocks[1];
if (restoredTable.type !== "TABLE" || restoredTable.rows !== 1 || restoredTable.cols !== 2) {
  console.error("FAIL: Restored table mismatch", restoredTable);
  process.exit(1);
}

const leftCell = restoredTable.cells[0][0];
const rightCell = restoredTable.cells[0][1];
const leftText = leftCell.runs.map(r => r.text).join("");
const rightText = rightCell.runs.map(r => r.text).join("");

if (!leftText.includes("HINDUSTAN COPPER LIMITED") || !rightText.includes("Enquiry No.: M19282")) {
  console.error("FAIL: Cell text mismatch:", { leftText, rightText });
  process.exit(1);
}

console.log("SUCCESS: All bidirectional AST converter assertions (including TableBlock) passed cleanly!");
