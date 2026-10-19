import { PDFDocument, rgb, StandardFonts, degrees } from 'pdf-lib';

/**
 * Sanitizes input text for standard PDF WinAnsi / Latin-1 encoding,
 * preventing pdf-lib encoding errors while preserving common typographical symbols.
 *
 * @param {string} text - Raw string to sanitize
 * @returns {string} Sanitized string
 */
export function sanitizeForPdf(text) {
  if (!text) return '';
  return text
    .replace(/\u20B9/g, 'Rs. ')
    .replace(/[\u201C\u201D]/g, '"')
    .replace(/[\u2018\u2019]/g, "'")
    .replace(/[\u2013\u2014]/g, '-')
    .replace(/\u2022/g, '-')
    .replace(/\u2026/g, '...')
    .replace(/\t/g, '    ')
    .replace(/\u00A0/g, ' ')
    .replace(/[^\x00-\xFF]/g, '');
}

/**
 * Converts rotated viewport coordinates back into unrotated PDF coordinate space.
 *
 * @param {Array<number>} bbox - [x, y, w, h] in viewport
 * @param {number} rot - Rotation degrees (0, 90, 180, 270)
 * @param {number} pageW - Unrotated page width
 * @param {number} pageH - Unrotated page height
 * @returns {Array<number>} [pdfX, pdfY, pdfW, pdfH] in unrotated PDF top-left coordinates
 */
export function toUnrotatedPdfCoords(bbox, rot, pageW, pageH) {
  if (!bbox || bbox.length < 4) return [0, 0, 0, 0];
  const r = ((rot % 360) + 360) % 360;
  if (r === 0) return [...bbox];

  const [vx, vy, vw, vh] = bbox;
  if (r === 90) {
    return [vy, pageH - vx - vw, vh, vw];
  } else if (r === 180) {
    return [pageW - vx - vw, pageH - vy - vh, vw, vh];
  } else if (r === 270) {
    return [pageW - vy - vh, vx, vh, vw];
  }
  return [...bbox];
}

/**
 * Parses hexadecimal color code to pdf-lib rgb() color.
 *
 * @param {string} hex - Hex string e.g. '#1e293b'
 * @returns {Object} pdf-lib rgb color
 */
export function parseHexColor(hex) {
  if (!hex || typeof hex !== 'string') return rgb(0, 0, 0);
  const clean = hex.replace('#', '').trim();
  if (clean.length === 6) {
    const r = parseInt(clean.slice(0, 2), 16) / 255;
    const g = parseInt(clean.slice(2, 4), 16) / 255;
    const b = parseInt(clean.slice(4, 6), 16) / 255;
    return rgb(isNaN(r) ? 0 : r, isNaN(g) ? 0 : g, isNaN(b) ? 0 : b);
  } else if (clean.length === 3) {
    const r = parseInt(clean[0] + clean[0], 16) / 255;
    const g = parseInt(clean[1] + clean[1], 16) / 255;
    const b = parseInt(clean[2] + clean[2], 16) / 255;
    return rgb(isNaN(r) ? 0 : r, isNaN(g) ? 0 : g, isNaN(b) ? 0 : b);
  }
  return rgb(0, 0, 0);
}

/**
 * Selects standard PDF font based on fontFamily name, bold, and italic flags.
 *
 * @param {Object} fonts - Map of embedded StandardFonts
 * @param {string} fontFamily - Font family name
 * @param {boolean} bold - Bold flag
 * @param {boolean} italic - Italic flag
 * @returns {Object} Selected PDFFont
 */
export function selectStandardFont(fonts, fontFamily, bold, italic) {
  const fn = (fontFamily || '').toLowerCase();
  if (fn.includes('times') || fn.includes('serif') || fn.includes('roman')) {
    if (bold && italic) return fonts.timesBoldItalic;
    if (bold) return fonts.timesBold;
    if (italic) return fonts.timesItalic;
    return fonts.times;
  }
  if (fn.includes('courier') || fn.includes('mono')) {
    if (bold && italic) return fonts.courierBoldItalic;
    if (bold) return fonts.courierBold;
    if (italic) return fonts.courierItalic;
    return fonts.courier;
  }
  if (bold && italic) return fonts.helveticaBoldItalic;
  if (bold) return fonts.helveticaBold;
  if (italic) return fonts.helveticaItalic;
  return fonts.helvetica;
}

