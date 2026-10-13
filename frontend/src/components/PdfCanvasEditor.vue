<template>
  <div class="pdf-canvas-editor-container" ref="containerRef">
    <!-- Pages Scroller -->
    <div class="pages-wrapper">
      <div
        v-for="pageIndex in numPages"
        :key="pageIndex"
        :id="`pdf-page-${pageIndex}`"
        class="pdf-page-card"
        :style="getPageCardStyle(pageIndex)"
      >
        <!-- Page Header Label & Orientation Controls -->
        <div class="page-meta-header">
          <div class="page-meta-left">
            <span class="page-label">Page {{ pageIndex }} of {{ numPages }}</span>
            <span v-if="(userRotations[pageIndex] || 0) !== 0" class="page-rotation-badge">
              {{ userRotations[pageIndex] }}°
            </span>
          </div>

          <div class="page-header-actions">
            <span v-if="getPageEditCount(pageIndex) > 0" class="page-edit-badge">
              {{ getPageEditCount(pageIndex) }} {{ getPageEditCount(pageIndex) === 1 ? 'edit' : 'edits' }}
            </span>

            <button
              type="button"
              class="btn-page-action"
              title="Rotate page 90° clockwise"
              @click="rotatePage(pageIndex, 90)"
            >
              <svg class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67"/>
              </svg>
              <span>Rotate 90°</span>
            </button>

            <button
              type="button"
              class="btn-page-action"
              title="Flip page 180° (Fix inverted scan)"
              @click="rotatePage(pageIndex, 180)"
            >
              <svg class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M7 16V4m0 0L3 8m4-4l4 4m6 4v12m0 0l4-4m-4 4l-4-4"/>
              </svg>
              <span>Flip 180°</span>
            </button>
          </div>
        </div>

        <!-- Canvas and Interactive Overlay Frame -->
        <div
          class="page-stage"
          :style="getPageStageStyle(pageIndex)"
        >
          <!-- 1. 100% Pixel-Perfect PDF.js Canvas -->
          <canvas
            :ref="el => setCanvasRef(pageIndex, el)"
            class="pdf-page-canvas"
          />

          <!-- 2. Interactive Redaction & Text Overlay Layer -->
          <div
            v-if="isPageRendered(pageIndex)"
            class="pdf-page-overlay"
            :style="getPageOverlayStyle(pageIndex)"
          >
            <div
              v-for="item in getPageItems(pageIndex)"
              :key="item.id"
              class="overlay-text-box"
              :class="{
                'is-editing': editingId === item.id,
                'is-edited': isItemEdited(item.id),
                'modified-item': isItemEdited(item.id),
                'is-transparent-edit': isItemTransparent(item.id),
                'is-hovered': hoveredId === item.id && editingId !== item.id,
                'is-active': documentStore.activeBlockId === item.id
              }"
              :style="getItemBoxStyle(item)"
              @mouseenter="hoveredId = item.id"
              @mouseleave="hoveredId = null"
              @click.stop="startEditing(item)"
            >
              <!-- Editing Mode: Active Inline Input with Floating Mode Toggle -->
              <template v-if="editingId === item.id">
                <div class="edit-popover-toolbar" @mousedown.stop>
                  <label
                    v-if="isPureBlankText(item.originalText)"
                    class="toggle-mode-btn"
                    :class="{ 'is-active': editTransparent }"
                    title="Toggle: When checked, text is printed directly on top of dotted lines / underlines without whiteout"
                  >
                    <input
                      type="checkbox"
                      v-model="editTransparent"
                      class="hidden-checkbox"
                    />
                    <span class="toggle-icon">{{ editTransparent ? '☑' : '☐' }}</span>
                    <span>{{ editTransparent ? 'Transparent (Overprint on dots/lines)' : 'Whiteout (Erase background)' }}</span>
                  </label>
                  <span v-else class="popover-mode-badge" title="Alphanumeric text is whited out to prevent ghosting">
                    Whiteout Redaction
                  </span>
                  <button type="button" class="btn-popover-done" @click.stop="commitEdit(item)">Done</button>
                </div>

                <input
                  ref="activeInputRef"
                  type="text"
                  v-model="editText"
                  class="inline-editor-input"
                  :class="{ 'is-transparent-input': editTransparent }"
                  :style="getEditingInputStyle(item)"
                  @keydown.enter.prevent="commitEdit(item)"
                  @keydown.esc.prevent="cancelEdit"
                  @blur="onInputBlur(item)"
                />
              </template>

              <!-- Display Mode: Modified (whiteout OR transparent overprint) OR Original (transparent overlay) -->
              <template v-else>
                <div
                  v-if="isItemEdited(item.id)"
                  class="edited-text-display modified-item"
                  :class="{ 'is-transparent-display': isItemTransparent(item.id) }"
                  :style="getDisplayTextStyle(item)"
                  @click.stop="startEditing(item)"
                  @dblclick.stop="revertItem(item)"
                  title="Click to re-edit, double-click to revert"
                >
                  <span class="edited-text-content">{{ getItemText(item) }}</span>
                  <button
                    type="button"
                    class="btn-revert-item"
                    title="Revert to original text (1 click)"
                    @click.stop="revertItem(item)"
                  >
                    &times;
                  </button>
                </div>

                <div v-else class="ghost-text-display">
                  <span class="hover-hint">
                    {{ (item.isTransparent || isPureBlankText(item.originalText)) ? 'Fill on dots' : 'Click to edit' }}
                  </span>
                </div>
              </template>
            </div>
          </div>

          <!-- Page Loading Spinner -->
          <div v-if="!isPageRendered(pageIndex)" class="page-loading-mask">
            <div class="spinner-sm" />
            <span>Rendering Page {{ pageIndex }}...</span>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted } from 'vue';
