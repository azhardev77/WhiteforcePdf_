import * as pdfjsLib from 'pdfjs-dist';
import pdfWorker from 'pdfjs-dist/build/pdf.worker.mjs?url';

pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorker;

/**
 * Client-Side PDF layout and metadata extractor using pdfjs-dist.
 * Bypasses all backend dependencies to construct a lightweight DocumentModel
 * directly in the browser.
 *
 * @param {ArrayBuffer|Uint8Array} pdfBytes - Raw PDF binary data
 * @param {string} [fileName] - Document filename
 * @returns {Promise<Object>} DocumentModel containing pages and dimensions
 */
export async function extractPdfClient(pdfBytes, fileName = 'document.pdf') {
  if (!pdfBytes) {
    throw new Error('No PDF binary data provided for client extraction.');
  }

  let bufferCopy;
  if (pdfBytes instanceof Uint8Array) {
    bufferCopy = new Uint8Array(pdfBytes.buffer.slice(pdfBytes.byteOffset, pdfBytes.byteOffset + pdfBytes.byteLength));
  } else if (pdfBytes instanceof ArrayBuffer) {
    bufferCopy = new Uint8Array(pdfBytes.slice(0));
  } else {
    bufferCopy = new Uint8Array(pdfBytes);
  }

  const loadingTask = pdfjsLib.getDocument({
    data: bufferCopy,
    cMapUrl: 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/cmaps/',
    cMapPacked: true
  });

  const pdfDoc = await loadingTask.promise;
  const numPages = pdfDoc.numPages;
  const pages = [];

  for (let p = 1; p <= numPages; p++) {
    try {
      const page = await pdfDoc.getPage(p);
      const view = page.view || [0, 0, 595.28, 841.89];
      const width = view[2] - view[0] || 595.28;
      const height = view[3] - view[1] || 841.89;

      pages.push({
        pageNumber: p,
        width,
        height,
        rotation: page.rotate || 0
      });
    } catch (err) {
      console.warn(`[clientPdfExtractor] Failed to read page ${p} dimensions:`, err);
      pages.push({
        pageNumber: p,
        width: 595.28,
        height: 841.89,
        rotation: 0
      });
    }
  }

  return {
    id: `doc-${Date.now()}`,
    name: fileName,
    pageCount: numPages,
    pages
  };
}

export default {
  extractPdfClient
};