/**
 * Wraps text into lines that fit within maxWidth using pdf-lib font metrics.
 *
 * @param {string} text - Raw text to wrap
 * @param {Object} font - pdf-lib PDFFont
 * @param {number} fontSize - Font size in pt
 * @param {number} maxWidth - Available width in pt
 * @returns {Array<string>} Array of wrapped lines
 */
export function wrapText(text, font, fontSize, maxWidth) {
  if (!text) return [];
  const sanitized = sanitizeForPdf(text);
  const paragraphs = sanitized.split(/\r?\n/);
  const resultLines = [];
  const maxW = Math.max(maxWidth, fontSize * 2);

  for (const paragraph of paragraphs) {
    if (!paragraph.trim()) {
      resultLines.push('');
      continue;
    }
    const words = paragraph.split(/\s+/);
    let currentLine = '';

    for (const word of words) {
      if (!word) continue;
      const testLine = currentLine ? `${currentLine} ${word}` : word;
      let testWidth = 0;
      try {
        testWidth = font.widthOfTextAtSize(testLine, fontSize);
      } catch {
        testWidth = testLine.length * fontSize * 0.55;
      }

      if (testWidth <= maxW) {
        currentLine = testLine;
      } else {
        if (currentLine) {
          resultLines.push(currentLine);
          currentLine = '';
        }
        let wordWidth = 0;
        try {
          wordWidth = font.widthOfTextAtSize(word, fontSize);
        } catch {
          wordWidth = word.length * fontSize * 0.55;
        }

        if (wordWidth > maxW) {
          let chunk = '';
          for (let i = 0; i < word.length; i++) {
            const ch = word[i];
            const testChunk = chunk + ch;
            let chunkW = 0;
            try {
              chunkW = font.widthOfTextAtSize(testChunk, fontSize);
            } catch {
              chunkW = testChunk.length * fontSize * 0.55;
            }
            if (chunkW > maxW && chunk) {
              resultLines.push(chunk);
              chunk = '';
            }
            chunk += ch;
          }
          currentLine = chunk;
        } else {
          currentLine = word;
        }
      }
    }
    if (currentLine) {
      resultLines.push(currentLine);
    }
  }
  return resultLines;
}

/**
 * Pure In-Browser PDF modification & redaction export engine using pdf-lib.
 * Modifies existing PDF binary by applying whiteout redactions and drawing replacement text.
 *
 * @param {ArrayBuffer|Uint8Array} originalPdfBytes - Original PDF binary buffer
 * @param {Array<Object>} edits - Active redactions and inline edits
 * @param {Object} [options] - Additional view settings { userRotations }
 * @returns {Promise<Uint8Array>} Modified PDF binary bytes
 */