import * as pdfjsLib from 'pdfjs-dist';
import pdfWorker from 'pdfjs-dist/build/pdf.worker.mjs?url';
import { useDocumentStore } from '@/stores/document';

// Configure pdfjs worker
pdfjsLib.GlobalWorkerOptions.workerSrc = pdfWorker;

const props = defineProps({
  documentModel: {
    type: Object,
    default: null
  },
  zoom: {
    type: Number,
    default: 1.25
  }
});

const emit = defineEmits(['select-item', 'page-rendered']);

const documentStore = useDocumentStore();

const containerRef = ref(null);
const canvasRefs = ref({});
const activeInputRef = ref(null);

const numPages = ref(0);
const pageViewports = ref({}); // pageIndex -> { width, height, rotation, rawWidth, rawHeight }
const userRotations = ref({}); // pageIndex -> rotation in degrees (0, 90, 180, 270)
const renderedPages = ref(new Set());
const editingId = ref(null);
const editText = ref('');
const editTransparent = ref(false);
const hoveredId = ref(null);

const PURE_BLANK_REGEX = /^[.\s_\-–—]+$/;

function isPureBlankText(text) {
  if (!text) return true;
  const trimmed = String(text).trim();
  if (trimmed.length === 0) return true;
  return PURE_BLANK_REGEX.test(trimmed);
}

function isDotsOrLinesText(text) {
  return isPureBlankText(text);
}

let measurementCanvas = null;
let measurementCtx = null;

function measureTextWidth(text, fontSize, fontFamily, bold = false) {
  if (!text) return 0;
  if (typeof document === 'undefined') {
    return text.length * fontSize * 0.55;
  }
  if (!measurementCanvas) {
    measurementCanvas = document.createElement('canvas');
    measurementCtx = measurementCanvas.getContext('2d');
  }
  if (!measurementCtx) {
    return text.length * fontSize * 0.55;
  }
  measurementCtx.font = `${bold ? 'bold ' : ''}${fontSize}px ${fontFamily || 'Helvetica, Arial, sans-serif'}`;
  return measurementCtx.measureText(text).width;
}

/**
 * Splits lines with embedded or trailing fill-in blanks into discrete sub-items:
 * 1. Static label (e.g. "you against the said M/s. ") -> standard whiteout item
 * 2. Fillable blank (e.g. "-----------------") -> pure blank, auto-transparent form fill
 */
function partitionLineItem(baseItem) {
  const text = baseItem.text != null ? String(baseItem.text).replace(/\n$/, '') : '';
  if (!text.trim()) return [];

  // If already 100% pure blank (e.g. --------------------), keep as single pure blank item
  if (isPureBlankText(text)) {
    return [{
      ...baseItem,
      text: text,
      originalText: text,
      isTransparent: true,
      isPureBlank: true,
      isStaticLabel: false
    }];
  }

  // Look for 3 or more consecutive dots, dashes, or underscores
  const blankRegex = /([._\-–—]{3,}(?:[ ]+[._\-–—]{2,})*)/g;
  const matches = [...text.matchAll(blankRegex)];

  // If no fillable blank pattern found, keep as single standard text line
  if (matches.length === 0) {
    return [{
      ...baseItem,
      text: text,
      originalText: text,
      isTransparent: false,
      isPureBlank: false,
      isStaticLabel: false
    }];
  }

  // Mixed line containing both label and blank(s) -> partition into sub-items
  const segments = [];
  let lastIndex = 0;

  for (const match of matches) {
    const matchIndex = match.index;
    const matchText = match[0];

    // Leading text/label segment
    if (matchIndex > lastIndex) {
      const labelText = text.substring(lastIndex, matchIndex);
      if (labelText.length > 0) {
        segments.push({
          text: labelText,
          isBlank: false
        });
      }
    }

    // Blank segment
    segments.push({
      text: matchText,
      isBlank: true
    });

    lastIndex = matchIndex + matchText.length;
  }

  // Trailing text segment
  if (lastIndex < text.length) {
    const tailText = text.substring(lastIndex);
    if (tailText.length > 0) {
      segments.push({
        text: tailText,
        isBlank: false
      });
    }
  }

  if (segments.length <= 1) {
    const seg = segments[0] || { text, isBlank: false };
    return [{
      ...baseItem,
      text: seg.text,
      originalText: seg.text,
      isTransparent: seg.isBlank,
      isPureBlank: seg.isBlank,
      isStaticLabel: !seg.isBlank
    }];
  }

  // Calculate proportional bounding boxes for each segment
  const [origX, origY, origW, origH] = baseItem.bbox;
  const fontSize = baseItem.fontSize || 12;
  const fontFamily = baseItem.fontFamily || 'Helvetica, Arial, sans-serif';
  const bold = Boolean(baseItem.style?.bold);

  const measuredWidths = segments.map(seg => {
    return Math.max(measureTextWidth(seg.text, fontSize, fontFamily, bold), seg.text.length * fontSize * 0.45);
  });
  const totalMeasuredWidth = measuredWidths.reduce((sum, w) => sum + w, 0);
  const scaleRatio = totalMeasuredWidth > 0 ? (origW / totalMeasuredWidth) : (1 / segments.length);

  const subItems = [];
  let currentX = origX;

  segments.forEach((seg, sIdx) => {
    const isLast = (sIdx === segments.length - 1);
    let segW;
    if (isLast) {
      segW = Math.max(origX + origW - currentX, 8);
    } else {
      segW = Math.max(measuredWidths[sIdx] * scaleRatio, 8);
    }

    const subId = `${baseItem.id}-${seg.isBlank ? 'blank' : 'lbl'}-${sIdx}`;
    subItems.push({
      ...baseItem,
      id: subId,
      bbox: [currentX, origY, segW, origH],
      text: seg.text,
      originalText: seg.text,
      isTransparent: seg.isBlank,
      isPureBlank: seg.isBlank,
      isStaticLabel: !seg.isBlank
    });

    currentX += segW;
  });

  return subItems;
}

