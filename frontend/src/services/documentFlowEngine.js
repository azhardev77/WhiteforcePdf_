/**
 * documentFlowEngine.js
 * 
 * Cascading Multi-Page Reflow & Pagination Engine for PDF Visual Editor.
 * Enforces strict fixed page boundaries (no canvas stretching), calculates
 * downstream sibling shifts, splits overflowing paragraphs via textLayoutEngine,
 * and paginates blocks across pages cleanly.
 */

import { splitParagraphAtHeight, computeLines } from './textLayoutEngine';

export const PAGE_TOP_MARGIN = 40; // points
export const PAGE_BOTTOM_MARGIN_OFFSET = 40; // points

/**
 * Gets the maximum allowed Y coordinate for a page of native height.
 *
 * @param {number} pageNativeHeight
 * @returns {number}
 */
export function getPageBottomMargin(pageNativeHeight = 792) {
  return pageNativeHeight - PAGE_BOTTOM_MARGIN_OFFSET;
}

/**
 * Clones a block object deeply while preserving function-safe metadata.
 */
export function cloneBlock(block) {
  if (!block) return null;
  return JSON.parse(JSON.stringify(block));
}

/**
 * Captures a complete flow snapshot across all pages.
 */
export function captureFlowSnapshot(pageBlocksMap, numPages) {
  return {
    pageBlocksMap: JSON.parse(JSON.stringify(pageBlocksMap || {})),
    numPages: numPages || 1,
    timestamp: Date.now()
  };
}

/**
 * Performs cascading reflow on a given page and cascades into subsequent pages.
 * Implements a dynamic Two-Tier overflow strategy without hardcoded page limits:
 * - Tier 1 (Auto-Fit Containment): When text exceeds its bounding box or page boundaries,
 *   attempts in-place auto-shrink down to min 8.5pt font size and min 1.1 line-height.
 * - Tier 2 (Controlled Spillover): Only triggers onAddBlankPage if the document already
 *   has multiple pages, or if the user explicitly inserts a page break / overflows beyond
 *   minimum legible font size.
 * - Downstream siblings shift strictly by NET delta height added, never static pitch.
 * - Drops all split blocks with whitespace-only text to prevent phantom dashed outlines.
 *
 * @param {number} startPageNumber - Page number to begin reflow on (1-indexed)
 * @param {Object} pageBlocksMap - Map of pageNumber -> Array<Block>
 * @param {Function} getPageDims - Function returning { width, height } for a page
 * @param {Object} [options]
 * @param {Function} [options.onAddBlankPage] - Hook invoked when a new page is dynamically needed
 * @param {Function} [options.onRemoveDynamicPage] - Hook invoked when a dynamic page becomes empty
 * @param {number} [options.activeEditingId] - ID of currently active editing block (if any)
 * @param {number} [options.activeDeltaY=0] - Live uncommitted deltaY of active editing block
 * @param {number} [options.totalDocumentPages] - Total parsed pages in original document
 * @param {boolean} [options.hasExplicitPageBreak=false] - Explicit page break signal
 * @returns {{ hasChanged: boolean, pagesAffected: number[], splitInfo: Object|null }}
 */