export async function exportModifiedPdf(originalPdfBytes, edits = [], options = {}) {
  if (!originalPdfBytes) {
    throw new Error('No PDF data provided for in-place export.');
  }

  let rawBytes;
  if (originalPdfBytes instanceof Uint8Array) {
    rawBytes = originalPdfBytes;
  } else if (originalPdfBytes instanceof ArrayBuffer) {
    rawBytes = new Uint8Array(originalPdfBytes.slice(0));
  } else {
    rawBytes = new Uint8Array(originalPdfBytes);
  }

  const pdfDoc = await PDFDocument.load(rawBytes);
  const numPages = pdfDoc.getPageCount();

  const fonts = {
    helvetica: await pdfDoc.embedFont(StandardFonts.Helvetica),
    helveticaBold: await pdfDoc.embedFont(StandardFonts.HelveticaBold),
    helveticaItalic: await pdfDoc.embedFont(StandardFonts.HelveticaOblique),
    helveticaBoldItalic: await pdfDoc.embedFont(StandardFonts.HelveticaBoldOblique),
    times: await pdfDoc.embedFont(StandardFonts.TimesRoman),
    timesBold: await pdfDoc.embedFont(StandardFonts.TimesRomanBold),
    timesItalic: await pdfDoc.embedFont(StandardFonts.TimesRomanItalic),
    timesBoldItalic: await pdfDoc.embedFont(StandardFonts.TimesRomanBoldItalic),
    courier: await pdfDoc.embedFont(StandardFonts.Courier),
    courierBold: await pdfDoc.embedFont(StandardFonts.CourierBold),
    courierItalic: await pdfDoc.embedFont(StandardFonts.CourierOblique),
    courierBoldItalic: await pdfDoc.embedFont(StandardFonts.CourierBoldOblique)
  };

  const userRotations = options.userRotations || {};

  // Group edits by pageNumber (1-indexed)
  const editsByPage = {};
  for (const edit of edits) {
    const p = edit.pageNumber || 1;
    if (!editsByPage[p]) editsByPage[p] = [];
    editsByPage[p].push(edit);
  }

  for (let p = 1; p <= numPages; p++) {
    const pageIndex = p - 1;
    const page = pdfDoc.getPage(pageIndex);
    const { width: pageW, height: pageH } = page.getSize();
    const rotDelta = (userRotations[p] || 0) % 360;

    const pageEdits = editsByPage[p] || [];

    for (const edit of pageEdits) {
      const pageRot = (edit.pageRotation != null ? edit.pageRotation : rotDelta) % 360;
      const whiteoutBbox = edit.originalBbox || edit.bbox || [0, 0, 50, 15];
      const unrotatedWhiteout = toUnrotatedPdfCoords(whiteoutBbox, pageRot, pageW, pageH);

      const [whiteoutX, whiteoutY, whiteoutW, whiteoutH] = unrotatedWhiteout;

      // 1. Draw solid whiteout rectangle over original text/icon if not transparent
      if (!edit.transparentBackground) {
        const padX = 0.5;
        const rectX = Math.max(0, whiteoutX - padX);
        const rectW = whiteoutW + (padX * 2);
        const rectY = pageH - whiteoutY - whiteoutH;
        const rectH = whiteoutH;

        page.drawRectangle({
          x: rectX,
          y: rectY,
          width: rectW,
          height: rectH,
          color: rgb(1, 1, 1) // Whiteout mask
        });
      } else if (edit.originalText && /^[\s,.;:\-_–—]+$/.test(edit.originalText) && edit.originalText.trim()) {
        // If blank line with trailing comma/dot, only whiteout the trailing punctuation
        const punctW = Math.min(12, whiteoutW);
        const punctX = whiteoutX + whiteoutW - punctW;
        const rectY = pageH - whiteoutY - whiteoutH;
        page.drawRectangle({
          x: punctX,
          y: rectY,
          width: punctW,
          height: whiteoutH,
          color: rgb(1, 1, 1)
        });
      }

      // 2. Draw replacement text if newText is non-empty
      const newText = (edit.newText || '').trim();
      if (newText) {
        const style = edit.style || {};
        const fontSize = Math.max(6, Math.min(72, Number(style.fontSize) || 11));
        const bold = Boolean(style.bold);
        const italic = Boolean(style.italic);
        const font = selectStandardFont(fonts, style.fontFamily, bold, italic);
        const textColor = parseHexColor(style.color || '#000000');
        const lineHeight = fontSize * 1.2;

        const targetBbox = edit.bbox || whiteoutBbox;
        const unrotatedTarget = toUnrotatedPdfCoords(targetBbox, pageRot, pageW, pageH);
        const [boxX, boxY, boxW] = unrotatedTarget;

        const maxAllowedW = Math.max(boxW, pageW - boxX - 36);
        const lines = wrapText(newText, font, fontSize, maxAllowedW);

        for (let i = 0; i < lines.length; i++) {
          const lineStr = lines[i];
          if (!lineStr) continue;

          const textX = boxX;
          const baselineY = pageH - boxY - (fontSize * 0.82) - (i * lineHeight);

          try {
            page.drawText(lineStr, {
              x: textX,
              y: baselineY,
              size: fontSize,
              font,
              color: textColor
            });
          } catch (err) {
            console.warn('[pdf-lib] Could not draw line:', lineStr, err);
          }
        }
      }
    }

    // Apply rotation transformation if page was rotated in editor
    if (rotDelta !== 0) {
      const origAngle = page.getRotation().angle;
      page.setRotation(degrees((origAngle + rotDelta) % 360));
    }
  }

  const modifiedBytes = await pdfDoc.save();
  return modifiedBytes;
}

/**
 * Triggers an immediate browser file download for a given Blob or Uint8Array.
 *
 * @param {Blob|Uint8Array} blobOrBytes - PDF binary data
 * @param {string} fileName - Destination file name
 */
export function triggerBrowserDownload(blobOrBytes, fileName) {
  const blob = blobOrBytes instanceof Blob
    ? blobOrBytes
    : new Blob([blobOrBytes], { type: 'application/pdf' });
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName || 'document_edited.pdf';
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}

export default {
  exportModifiedPdf,
  triggerBrowserDownload,
  sanitizeForPdf,
  toUnrotatedPdfCoords,
  parseHexColor,
  selectStandardFont,
  wrapText
};