let currentPdfDoc = null;
const renderTasks = {};

function setCanvasRef(pageIndex, el) {
  if (el) {
    canvasRefs.value[pageIndex] = el;
  }
}

// Flatten DocumentModel blocks to individual Line-level interactive items
function flattenPageItems(pageModel) {
  if (!pageModel) return [];
  const pageNum = pageModel.pageNumber || 1;
  const items = [];

  const processBlock = (block, defaultPrefix) => {
    if (!block) return;
    const blockId = block.id || defaultPrefix;

    // 1. Explicit lines if provided
    if (block.lines && Array.isArray(block.lines) && block.lines.length > 0) {
      block.lines.forEach((line, lIdx) => {
        if (!line) return;
        const lineText = line.text != null ? String(line.text).replace(/\n$/, '').trim() : '';
        const lineBbox = line.bbox;
        if (!lineText || !lineBbox || lineBbox.length < 4 || lineBbox[2] <= 0 || lineBbox[3] <= 0) return;

        const primaryRun = line.runs?.[0];
        const style = primaryRun?.style || block.style || {
          fontFamily: 'Helvetica, Arial, sans-serif',
          fontSize: line.primaryFontSize || 12.0,
          bold: Boolean(line.isBold),
          italic: false,
          color: '#000000'
        };

        const fontSize = style.fontSize || line.primaryFontSize || 12.0;

        const rawItem = {
          id: `${blockId}-line-${lIdx}`,
          pageNumber: pageNum,
          bbox: [...lineBbox],
          text: lineText,
          originalText: lineText,
          style: style,
          fontSize: fontSize,
          fontFamily: style.fontFamily || 'Helvetica, Arial, sans-serif',
          color: style.color || '#000000',
          isTransparent: isPureBlankText(lineText)
        };
        items.push(...partitionLineItem(rawItem));
      });
      return;
    }

    // 2. Cluster block.runs into tight single-line units
    if (block.runs && Array.isArray(block.runs) && block.runs.length > 0) {
      const validRuns = block.runs.filter(r => {
        if (!r) return false;
        const t = r.text != null ? String(r.text).replace(/\n$/, '') : '';
        if (!t.trim()) return false;
        const b = r.bbox;
        return b && b.length === 4 && b[2] > 0 && b[3] > 0;
      });

      if (validRuns.length === 0) return;

      // Sort runs primarily by Y (baseline vicinity), secondarily by X (reading order)
      const sortedRuns = [...validRuns].sort((a, b) => {
        const diffY = a.bbox[1] - b.bbox[1];
        if (Math.abs(diffY) > 2.5) return diffY;
        return a.bbox[0] - b.bbox[0];
      });

      const lineClusters = [];
      sortedRuns.forEach((run) => {
        const runY = run.bbox[1];
        const runH = run.bbox[3];

        let matchedLine = null;
        for (const cluster of lineClusters) {
          const refY = cluster.avgY;
          const refH = cluster.avgH;
          const verticalTolerance = Math.max(Math.min(runH, refH) * 0.45, 3.5);

          if (Math.abs(runY - refY) <= verticalTolerance) {
            matchedLine = cluster;
            break;
          }
        }

        if (matchedLine) {
          matchedLine.runs.push(run);
          matchedLine.avgY = matchedLine.runs.reduce((sum, r) => sum + r.bbox[1], 0) / matchedLine.runs.length;
          matchedLine.avgH = matchedLine.runs.reduce((sum, r) => sum + r.bbox[3], 0) / matchedLine.runs.length;
        } else {
          lineClusters.push({
            runs: [run],
            avgY: runY,
            avgH: runH
          });
        }
      });

      // Sort lines strictly from top to bottom
      lineClusters.sort((a, b) => a.avgY - b.avgY);

      lineClusters.forEach((cluster, lIdx) => {
        // Sort runs within this line horizontally from left to right
        cluster.runs.sort((a, b) => a.bbox[0] - b.bbox[0]);

        let minX = Infinity;
        let minY = Infinity;
        let maxX = -Infinity;
        let maxY = -Infinity;

        // Restore whitespace between adjacent runs
        let fullText = '';
        cluster.runs.forEach((run, rIdx) => {
          const rText = String(run.text).replace(/\n$/, '');
          const b = run.bbox;

          minX = Math.min(minX, b[0]);
          minY = Math.min(minY, b[1]);
          maxX = Math.max(maxX, b[0] + b[2]);
          maxY = Math.max(maxY, b[1] + b[3]);

          if (rIdx > 0 && fullText.length > 0) {
            const prevRun = cluster.runs[rIdx - 1];
            const gap = b[0] - (prevRun.bbox[0] + prevRun.bbox[2]);
            const prevText = prevRun.text || '';

            const needsSpace =
              !fullText.endsWith(' ') &&
              !rText.startsWith(' ') &&
              !/^[.,;:?!%)\]}]/.test(rText) &&
              !/[(\[{]$/.test(prevText) &&
              (gap >= 0.8 || (gap >= -1.0 && /\w$/.test(fullText) && /^\w/.test(rText)));

            if (needsSpace) {
              fullText += ' ';
            }
          }
          fullText += rText;
        });

        const lineW = Math.max(0, maxX - minX);
        const lineH = Math.max(0, maxY - minY);
        if (!fullText.trim() || lineW <= 0 || lineH <= 0) return;

        // Extract dominant style from the longest run in the line
        const primaryRun = cluster.runs.reduce((longest, r) =>
          ((r.text?.length || 0) > (longest.text?.length || 0) ? r : longest), cluster.runs[0]);
        const style = primaryRun?.style || block.style || {
          fontFamily: 'Helvetica, Arial, sans-serif',
          fontSize: 12.0,
          bold: false,
          italic: false,
          color: '#000000'
        };

        const fontSize = style.fontSize || 12.0;

        const rawItem = {
          id: `${blockId}-line-${lIdx}`,
          pageNumber: pageNum,
          bbox: [minX, minY, lineW, lineH],
          text: fullText,
          originalText: fullText,
          style: style,
          fontSize: fontSize,
          fontFamily: style.fontFamily || 'Helvetica, Arial, sans-serif',
          color: style.color || '#000000',
          isTransparent: isPureBlankText(fullText)
        };
        items.push(...partitionLineItem(rawItem));
      });
      return;
    }

    // 3. Fallback for blocks with only block.text and block.bbox
    if (block.text && block.bbox && block.bbox.length === 4 && block.bbox[2] > 0 && block.bbox[3] > 0) {
      const rawText = String(block.text).replace(/\r\n/g, '\n').replace(/\r/g, '\n');
      const lines = rawText.split('\n').filter(l => l.trim().length > 0);
      if (lines.length === 0) return;

      const totalH = block.bbox[3];
      const lineH = totalH / lines.length;

      lines.forEach((lineText, lIdx) => {
        const lineY = block.bbox[1] + (lIdx * lineH);
        const style = block.style || {
          fontFamily: 'Helvetica, Arial, sans-serif',
          fontSize: 12.0,
          bold: false,
          italic: false,
          color: '#000000'
        };
        const fontSize = style.fontSize || 12.0;

        const rawItem = {
          id: `${blockId}-line-${lIdx}`,
          pageNumber: pageNum,
          bbox: [block.bbox[0], lineY, block.bbox[2], lineH],
          text: lineText,
          originalText: lineText,
          style: style,
          fontSize: fontSize,
          fontFamily: style.fontFamily || 'Helvetica, Arial, sans-serif',
          color: style.color || '#000000',
          isTransparent: isPureBlankText(lineText)
        };
        items.push(...partitionLineItem(rawItem));
      });
    }
  };

  if (pageModel.blocks && Array.isArray(pageModel.blocks)) {
    pageModel.blocks.forEach((block, bIdx) => {
      if (!block) return;
      if (block.type === 'HEADING' || block.type === 'PARAGRAPH') {
        processBlock(block, `b-${bIdx}`);
      } else if (block.type === 'TABLE' && block.cells && Array.isArray(block.cells)) {
        block.cells.forEach((row, rIdx) => {
          if (!Array.isArray(row)) return;
          row.forEach((cell, cIdx) => {
            if (cell) {
              processBlock(cell, `tbl-${bIdx}-c-${rIdx}-${cIdx}`);
            }
          });
        });
      }
    });
  }

  return items;
}

