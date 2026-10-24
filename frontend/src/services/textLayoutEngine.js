/**
 * textLayoutEngine.js
 * 
 * Headless Text Measurement & Paragraph Partitioning Engine for PDF Layouts.
 * Uses an offscreen HTML5 Canvas 2D context to calculate line breaks,
 * paragraph height, and clean boundary-based paragraph splitting.
 */

let sharedCanvas = null;
let sharedCtx = null;

function getCanvasContext() {
  if (!sharedCtx) {
    if (typeof document !== 'undefined') {
      sharedCanvas = document.createElement('canvas');
      sharedCtx = sharedCanvas.getContext('2d');
    }
  }
  return sharedCtx;
}

/**
 * Formats CSS font specification for Canvas 2D context.
 *
 * @param {Object} fontSpec
 * @param {number} [fontSpec.fontSize=12]
 * @param {string} [fontSpec.fontFamily='Helvetica, Arial, sans-serif']
 * @param {boolean} [fontSpec.bold=false]
 * @param {boolean} [fontSpec.italic=false]
 * @returns {string} CSS font string e.g. "bold italic 12px Helvetica, Arial, sans-serif"
 */
export function formatFontString(fontSpec = {}) {
  const size = fontSpec.fontSize || 12;
  const family = fontSpec.fontFamily || 'Helvetica, Arial, sans-serif';
  const weight = fontSpec.bold ? 'bold' : 'normal';
  const style = fontSpec.italic ? 'italic' : 'normal';
  return `${style} ${weight} ${size}px ${family}`;
}

/**
 * Measures the pixel width of a string given a font specification.
 *
 * @param {string} text
 * @param {Object} fontSpec
 * @returns {number} Width in points/pixels
 */
export function measureTextWidth(text, fontSpec = {}) {
  if (!text) return 0;
  const ctx = getCanvasContext();
  if (ctx) {
    ctx.font = formatFontString(fontSpec);
    return ctx.measureText(text).width;
  }
  // Fallback approximation when running outside DOM
  const size = fontSpec.fontSize || 12;
  return text.length * size * 0.55;
}

/**
 * Computes wrapped lines and total height for a given text, font specification, and container width.
 *
 * @param {string} text
 * @param {Object} fontSpec
 * @param {number} containerWidth
 * @returns {{ lines: string[], height: number, lineHeight: number }}
 */
export function computeLines(text, fontSpec = {}, containerWidth = 500) {
  const fontSize = fontSpec.fontSize || 12;
  const lineHeight = fontSpec.lineHeight || Math.max(Math.round(fontSize * 1.25), 14);
  const maxW = Math.max(containerWidth, 40);

  if (!text) {
    return { lines: [''], height: lineHeight, lineHeight };
  }

  const ctx = getCanvasContext();
  if (ctx) {
    ctx.font = formatFontString(fontSpec);
  }

  const paragraphs = text.split('\n');
  const resultLines = [];

  for (let pIdx = 0; pIdx < paragraphs.length; pIdx++) {
    const para = paragraphs[pIdx];
    if (para === '') {
      resultLines.push('');
      continue;
    }

    const words = para.split(/\s+/);
    let currentLine = '';

    for (let wIdx = 0; wIdx < words.length; wIdx++) {
      const word = words[wIdx];
      if (!word) continue;

      const testLine = currentLine ? `${currentLine} ${word}` : word;
      let testW = 0;
      if (ctx) {
        testW = ctx.measureText(testLine).width;
      } else {
        testW = testLine.length * fontSize * 0.55;
      }

      if (testW <= maxW) {
        currentLine = testLine;
      } else {
        if (currentLine) {
          resultLines.push(currentLine);
          currentLine = '';
        }

        let wordW = 0;
        if (ctx) {
          wordW = ctx.measureText(word).width;
        } else {
          wordW = word.length * fontSize * 0.55;
        }

        // Handle single words wider than the entire container
        if (wordW > maxW) {
          let chunk = '';
          for (let i = 0; i < word.length; i++) {
            const ch = word[i];
            const testChunk = chunk + ch;
            let chunkW = ctx ? ctx.measureText(testChunk).width : testChunk.length * fontSize * 0.55;
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

    if (currentLine !== '') {
      resultLines.push(currentLine);
    }
  }

  const totalHeight = Math.max(resultLines.length * lineHeight, lineHeight);
  return {
    lines: resultLines,
    height: totalHeight,
    lineHeight
  };
}

/**
 * Splits a paragraph cleanly at an available height constraint into fitText and overflowText.
 *
 * @param {string} text - Raw text to partition
 * @param {Object} fontSpec - Font configuration
 * @param {number} containerWidth - Max width constraint
 * @param {number} availableHeight - Maximum vertical height available on current page
 * @returns {{
 *   fitText: string,
 *   overflowText: string,
 *   fitHeight: number,
 *   overflowHeight: number,
 *   fitLines: string[],
 *   overflowLines: string[],
 *   didSplit: boolean,
 *   lineHeight: number
 * }}
 */
export function splitParagraphAtHeight(text, fontSpec = {}, containerWidth = 500, availableHeight = 400) {
  const { lines, lineHeight } = computeLines(text, fontSpec, containerWidth);

  // If entire text fits within available height
  const totalHeight = lines.length * lineHeight;
  if (totalHeight <= availableHeight || availableHeight <= 0) {
    if (availableHeight <= 0) {
      // Entire block overflows
      return {
        fitText: '',
        overflowText: text,
        fitHeight: 0,
        overflowHeight: totalHeight,
        fitLines: [],
        overflowLines: lines,
        didSplit: true,
        lineHeight
      };
    }

    return {
      fitText: text,
      overflowText: '',
      fitHeight: totalHeight,
      overflowHeight: 0,
      fitLines: lines,
      overflowLines: [],
      didSplit: false,
      lineHeight
    };
  }

  const maxFitLines = Math.max(0, Math.floor(availableHeight / lineHeight));
  if (maxFitLines === 0) {
    return {
      fitText: '',
      overflowText: text,
      fitHeight: 0,
      overflowHeight: totalHeight,
      fitLines: [],
      overflowLines: lines,
      didSplit: true,
      lineHeight
    };
  }

  const fitSlice = lines.slice(0, maxFitLines);
  const overflowSlice = lines.slice(maxFitLines);

  return {
    fitText: fitSlice.join('\n'),
    overflowText: overflowSlice.join('\n'),
    fitHeight: fitSlice.length * lineHeight,
    overflowHeight: overflowSlice.length * lineHeight,
    fitLines: fitSlice,
    overflowLines: overflowSlice,
    didSplit: true,
    lineHeight
  };
}

export default {
  formatFontString,
  measureTextWidth,
  computeLines,
  splitParagraphAtHeight
};