export function performCascadingReflow(
  startPageNumber,
  pageBlocksMap,
  getPageDims,
  options = {}
) {
  const {
    onAddBlankPage,
    onRemoveDynamicPage,
    activeEditingId = null,
    activeDeltaY = 0
  } = options;

  let hasChanged = false;
  let splitInfo = null;
  const pagesAffected = [];

  let currentPage = startPageNumber;
  const maxPagesToProcess = 10; // Safety guard against infinite cascade
  let iterations = 0;

  // Dynamic multi-page status: true if the document originally had > 1 page,
  // or if start/current page is already beyond page 1
  const parsedTotalPages = options.totalDocumentPages || Math.max(1, Object.keys(pageBlocksMap || {}).length);
  const hasMultiplePages = parsedTotalPages > 1 || currentPage > 1;

  while (iterations < maxPagesToProcess) {
    iterations++;
    const blocks = pageBlocksMap[currentPage] || [];
    if (blocks.length === 0 && currentPage > startPageNumber) {
      break;
    }

    pagesAffected.push(currentPage);
    const dims = getPageDims(currentPage) || { width: 612, height: 792 };
    const maxAllowedY = getPageBottomMargin(dims.height);

    // Filter phantom blocks: strip any split or empty block whose text is empty/whitespace only
    const cleanBlocks = blocks.filter(b => {
      if (!b) return false;
      if (b.isBlankLine) return true;
      const txt = (b.text != null ? b.text : b.originalText) || '';
      const trimmed = txt.trim();
      if ((trimmed.length === 0 || /^[\s\u200B-\u200D\uFEFF]+$/.test(trimmed)) && b.id !== activeEditingId) {
        hasChanged = true;
        return false;
      }
      return true;
    });

    // Sort blocks by original vertical order
    const sorted = [...cleanBlocks].sort((a, b) => {
      const ay = a.originalY != null ? a.originalY : a.y;
      const by = b.originalY != null ? b.originalY : b.y;
      return ay - by;
    });

    const stayingBlocks = [];
    const overflowingBlocks = [];

    // Track cumulative shift from expanded siblings above (NET delta height only)
    let cumulativeShift = 0;

    for (let i = 0; i < sorted.length; i++) {
      const b = sorted[i];

      // If previous blocks already overflowed to next page, all subsequent blocks also overflow
      if (overflowingBlocks.length > 0) {
        overflowingBlocks.push(b);
        continue;
      }

      // Compute effective height of this block
      const isActivelyEditing = b.id === activeEditingId;
      if (isActivelyEditing && options.activeText != null) {
        b.text = options.activeText;
      }

      const isEditedBlock = isActivelyEditing ||
                            Boolean(b.isEdited) ||
                            (b.deltaY != null && b.deltaY > 0) ||
                            (b.text != null && b.originalText != null && b.text.trim() !== b.originalText.trim());

      const text = (b.text != null ? b.text : b.originalText) || '';
      const containerW = b.customWidth || b.width || b.originalWidth || 500;
      const origH = b.originalHeight || 20;
      const origY = b.originalY != null ? b.originalY : (b.y != null ? b.y : PAGE_TOP_MARGIN);
      let targetY = origY + cumulativeShift;

      if (b.isMoved && b.customY != null) {
        targetY = b.customY;
      }

      const baseFontSize = b.originalFontSize || b.fontSize || 12;
      const linePitch = (b.lines && b.lines.length > 1)
        ? (origH / b.lines.length)
        : (baseFontSize * 1.25);
      const fontSpec = {
        fontSize: b.customFontSize || baseFontSize,
        fontFamily: b.fontFamily || 'Helvetica, Arial, sans-serif',
        bold: Boolean(b.bold),
        italic: Boolean(b.italic),
        lineHeight: b.customLineHeight || Math.round(linePitch)
      };

      let bHeight = origH;
      if (isEditedBlock) {
        let computed = computeLines(text, fontSpec, containerW);
        bHeight = computed.height;
        if (isActivelyEditing && activeDeltaY > 0) {
          bHeight = Math.max(computed.height, origH + activeDeltaY);
        } else if (b.deltaY > 0) {
          bHeight = Math.max(computed.height, origH + b.deltaY);
        }
      } else {
        bHeight = b.currentHeight || origH;
      }

      const availableHeightOnPage = Math.max(origH, maxAllowedY - targetY);
      const exceedsPage = (targetY + bHeight) > maxAllowedY;

      // -----------------------------------------------------------------------
      // TIER 1: AUTO-FIT CONTAINMENT
      // -----------------------------------------------------------------------
      // If content exceeds the page's printable area (pageHeight - bottomMargin),
      // calculate overflow and attempt in-place auto-shrink down to min 8.5pt font-size
      // and min 1.1 line-height to fit within the printable page area.
      let fitsInPlace = false;
      let fittedFontSize = fontSpec.fontSize;
      let fittedLineHeight = null;
      let fittedHeight = bHeight;

      if (exceedsPage) {
        const minFontSize = 8.5;
        const minLineHeightMult = 1.1;
        const maxContainmentHeight = availableHeightOnPage;
        const startSize = Math.max(minFontSize, Math.round(baseFontSize * 10) / 10);

        for (let s = startSize; s >= minFontSize; s -= 0.5) {
          // 1. Try standard line-height
          let fs = { ...fontSpec, fontSize: s };
          let c = computeLines(text, fs, containerW);
          if (c.height <= maxContainmentHeight) {
            fitsInPlace = true;
            fittedFontSize = s;
            fittedLineHeight = c.lineHeight;
            fittedHeight = c.height;
            break;
          }
          // 2. Try compact line-height down to min 1.1
          const compactLineHeight = Math.max(Math.round(s * minLineHeightMult), 10);
          fs = { ...fontSpec, fontSize: s, lineHeight: compactLineHeight };
          c = computeLines(text, fs, containerW);
          if (c.height <= maxContainmentHeight) {
            fitsInPlace = true;
            fittedFontSize = s;
            fittedLineHeight = compactLineHeight;
            fittedHeight = c.height;
            break;
          }
        }

        if (fitsInPlace) {
          b.customFontSize = fittedFontSize;
          if (fittedLineHeight != null) {
            b.customLineHeight = fittedLineHeight;
          }
          b.currentHeight = fittedHeight;
          bHeight = fittedHeight;
        }
      } else {
        fitsInPlace = true;
      }

      // -----------------------------------------------------------------------
      // Check if block fits on current page after Tier 1 containment
      // -----------------------------------------------------------------------
      if (targetY + bHeight <= maxAllowedY) {
        // Fits cleanly within printable area on current page
        b.y = targetY;
        b.currentHeight = bHeight;
        stayingBlocks.push(b);

        // Downstream siblings shift strictly by NET delta height added, never static pitch
        if (isEditedBlock && !b.isMoved) {
          const netDelta = Math.max(0, bHeight - origH);
          cumulativeShift += netDelta;
        }
        continue;
      }

      // -----------------------------------------------------------------------
      // TIER 2: CONTROLLED SPILLOVER
      // -----------------------------------------------------------------------
      // Only trigger spillover / onAddBlankPage if:
      // 1. Document already has multiple pages (hasMultiplePages === true), OR
      // 2. User explicitly inserted a page break (\f or options.hasExplicitPageBreak), OR
      // 3. Text overflows beyond minimum legible font size (!fitsInPlace)
      const hasExplicitPageBreak = text.includes('\f') || Boolean(b.hasPageBreak) || Boolean(options.hasExplicitPageBreak);
      const overflowsBeyondMinLegible = !fitsInPlace;
      const canSpillover = hasMultiplePages || hasExplicitPageBreak || overflowsBeyondMinLegible;

      if (canSpillover) {
        const availableHeight = maxAllowedY - targetY;
        const minLineHeight = (fontSpec.fontSize || 12) * 1.1;

        // Try clean paragraph splitting at page boundary
        if (availableHeight >= minLineHeight && text.length > 20) {
          const split = splitParagraphAtHeight(text, fontSpec, containerW, availableHeight);
          const cleanFitText = (split.fitText || '').trim();
          const cleanOverflowText = (split.overflowText || '').trim();

          // Drop split blocks where .trim().length === 0 to prevent phantom dashed outlines
          if (split.didSplit && cleanFitText.length > 0 && cleanOverflowText.length > 0) {
            b.y = targetY;
            b.currentHeight = split.fitHeight;
            b.text = split.fitText;
            b.isSplit = true;
            stayingBlocks.push(b);

            const overflowChildId = b.id.includes('_overflow') ? b.id : `${b.id}_overflow_${currentPage + 1}`;
            b.splitChildId = overflowChildId;

            if (isActivelyEditing) {
              splitInfo = {
                parentId: b.id,
                fitText: split.fitText,
                fitHeight: split.fitHeight,
                overflowChildId,
                overflowText: split.overflowText,
                overflowHeight: split.overflowHeight
              };
            }

            const overflowBlock = {
              ...cloneBlock(b),
              id: overflowChildId,
              text: split.overflowText,
              originalText: '',
              pageNumber: currentPage + 1,
              originalPageNumber: b.originalPageNumber || currentPage,
              x: b.x,
              originalX: b.originalX,
              y: PAGE_TOP_MARGIN,
              width: b.width,
              originalWidth: b.originalWidth,
              height: split.overflowHeight,
              currentHeight: split.overflowHeight,
              originalHeight: 0,
              deltaY: 0,
              isSplit: false,
              isOverflowChild: true,
              splitParentId: b.id,
              isEdited: true
            };

            overflowingBlocks.push(overflowBlock);
            hasChanged = true;
            continue;
          }
        }

        // If block cannot be split, migrate entire block if non-empty
        if (text.trim().length > 0) {
          overflowingBlocks.push(b);
          hasChanged = true;
        }
      } else {
        // Strict boundary containment: clamp within physical page bottom
        const availableHeight = Math.max(origH, maxAllowedY - origY);
        b.y = Math.min(targetY, maxAllowedY - Math.min(bHeight, availableHeight));
        b.currentHeight = Math.min(bHeight, Math.max(14, maxAllowedY - b.y));
        stayingBlocks.push(b);
      }
    }

    // -------------------------------------------------------------------------
    // Handle Spillover to Next Page
    // -------------------------------------------------------------------------
    const validOverflowing = overflowingBlocks.filter(ob => {
      const t = (ob.text != null ? ob.text : ob.originalText) || '';
      return t.trim().length > 0 || ob.isBlankLine;
    });

    if (validOverflowing.length > 0) {
      const nextPage = currentPage + 1;

      // Tier 2: Only trigger onAddBlankPage if dynamically needed
      if (typeof onAddBlankPage === 'function') {
        onAddBlankPage(nextPage, dims.width, dims.height);
      }

      pageBlocksMap[currentPage] = stayingBlocks;

      if (!pageBlocksMap[nextPage]) {
        pageBlocksMap[nextPage] = [];
      }

      // Existing blocks on next page (excluding any duplicates of overflowingBlocks)
      const existingOnNext = (pageBlocksMap[nextPage] || []).filter(
        item => !validOverflowing.some(ob => ob.id === item.id)
      );

      // Position overflowing blocks starting at PAGE_TOP_MARGIN
      let nextRunningY = PAGE_TOP_MARGIN;
      for (const b of validOverflowing) {
        b.originalPageNumber = b.originalPageNumber || currentPage;
        b.pageNumber = nextPage;
        b.y = nextRunningY;
        const bH = b.currentHeight || b.originalHeight || 20;
        nextRunningY += bH + 8;
      }

      // Shift existing blocks on next page downwards by NET shift
      const shiftForExisting = nextRunningY - PAGE_TOP_MARGIN;
      for (const ex of existingOnNext) {
        const origY = ex.originalY != null ? ex.originalY : ex.y;
        ex.y = origY + shiftForExisting;
      }

      pageBlocksMap[nextPage] = [...validOverflowing, ...existingOnNext];
      hasChanged = true;

      // Cascade reflow to next page
      currentPage = nextPage;
    } else {
      pageBlocksMap[currentPage] = stayingBlocks;

      // Check if any blocks previously spilled to next page can now return
      const nextPage = currentPage + 1;
      const nextBlocks = pageBlocksMap[nextPage];

      if (nextBlocks && nextBlocks.length > 0) {
        const returnCandidates = nextBlocks.filter(
          b => {
            const t = (b.text != null ? b.text : b.originalText) || '';
            return (t.trim().length > 0 || b.isBlankLine) && (b.originalPageNumber === currentPage || b.isOverflowChild);
          }
        );

        if (returnCandidates.length > 0) {
          let maxStayingBottom = PAGE_TOP_MARGIN;
          for (const b of stayingBlocks) {
            const bH = b.currentHeight || b.originalHeight || 20;
            maxStayingBottom = Math.max(maxStayingBottom, b.y + bH);
          }

          const returned = [];
          for (const cand of returnCandidates) {
            const candH = cand.currentHeight || cand.originalHeight || 20;
            if (maxStayingBottom + 8 + candH <= maxAllowedY) {
              // Can return!
              cand.pageNumber = currentPage;
              cand.y = maxStayingBottom + 8;
              maxStayingBottom = cand.y + candH;
              returned.push(cand);

              // If candidate was a split overflow child, merge back into parent
              if (cand.isOverflowChild && cand.splitParentId) {
                const parent = stayingBlocks.find(p => p.id === cand.splitParentId);
                if (parent) {
                  parent.text = `${parent.text}\n${cand.text}`;
                  parent.isSplit = false;
                  parent.splitChildId = null;
                  const fontSpec = {
                    fontSize: parent.customFontSize || parent.fontSize || 12,
                    fontFamily: parent.fontFamily || 'Helvetica, Arial, sans-serif',
                    bold: Boolean(parent.bold),
                    italic: Boolean(parent.italic)
                  };
                  const computed = computeLines(parent.text, fontSpec, parent.width || parent.originalWidth || 500);
                  parent.currentHeight = computed.height;
                  // Don't add cand to returned since it merged back
                  returned.pop();
                  continue;
                }
              }
            } else {
              break;
            }
          }

          if (returned.length > 0) {
            pageBlocksMap[currentPage] = [...stayingBlocks, ...returned];
            pageBlocksMap[nextPage] = nextBlocks.filter(
              b => !returned.some(rb => rb.id === b.id)
            );
            hasChanged = true;
          }

          // If next page is completely empty and was dynamic, trigger cleanup
          if (pageBlocksMap[nextPage].length === 0 && typeof onRemoveDynamicPage === 'function') {
            onRemoveDynamicPage(nextPage);
          }
        }
      }

      break;
    }
  }

  return { hasChanged, pagesAffected, splitInfo };
}

export default {
  PAGE_TOP_MARGIN,
  PAGE_BOTTOM_MARGIN_OFFSET,
  getPageBottomMargin,
  cloneBlock,
  captureFlowSnapshot,
  performCascadingReflow
};