// Extract all bounding-box text items from DocumentModel for a specific page
const pageItemsMap = computed(() => {
  const map = {};
  if (!props.documentModel || !props.documentModel.pages) return map;

  props.documentModel.pages.forEach((page, pIdx) => {
    const pageNum = page.pageNumber || (pIdx + 1);
    map[pageNum] = flattenPageItems(page);
  });

  return map;
});

function getPageItems(pageIndex) {
  return pageItemsMap.value[pageIndex] || [];
}

function getPageEditCount(pageIndex) {
  return documentStore.edits.filter(e => e.pageNumber === pageIndex).length;
}

function isPageRendered(pageIndex) {
  return renderedPages.value.has(pageIndex);
}

function isItemEdited(itemId) {
  return Boolean(documentStore.getEdit(itemId));
}

function isItemTransparent(itemId) {
  const edit = documentStore.getEdit(itemId);
  if (!edit) return false;
  const isPureBlank = isPureBlankText(edit.originalText);
  return Boolean(isPureBlank && edit.transparentBackground);
}

function getItemText(item) {
  const edit = documentStore.getEdit(item.id);
  if (edit) {
    return edit.newText != null ? edit.newText : (item.text || item.originalText);
  }
  return item.text || item.originalText;
}

function getPageDimensions(pageNumber) {
  const vp = pageViewports.value[pageNumber];
  if (vp && vp.rawWidth && vp.rawHeight) {
    return { width: vp.rawWidth, height: vp.rawHeight };
  }
  if (props.documentModel && props.documentModel.pages) {
    const page = props.documentModel.pages.find(p => (p.pageNumber || 0) === pageNumber) 
      || props.documentModel.pages[pageNumber - 1];
    if (page && page.width && page.height) {
      return { width: page.width, height: page.height };
    }
  }
  return { width: 595.28, height: 841.89 };
}

function getRotatedBbox(bbox, pageNumber) {
  const rot = (userRotations.value[pageNumber] || 0) % 360;
  if (rot === 0) return [...bbox];

  const { width: pw, height: ph } = getPageDimensions(pageNumber);
  const [x, y, w, h] = bbox;

  if (rot === 180) {
    return [pw - x - w, ph - y - h, w, h];
  } else if (rot === 90) {
    return [ph - y - h, x, h, w];
  } else if (rot === 270) {
    return [y, pw - x - w, h, w];
  }
  return [...bbox];
}

function getItemBoxStyle(item) {
  const scale = props.zoom;
  const bbox = getRotatedBbox(item.bbox, item.pageNumber);
  const leftPx = Math.round(bbox[0] * scale);
  const topPx = Math.round(bbox[1] * scale);
  const widthPx = Math.max(Math.round(bbox[2] * scale), 16);
  const heightPx = Math.max(Math.round(bbox[3] * scale), 12);

  return {
    left: `${leftPx}px`,
    top: `${topPx}px`,
    width: `${widthPx}px`,
    height: `${heightPx}px`,
    maxWidth: `calc(100% - ${leftPx}px)`
  };
}

function getEditingInputStyle(item) {
  const scale = props.zoom;
  const style = item.style || {};
  const baseFontSize = (style.fontSize || item.fontSize || 12.0) * scale;
  const bold = Boolean(style.bold);
  const italic = Boolean(style.italic);
  const fontFamily = style.fontFamily || item.fontFamily || 'Helvetica, Arial, sans-serif';

  // Dynamic font scaling to clamp width within original bounding box
  const currentText = editText.value || '';
  const bbox = getRotatedBbox(item.bbox, item.pageNumber);
  const availableWidth = Math.max((bbox[2] * scale) - 6, 16);

  let effectiveFontSize = baseFontSize;
  if (currentText.length > 0) {
    const textWidth = measureTextWidth(currentText, baseFontSize, fontFamily, bold);
    if (textWidth > availableWidth && availableWidth > 20) {
      const scaleFactor = availableWidth / textWidth;
      // Auto-scale font down, clamping at a readable minimum
      effectiveFontSize = Math.max(baseFontSize * scaleFactor, 8.0);
    }
  }

  return {
    fontSize: `${effectiveFontSize}px`,
    fontWeight: bold ? '700' : '400',
    fontStyle: italic ? 'italic' : 'normal',
    color: style.color || item.color || '#000000',
    fontFamily: fontFamily
  };
}

function getDisplayTextStyle(item) {
  const scale = props.zoom;
  const edit = documentStore.getEdit(item.id);
  const style = (edit && edit.style) ? edit.style : item.style || {};
  const baseFontSize = (style.fontSize || item.fontSize || 12.0) * scale;
  const bold = Boolean(style.bold);
  const italic = Boolean(style.italic);
  const fontFamily = style.fontFamily || item.fontFamily || 'Helvetica, Arial, sans-serif';

  const currentText = getItemText(item) || '';
  const bbox = getRotatedBbox(item.bbox, item.pageNumber);
  const availableWidth = Math.max((bbox[2] * scale) - 4, 16);

  let effectiveFontSize = baseFontSize;
  if (currentText.length > 0) {
    const textWidth = measureTextWidth(currentText, baseFontSize, fontFamily, bold);
    if (textWidth > availableWidth && availableWidth > 20) {
      const scaleFactor = availableWidth / textWidth;
      effectiveFontSize = Math.max(baseFontSize * scaleFactor, 8.0);
    }
  }

  return {
    fontSize: `${effectiveFontSize}px`,
    fontWeight: bold ? '700' : '400',
    fontStyle: italic ? 'italic' : 'normal',
    color: style.color || item.color || '#000000',
    fontFamily: fontFamily
  };
}

function getPageCardStyle(pageIndex) {
  const vp = pageViewports.value[pageIndex];
  if (!vp) return {};
  return {
    width: `${Math.ceil(vp.width)}px`
  };
}

function getPageStageStyle(pageIndex) {
  const vp = pageViewports.value[pageIndex];
  if (!vp) return {};
  return {
    width: `${Math.ceil(vp.width)}px`,
    height: `${Math.ceil(vp.height)}px`
  };
}

function getPageOverlayStyle(pageIndex) {
  const vp = pageViewports.value[pageIndex];
  if (!vp) return {};
  return {
    width: `${Math.ceil(vp.width)}px`,
    height: `${Math.ceil(vp.height)}px`
  };
}

let blurTimer = null;

function commitCurrentEdit() {
  if (!editingId.value) return;
  const currentId = editingId.value;
  for (const pageItems of Object.values(pageItemsMap.value)) {
    const it = pageItems.find(i => i.id === currentId);
    if (it) {
      commitEdit(it);
      return;
    }
  }
  editingId.value = null;
}

function startEditing(item) {
  // Clear any pending blur timeout so the editor does not prematurely close
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }

  // If already editing this exact item, keep editing
  if (editingId.value === item.id) return;

  // If another item was open, commit it first
  if (editingId.value) {
    commitCurrentEdit();
  }

  const rawText = item.text || item.originalText || '';
  const isPureBlank = isPureBlankText(rawText);
  const existingEdit = documentStore.getEdit(item.id);

  editingId.value = item.id;
  if (existingEdit) {
    editText.value = existingEdit.newText != null ? existingEdit.newText : (item.newText || '');
    // Transparent mode ONLY if pure blank
    editTransparent.value = isPureBlank ? Boolean(existingEdit.transparentBackground) : false;
  } else {
    // If pure blank, provide clean empty input so user can type directly on top
    editText.value = isPureBlank ? '' : rawText;
    editTransparent.value = isPureBlank;
  }

  documentStore.activeBlockId = item.id;
  emit('select-item', item);

  nextTick(() => {
    if (activeInputRef.value) {
      const el = Array.isArray(activeInputRef.value) ? activeInputRef.value[0] : activeInputRef.value;
      if (el) {
        el.focus();
        el.select();
      }
    }
  });
}

// Alias for requirement 1
const startEdit = startEditing;

function onInputBlur(item) {
  if (blurTimer) {
    clearTimeout(blurTimer);
  }
  blurTimer = setTimeout(() => {
    if (editingId.value === item.id) {
      commitEdit(item);
    }
    blurTimer = null;
  }, 200);
}

function commitEdit(item) {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }
  if (editingId.value !== item.id) return;

  const newText = editText.value != null ? editText.value : '';
  const pageRot = userRotations.value[item.pageNumber] || 0;
  const effectiveBbox = getRotatedBbox(item.bbox, item.pageNumber);
  const origText = item.text || item.originalText || '';
  const isPureBlank = isPureBlankText(origText);

  // Strict Whiteout vs Transparent:
  // Transparent is ONLY permitted if target text is pure blank.
  // Otherwise, editTransparent MUST be false and background solid #FFFFFF.
  const effectiveTransparent = isPureBlank ? Boolean(editTransparent.value) : false;

  documentStore.recordEdit({
    id: item.id,
    pageNumber: item.pageNumber,
    bbox: effectiveBbox,
    originalText: origText,
    newText: newText,
    style: item.style,
    backgroundColor: '#FFFFFF',
    transparentBackground: effectiveTransparent,
    pageRotation: pageRot
  });

  editingId.value = null;
}

function cancelEdit() {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }
  editingId.value = null;
  editText.value = '';
}

function revertItem(item) {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }
  if (editingId.value === item.id) {
    editingId.value = null;
    editText.value = '';
  }
  documentStore.removeEdit(item.id);
}

// Load and Render PDF Document
async function renderAllPages() {
  if (!documentStore.originalPdfData) return;

  try {
    // FIX BUG 2: Always pass a fresh slice copy to avoid detached ArrayBuffer errors
    const bufferCopy = documentStore.originalPdfData.slice(0);
    const loadingTask = pdfjsLib.getDocument({
      data: new Uint8Array(bufferCopy),
      cMapUrl: 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/cmaps/',
      cMapPacked: true
    });

    currentPdfDoc = await loadingTask.promise;
    numPages.value = currentPdfDoc.numPages;

    await nextTick();

    for (let p = 1; p <= numPages.value; p++) {
      await renderPage(p);
    }
  } catch (err) {
    console.error('Failed to render PDF pages:', err);
    documentStore.errorMessage = `Failed to render PDF: ${err.message}`;
  }
}

async function rotatePage(pageNumber, delta = 90) {
  const currentRot = userRotations.value[pageNumber] || 0;
  const newRot = ((currentRot + delta) % 360 + 360) % 360;
  userRotations.value[pageNumber] = newRot;
  renderedPages.value.delete(pageNumber);
  await renderPage(pageNumber);
}

async function renderPage(pageNumber) {
  if (!currentPdfDoc) return;
  const canvas = canvasRefs.value[pageNumber];
  if (!canvas) return;

  // Cancel any in-flight render task for this page
  if (renderTasks[pageNumber]) {
    try {
      renderTasks[pageNumber].cancel();
    } catch {
      // Ignored
    }
    delete renderTasks[pageNumber];
  }

  try {
    const page = await currentPdfDoc.getPage(pageNumber);

    // Default to 0deg upright orientation unless explicitly rotated by user
    const pageUserRotation = userRotations.value[pageNumber] || 0;

    const viewport = page.getViewport({ 
      scale: props.zoom,
      rotation: pageUserRotation 
    });

    const rawWidth = page.view ? (page.view[2] - page.view[0]) : (viewport.width / props.zoom);
    const rawHeight = page.view ? (page.view[3] - page.view[1]) : (viewport.height / props.zoom);

    pageViewports.value[pageNumber] = {
      width: viewport.width,
      height: viewport.height,
      rotation: pageUserRotation,
      rawWidth,
      rawHeight
    };

    const ctx = canvas.getContext('2d');
    const dpr = window.devicePixelRatio || 1;

    canvas.width  = Math.floor(viewport.width  * dpr);
    canvas.height = Math.floor(viewport.height * dpr);
    canvas.style.width  = `${Math.floor(viewport.width)}px`;
    canvas.style.height = `${Math.floor(viewport.height)}px`;

    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.scale(dpr, dpr);

    const renderContext = {
      canvasContext: ctx,
      viewport: viewport
    };

    const task = page.render(renderContext);
    renderTasks[pageNumber] = task;

    await task.promise;
    delete renderTasks[pageNumber];

    renderedPages.value.add(pageNumber);
    emit('page-rendered', pageNumber);
  } catch (err) {
    if (err?.name !== 'RenderingCancelledException') {
      console.error(`Error rendering page ${pageNumber}:`, err);
    }
  }
}

// Watchers
watch(
  () => documentStore.originalPdfData,
  async (newData) => {
    if (newData && newData.byteLength > 0) {
      userRotations.value = {};
      renderedPages.value.clear();
      await renderAllPages();
    }
  },
  { immediate: true }
);

watch(
  () => props.zoom,
  async () => {
    if (currentPdfDoc) {
      renderedPages.value.clear();
      for (let p = 1; p <= numPages.value; p++) {
        await renderPage(p);
      }
    }
  }
);

onMounted(async () => {
  if (documentStore.originalPdfData && documentStore.originalPdfData.byteLength > 0) {
    await renderAllPages();
  }
});
</script>

<style scoped>
.pdf-canvas-editor-container {
  flex: 1;
  display: flex;
  justify-content: center;
  overflow-y: auto;
  overflow-x: auto;
  padding: 40px 20px;
  background-color: #0f172a;
}

.pages-wrapper {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 36px;
}

.pdf-page-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  background: transparent;
}

.page-meta-header {
  width: 100%;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 4px 8px 4px;
}

.page-meta-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.page-rotation-badge {
  font-size: 10px;
  font-weight: 700;
  background-color: rgba(99, 102, 241, 0.2);
  color: #818cf8;
  border: 1px solid rgba(99, 102, 241, 0.4);
  padding: 1px 6px;
  border-radius: 9999px;
  letter-spacing: 0.03em;
}

.page-header-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.btn-page-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: rgba(30, 41, 59, 0.7);
  border: 1px solid #334155;
  color: #94a3b8;
  font-size: 11px;
  font-weight: 500;
  padding: 2px 7px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-page-action:hover {
  background: #334155;
  color: #f1f5f9;
  border-color: #475569;
}

.page-label {
  font-size: 12px;
  font-weight: 600;
  color: #94a3b8;
  letter-spacing: 0.02em;
}

.page-edit-badge {
  font-size: 11px;
  font-weight: 600;
  background-color: rgba(16, 185, 129, 0.2);
  color: #10b981;
  border: 1px solid rgba(16, 185, 129, 0.4);
  padding: 2px 8px;
  border-radius: 9999px;
}



.page-stage {
  position: relative;
  background: #ffffff;
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.4), 0 8px 10px -6px rgba(0, 0, 0, 0.3);
  border-radius: 4px;
  overflow: hidden;
}

.pdf-page-canvas {
  display: block;
}

.pdf-page-overlay {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: auto;
  z-index: 10;
}

/* Interactive Text Boxes */
.overlay-text-box {
  position: absolute;
  box-sizing: border-box;
  cursor: pointer;
  transition: background-color 0.12s ease, border-color 0.12s ease;
  border-radius: 2px;
  overflow: hidden;
  pointer-events: auto;
}

/* 1. Default Unedited State: Transparent on top of sharp canvas */
.overlay-text-box:not(.is-edited):not(.is-editing) {
  border: 1px dashed transparent;
  background: transparent;
}

/* Hover on unedited: highlight boundaries */
.overlay-text-box:not(.is-edited):not(.is-editing):hover {
  border-color: #6366f1;
  background-color: rgba(99, 102, 241, 0.12);
}

.ghost-text-display {
  width: 100%;
  height: 100%;
  position: relative;
  pointer-events: auto;
}

.hover-hint {
  display: none;
  position: absolute;
  top: -20px;
  left: 0;
  background: #1e1b4b;
  color: #c7d2fe;
  font-size: 10px;
  font-weight: 600;
  padding: 2px 6px;
  border-radius: 3px;
  white-space: nowrap;
  pointer-events: none;
  box-shadow: 0 2px 4px rgba(0, 0, 0, 0.2);
}

.overlay-text-box:not(.is-edited):hover .hover-hint {
  display: block;
}

/* 2. Active Editing State: Inline Textarea Input + Popover Toolbar */
.overlay-text-box.is-editing {
  overflow: visible;
  z-index: 30;
}

.edit-popover-toolbar {
  position: absolute;
  bottom: calc(100% + 4px);
  left: 0;
  display: flex;
  align-items: center;
  gap: 8px;
  background: #0f172a;
  border: 1px solid #334155;
  padding: 4px 10px;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);
  white-space: nowrap;
  z-index: 40;
}

.popover-mode-badge {
  font-size: 11px;
  font-weight: 600;
  color: #94a3b8;
  letter-spacing: 0.02em;
}

.toggle-mode-btn {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  font-weight: 600;
  color: #94a3b8;
  cursor: pointer;
  user-select: none;
}

.toggle-mode-btn.is-active {
  color: #60a5fa;
}

.hidden-checkbox {
  display: none;
}

.toggle-icon {
  font-size: 13px;
  color: #3b82f6;
}

.btn-popover-done {
  background: #4f46e5;
  color: #ffffff;
  border: none;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
  cursor: pointer;
}

.btn-popover-done:hover {
  background: #4338ca;
}

.inline-editor-input {
  width: 100%;
  max-width: 100%;
  height: 100%;
  line-height: 1;
  overflow: hidden;
  text-overflow: clip;
  white-space: pre;
  box-sizing: border-box;
  padding: 0 2px;
  margin: 0;
  background-color: #ffffff;
  border: 1.5px solid #4f46e5;
  outline: none;
  box-shadow: 0 2px 8px rgba(79, 70, 229, 0.35);
  border-radius: 2px;
}

/* Transparent form fill input (overprint on dots/lines) */
.inline-editor-input.is-transparent-input {
  background-color: transparent !important;
  border: 2px dashed #3b82f6 !important;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.25);
}

/* 3. Modified / Edited State: Solid Whiteout OR Transparent Overprint */
.overlay-text-box.is-edited,
.overlay-text-box.modified-item {
  background-color: #ffffff;
  border: 1.5px solid #10b981;
  box-shadow: 0 2px 6px rgba(16, 185, 129, 0.2);
  z-index: 20;
  pointer-events: auto !important;
  cursor: pointer;
}

/* Bug 1 Fix: Transparent background overprint mode (preserves dots/underlines) */
.overlay-text-box.is-edited.is-transparent-edit,
.overlay-text-box.modified-item.is-transparent-edit {
  background-color: transparent !important;
  border: 1.5px dashed #3b82f6 !important;
  box-shadow: none !important;
}

.overlay-text-box.is-edited:hover,
.overlay-text-box.modified-item:hover {
  box-shadow: 0 2px 8px rgba(16, 185, 129, 0.35);
}

.overlay-text-box.is-edited.is-transparent-edit:hover,
.overlay-text-box.modified-item.is-transparent-edit:hover {
  border-color: #2563eb !important;
  box-shadow: 0 2px 8px rgba(59, 130, 246, 0.3) !important;
}

.edited-text-display,
.modified-item {
  width: 100%;
  max-width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  position: relative;
  padding: 0 2px;
  box-sizing: border-box;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: pre;
  line-height: 1;
  pointer-events: auto !important;
  cursor: pointer;
}

.edited-text-display.is-transparent-display {
  background-color: transparent !important;
}

.edited-text-content {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: pre;
  display: inline-block;
  max-width: 100%;
  user-select: none;
}

.btn-revert-item {
  position: absolute;
  top: -8px;
  right: -8px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background-color: #ef4444;
  color: #ffffff;
  font-size: 11px;
  line-height: 1;
  display: none;
  align-items: center;
  justify-content: center;
  border: 1px solid #ffffff;
  cursor: pointer;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.3);
  z-index: 25;
}

.overlay-text-box.is-edited:hover .btn-revert-item,
.overlay-text-box.modified-item:hover .btn-revert-item {
  display: flex;
}

.btn-revert-item:hover {
  background-color: #dc2626;
  transform: scale(1.15);
}

.page-loading-mask {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(255, 255, 255, 0.85);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  font-size: 13px;
  font-weight: 500;
  color: #475569;
}

.spinner-sm {
  width: 28px;
  height: 28px;
  border: 3px solid #cbd5e1;
  border-top-color: #4f46e5;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
