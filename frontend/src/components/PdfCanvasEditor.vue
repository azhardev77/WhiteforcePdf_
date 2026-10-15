<template>
  <div class="pdf-canvas-editor-container" ref="containerRef">
    <!-- Pages Scroller -->
    <div class="pages-wrapper">
      <div
        v-for="pageIndex in numPages"
        :key="pageIndex"
        :id="`pdf-page-${pageIndex}`"
        :data-page="pageIndex"
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
              {{ getPageEditCount(pageIndex) }} {{ getPageEditCount(pageIndex) === 1 ? 'paragraph edit' : 'paragraph edits' }}
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
          <!-- Active Page: Mounted when visible in/near viewport -->
          <template v-if="isPageVisible(pageIndex)">
            <!-- 1. 100% Pixel-Perfect PDF.js Canvas -->
            <canvas
              :ref="el => setCanvasRef(pageIndex, el)"
              class="pdf-page-canvas"
            />

            <!-- 1.5. Dedicated Whiteout Mask Layer (Above canvas, below interactive tokens) -->
            <div
              v-if="isPageCanvasRendered[pageIndex]"
              class="pdf-page-whiteout-layer"
              :style="getPageOverlayStyle(pageIndex)"
            >
              <div
                v-for="mask in getPageWhiteoutMasks(pageIndex)"
                :key="mask.id"
                :id="mask.id"
                :data-mask-id="mask.id"
                class="whiteout-mask-box"
                :style="mask.style"
              />
            </div>

            <!-- 2. Interactive WPS Office-Style Paragraph Blocks Layer -->
            <div
              v-if="isPageCanvasRendered[pageIndex]"
              class="pdf-page-overlay"
              :style="getPageOverlayStyle(pageIndex)"
            >
              <div
                v-for="block in getPageBlocks(pageIndex)"
                :key="block.id"
                :data-block-id="block.id"
                :data-id="block.id"
                :data-text="block.text"
                :data-is-blank-line="block.isBlankLine ? 'true' : 'false'"
                :data-is-moved="block.isMoved ? 'true' : 'false'"
                :title="block.text"
                class="wps-paragraph-block overlay-text-box"
                :class="{
                  'is-editing': editingBlockId === block.id,
                  'is-edited': isBlockEdited(block),
                  'is-hovered': hoveredBlockId === block.id && editingBlockId !== block.id,
                  'is-active': documentStore.activeBlockId === block.id,
                  'is-blank-line': block.isBlankLine,
                  'is-moved': block.isMoved,
                  'is-dragging': draggingBlockId === block.id
                }"
                :style="getParagraphBlockStyle(block)"
                @mouseenter="hoveredBlockId = block.id"
                @mouseleave="hoveredBlockId = null"
                @pointerdown="onBlockPointerDown(block, $event)"
                @click.stop="onParagraphClick(block, $event)"
              >
                <!-- 4-Way Drag & Move Handle (Visible on hover or active/editing) -->
                <div
                  class="block-drag-handle"
                  data-testid="block-drag-handle"
                  title="Drag to move paragraph"
                  @pointerdown.stop.prevent="startDragBlock(block, $event)"
                >
                  <svg viewBox="0 0 24 24" class="icon-drag-move" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
                    <path d="M5 9l-3 3 3 3M9 5l3-3 3 3M15 19l-3 3-3-3M19 9l3 3-3 3M2 12h20M12 2v20"/>
                  </svg>
                </div>

                <!-- Screen-reader and locator text representation: always present in all states -->
                <span class="sr-only-block-text">{{ editingBlockId === block.id ? activeBlockText : block.text }}</span>

                <!-- Editing Mode: Direct In-Place Live Typing (No popups required!) -->
                <template v-if="editingBlockId === block.id">
                  <textarea
                    ref="activeBlockTextareaRef"
                    v-model="activeBlockText"
                    class="wps-paragraph-textarea inline-editor-input"
                    :data-block-id="block.id"
                    :style="getParagraphTextareaStyle(block)"
                    rows="1"
                    @input="onParagraphInput(block, $event)"
                    @keydown.esc.prevent="cancelBlockEdit(block)"
                    @blur="onParagraphBlur(block)"
                  />
                </template>

                <!-- Display Mode: Modified / Edited state vs Original unedited state -->
                <template v-else>
                  <!-- Modified/Edited Paragraph Display with Revert Button -->
                  <div
                    v-if="isBlockEdited(block)"
                    class="wps-paragraph-display edited-text-display modified-item"
                    :style="getParagraphDisplayStyle(block)"
                    @click.stop="startEditingBlock(block, $event)"
                    @dblclick.stop="revertBlock(block)"
                    title="Click to edit, double-click to revert"
                  >
                    <div class="wps-paragraph-content edited-text-content">{{ block.text }}</div>
                    <button
                      type="button"
                      class="btn-revert-block btn-revert-item"
                      title="Revert paragraph to original text (1 click)"
                      @click.stop="revertBlock(block)"
                    >
                      &times;
                    </button>
                  </div>

                  <!-- Original Unedited Paragraph: Transparent Hitbox with WPS Dashed Border -->
                  <div v-else class="wps-paragraph-ghost ghost-word-hitbox">
                    <span class="hover-hint">Click to edit paragraph</span>
                  </div>
                </template>
              </div>
            </div>

            <!-- Page Loading Spinner (Visible while canvas rendering) -->
            <div v-if="!isPageCanvasRendered[pageIndex]" class="page-loading-mask">
              <div class="spinner-sm" />
              <span>Rendering Page {{ pageIndex }}...</span>
            </div>
          </template>

          <!-- Offscreen Page: Ultra-Lightweight Zero-Cost Placeholder -->
          <div v-else class="page-offscreen-placeholder">
            <div class="placeholder-skeleton-body">
              <div class="skeleton-shimmer-bar" />
              <div class="skeleton-shimmer-bar short" />
              <div class="skeleton-shimmer-bar medium" />
              <div class="skeleton-shimmer-bar" />
              <div class="skeleton-shimmer-bar short" />
            </div>
            <div class="placeholder-meta-tag">
              <span>Page {{ pageIndex }} (Virtualized)</span>
            </div>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, nextTick, onMounted, onBeforeUnmount } from 'vue';
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
const activeBlockTextareaRef = ref(null);

const numPages = ref(0);
const pageViewports = ref({}); // pageIndex -> { width, height, rotation, rawWidth, rawHeight }
const userRotations = ref({}); // pageIndex -> rotation in degrees (0, 90, 180, 270)
const isPageCanvasRendered = ref({}); // pageIndex -> boolean

// Virtual Scrolling & Lazy Rendering State
const visiblePages = ref({}); // pageNumber -> boolean
let pageObserver = null;
let renderDebounceTimer = null;

// WPS Paragraph Block State
const pageBlocksMap = ref({}); // pageNumber -> Array<ParagraphBlock>
const editingBlockId = ref(null);
const activeBlockText = ref('');
const hoveredBlockId = ref(null);
const activeDeltaY = ref(0);
const reflowVersion = ref(0);

// Pristine background canvas snapshot for live lossless slicing on vertical reflow
const pristineCanvasMap = {}; // pageNumber -> HTMLCanvasElement

// Track committed edits across page re-renders: blockId -> { newText, deltaY, currentHeight }
const editedBlocksState = {};

let blurTimer = null;

function isPageVisible(pageIndex) {
  if (editingBlockId.value && pageBlocksMap.value[pageIndex]?.some(b => b.id === editingBlockId.value)) {
    return true;
  }
  return Boolean(visiblePages.value[pageIndex]);
}

function getPageBlocks(pageIndex) {
  return pageBlocksMap.value[pageIndex] || [];
}

// Backwards compatibility getter
function getPageWords(pageIndex) {
  return pageBlocksMap.value[pageIndex] || [];
}

function isBlockEdited(block) {
  if (!block) return false;
  if (block.isMoved && (block.customX != null || block.customY != null)) {
    return true;
  }
  if (block.isBlankLine) {
    return Boolean(block.isEdited && block.text && block.text.trim());
  }
  return Boolean(block.isEdited && block.text !== block.originalText);
}

function getPageEditCount(pageIndex) {
  const blocks = pageBlocksMap.value[pageIndex] || [];
  return blocks.filter(b => isBlockEdited(b)).length;
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

function getMaxAllowedWidth(block) {
  const vp = pageViewports.value[block.pageNumber];
  const pageW = vp ? vp.width : 600;
  const rightMargin = 36 * props.zoom;
  const posX = block.customX != null ? block.customX : block.originalX;
  return Math.max(block.originalWidth, pageW - posX - rightMargin);
}

function getPageExpandedEdits(pageNumber) {
  const blocks = pageBlocksMap.value[pageNumber] || [];
  const expanded = blocks
    .filter(b => (isBlockEdited(b) && (b.deltaY || 0) > 0.5) || (b.id === editingBlockId.value && activeDeltaY.value > 0.5))
    .map(b => ({
      id: b.id,
      originalY: b.originalY,
      originalHeight: b.originalHeight,
      bottomY: b.originalY + b.originalHeight,
      deltaY: (b.id === editingBlockId.value ? activeDeltaY.value : b.deltaY) || 0
    }))
    .sort((a, b) => a.originalY - b.originalY);

  return expanded;
}

function getCumulativeDeltaYForY(pageNumber, targetOriginalY, currentBlockId = null) {
  const expanded = getPageExpandedEdits(pageNumber);
  let shift = 0;
  for (const exp of expanded) {
    if (exp.id === currentBlockId) continue;
    if (exp.bottomY <= targetOriginalY + 3) {
      shift += exp.deltaY;
    }
  }
  return shift;
}

function getPageTotalDeltaY(pageIndex) {
  const expanded = getPageExpandedEdits(pageIndex);
  return expanded.reduce((sum, b) => sum + b.deltaY, 0);
}

function updatePageVerticalReflow(pageNumber) {
  const pageBlocks = pageBlocksMap.value[pageNumber] || [];
  if (pageBlocks.length === 0) return;

  for (const b of pageBlocks) {
    const shiftY = getCumulativeDeltaYForY(pageNumber, b.originalY, b.id);
    b.y = b.originalY + shiftY;
    if (b.originalPdfY != null) {
      b.pdfY = b.originalPdfY + (shiftY / props.zoom);
    }
  }

  reflowVersion.value++;
  applyLiveCanvasReflow(pageNumber);
}

function applyLiveCanvasReflow(pageNumber) {
  const canvas = canvasRefs.value[pageNumber];
  const pristine = pristineCanvasMap[pageNumber];
  if (!canvas || !pristine) return;

  const vp = pageViewports.value[pageNumber];
  if (!vp) return;

  const dpr = window.devicePixelRatio || 1;
  const ctx = canvas.getContext('2d');
  const expanded = getPageExpandedEdits(pageNumber);

  if (expanded.length === 0) {
    if (canvas.height !== pristine.height) {
      canvas.width = pristine.width;
      canvas.height = pristine.height;
      canvas.style.width = `${Math.floor(vp.width)}px`;
      canvas.style.height = `${Math.floor(vp.height)}px`;
    }
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.drawImage(pristine, 0, 0);
    return;
  }

  const totalDeltaY = expanded.reduce((sum, b) => sum + b.deltaY, 0);
  const targetH = pristine.height + Math.round(totalDeltaY * dpr);

  if (canvas.height !== targetH) {
    canvas.width = pristine.width;
    canvas.height = targetH;
    canvas.style.width = `${Math.floor(vp.width)}px`;
    canvas.style.height = `${Math.floor(vp.height + totalDeltaY)}px`;
  }

  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  let prevSourceBottomY = 0;
  let currentDestY = 0;

  for (const exp of expanded) {
    const splitY = Math.round(exp.bottomY);
    const sliceHeight = Math.max(0, splitY - prevSourceBottomY);

    if (sliceHeight > 0) {
      ctx.drawImage(
        pristine,
        0, Math.round(prevSourceBottomY * dpr),
        pristine.width, Math.round(sliceHeight * dpr),
        0, Math.round(currentDestY * dpr),
        pristine.width, Math.round(sliceHeight * dpr)
      );
      currentDestY += sliceHeight;
    }

    currentDestY += exp.deltaY;
    prevSourceBottomY = splitY;
  }

  const remainingH = Math.max(0, vp.height - prevSourceBottomY);
  if (remainingH > 0) {
    ctx.drawImage(
      pristine,
      0, Math.round(prevSourceBottomY * dpr),
      pristine.width, Math.round(remainingH * dpr),
      0, Math.round(currentDestY * dpr),
      pristine.width, Math.round(remainingH * dpr)
    );
  }
}

function getPageWhiteoutMasks(pageIndex) {
  const blocks = pageBlocksMap.value[pageIndex] || [];
  if (blocks.length === 0) return [];

  const masks = [];
  for (const block of blocks) {
    const isEdited = isBlockEdited(block) || editingBlockId.value === block.id;
    if (!isEdited) continue;
    // CRITICAL: Blank line blocks with transparent background MUST NOT whiteout the line!
    if (block.isBlankLine || block.transparentBackground) {
      continue;
    }

    masks.push({
      id: `mask-${block.id}`,
      style: {
        left: `${Math.round(block.originalX - 1)}px`,
        top: `${Math.round(block.originalY - 1)}px`,
        width: `${Math.round(block.originalWidth + 3)}px`,
        height: `${Math.round(block.originalHeight + 2)}px`
      }
    });
  }
  return masks;
}

function getParagraphBlockStyle(block) {
  const _ = reflowVersion.value;
  const maxW = getMaxAllowedWidth(block);
  const isEditing = editingBlockId.value === block.id;
  const isEdited = isBlockEdited(block);
  const heightPx = (isEditing || isEdited)
    ? Math.max((isEditing ? (activeDeltaY.value + block.originalHeight) : block.currentHeight) || block.originalHeight, 16)
    : block.originalHeight;
  const widthPx = Math.max(block.originalWidth, 30);

  const posX = block.customX != null ? block.customX : block.originalX;
  const posY = block.customY != null ? block.customY : block.y;

  const styleObj = {
    left: `${Math.round(posX)}px`,
    top: `${Math.round(posY)}px`,
    width: `${Math.round(widthPx)}px`,
    maxWidth: `${Math.round(maxW)}px`,
    height: `${Math.round(heightPx)}px`,
    zIndex: isEditing ? 35 : (isEdited ? 25 : 15)
  };

  if (block.isBlankLine || block.transparentBackground) {
    styleObj.backgroundColor = 'transparent';
  }

  return styleObj;
}

function getParagraphTextareaStyle(block) {
  const linePitch = (block.lines && block.lines.length > 1)
    ? (block.originalHeight / block.lines.length)
    : Math.max(block.viewportFontSize * 1.25, 16);
  return {
    fontSize: `${block.viewportFontSize}px`,
    fontFamily: block.fontName || 'Helvetica, Arial, sans-serif',
    fontWeight: block.bold ? 'bold' : 'normal',
    fontStyle: block.italic ? 'italic' : 'normal',
    color: block.color || '#000000',
    lineHeight: `${Math.round(linePitch)}px`
  };
}

function getParagraphDisplayStyle(block) {
  const linePitch = (block.lines && block.lines.length > 1)
    ? (block.originalHeight / block.lines.length)
    : Math.max(block.viewportFontSize * 1.25, 16);
  return {
    fontSize: `${block.viewportFontSize}px`,
    fontFamily: block.fontName || 'Helvetica, Arial, sans-serif',
    fontWeight: block.bold ? 'bold' : 'normal',
    fontStyle: block.italic ? 'italic' : 'normal',
    color: block.color || '#000000',
    lineHeight: `${Math.round(linePitch)}px`
  };
}

function getPageCardStyle(pageIndex) {
  const vp = pageViewports.value[pageIndex];
  if (vp && vp.width) {
    return { width: `${Math.ceil(vp.width)}px` };
  }
  const dims = getPageDimensions(pageIndex);
  return { width: `${Math.ceil(dims.width * props.zoom)}px` };
}

function getPageStageStyle(pageIndex) {
  const totalDeltaY = getPageTotalDeltaY(pageIndex);
  const vp = pageViewports.value[pageIndex];
  if (vp && vp.width && vp.height) {
    return {
      width: `${Math.ceil(vp.width)}px`,
      height: `${Math.ceil(vp.height + totalDeltaY)}px`
    };
  }
  const dims = getPageDimensions(pageIndex);
  return {
    width: `${Math.ceil(dims.width * props.zoom)}px`,
    height: `${Math.ceil((dims.height * props.zoom) + totalDeltaY)}px`
  };
}

function getPageOverlayStyle(pageIndex) {
  const totalDeltaY = getPageTotalDeltaY(pageIndex);
  const vp = pageViewports.value[pageIndex];
  if (vp && vp.width && vp.height) {
    return {
      width: `${Math.ceil(vp.width)}px`,
      height: `${Math.ceil(vp.height + totalDeltaY)}px`
    };
  }
  const dims = getPageDimensions(pageIndex);
  return {
    width: `${Math.ceil(dims.width * props.zoom)}px`,
    height: `${Math.ceil((dims.height * props.zoom) + totalDeltaY)}px`
  };
}

/**
 * Searches for dotted / dashed fill-in-the-blank placeholders in paragraph text.
 */
function findDotRange(text) {
  if (!text) return null;
  const match = text.match(/(\.{3,}|_{3,}|-{3,})/);
  if (match && match.index != null) {
    return {
      start: match.index,
      end: match.index + match[0].length
    };
  }
  return null;
}

const draggingBlockId = ref(null);

function onBlockPointerDown(block, event) {
  if (event.target === event.currentTarget) {
    startDragBlock(block, event);
  }
}

function startDragBlock(block, event) {
  if (event.button !== 0) return; // Only primary mouse button
  if (editingBlockId.value === block.id) {
    commitActiveBlock();
  }

  draggingBlockId.value = block.id;
  documentStore.activeBlockId = block.id;

  const startX = event.clientX;
  const startY = event.clientY;

  const blockStartX = block.customX != null ? block.customX : (block.originalX != null ? block.originalX : block.x);
  const blockStartY = block.customY != null ? block.customY : (block.originalY != null ? block.originalY : block.y);

  const vp = pageViewports.value[block.pageNumber];
  const maxW = vp ? vp.width : 800;
  const maxH = vp ? vp.height : 1100;

  const blockW = block.originalWidth || block.width || 100;
  const blockH = block.originalHeight || block.height || 30;

  const clampMinX = 0;
  const clampMaxX = Math.max(0, maxW - blockW);
  const clampMinY = 0;
  const clampMaxY = Math.max(0, maxH - blockH);

  let hasMoved = false;

  function onPointerMove(e) {
    const dx = e.clientX - startX;
    const dy = e.clientY - startY;

    if (Math.abs(dx) > 2 || Math.abs(dy) > 2) {
      hasMoved = true;
    }

    const clampedX = Math.min(Math.max(blockStartX + dx, clampMinX), clampMaxX);
    const clampedY = Math.min(Math.max(blockStartY + dy, clampMinY), clampMaxY);

    block.customX = clampedX;
    block.customY = clampedY;
    block.x = clampedX;
    block.y = clampedY;
    block.isMoved = true;
    reflowVersion.value++;
  }

  function onPointerUp(e) {
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', onPointerUp);
    window.removeEventListener('pointercancel', onPointerUp);

    setTimeout(() => {
      draggingBlockId.value = null;
    }, 50);

    if (hasMoved || block.isMoved) {
      block.isEdited = true;
      editedBlocksState[block.id] = {
        ...(editedBlocksState[block.id] || {}),
        newText: block.text,
        deltaY: block.deltaY || 0,
        currentHeight: block.currentHeight || block.originalHeight,
        customX: block.customX,
        customY: block.customY,
        isMoved: true
      };

      const edits = buildRedactionPayload();
      documentStore.setEdits(edits);
    }
  }

  window.addEventListener('pointermove', onPointerMove);
  window.addEventListener('pointerup', onPointerUp);
  window.addEventListener('pointercancel', onPointerUp);
}

function onParagraphClick(block, event) {
  if (draggingBlockId.value) return;
  if (editingBlockId.value === block.id) return;
  startEditingBlock(block, event);
}

function startEditingBlock(block, event) {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }

  if (editingBlockId.value === block.id) return;
  if (editingBlockId.value) {
    commitActiveBlock();
  }

  editingBlockId.value = block.id;
  if (block.isBlankLine) {
    const raw = block.text != null ? block.text : block.originalText;
    activeBlockText.value = (/^[,\.;:\-_–—\s]*$/.test(raw || '')) ? '' : raw;
  } else {
    activeBlockText.value = block.text != null ? block.text : block.originalText;
  }
  activeDeltaY.value = block.deltaY || 0;

  documentStore.activeBlockId = block.id;
  emit('select-item', block);

  nextTick(() => {
    let el = activeBlockTextareaRef.value;
    if (Array.isArray(el)) el = el[0];
    if (el) {
      el.style.height = 'auto';
      const scrollH = el.scrollHeight;
      const baseH = block.originalHeight;
      const currentH = Math.max(scrollH, baseH);
      el.style.height = `${currentH}px`;
      const extraH = Math.max(0, currentH - baseH);
      activeDeltaY.value = extraH;
      block.deltaY = extraH;
      block.currentHeight = currentH;
      updatePageVerticalReflow(block.pageNumber);

      el.focus();

      // If text contains dots / blank placeholder, select it so typing directly replaces it!
      const dotRange = findDotRange(activeBlockText.value);
      if (dotRange) {
        el.setSelectionRange(dotRange.start, dotRange.end);
      } else if (block.isBlankLine && !activeBlockText.value) {
        el.setSelectionRange(0, 0);
      } else {
        el.select();
      }
    }
  });
}

function onParagraphInput(block, event) {
  let el = event?.target || activeBlockTextareaRef.value;
  if (Array.isArray(el)) el = el[0];

  block.text = activeBlockText.value;
  if (block.isBlankLine) {
    block.isEdited = Boolean(activeBlockText.value && activeBlockText.value.trim());
  }

  if (el) {
    el.style.height = 'auto';
    const scrollH = el.scrollHeight;
    const baseH = block.originalHeight;
    const currentH = Math.max(scrollH, baseH);
    el.style.height = `${currentH}px`;

    const extraH = Math.max(0, currentH - baseH);
    block.deltaY = extraH;
    block.currentHeight = currentH;
    activeDeltaY.value = extraH;
  }

  updatePageVerticalReflow(block.pageNumber);

  // Synchronize redaction payload with store
  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
}

function commitActiveBlock() {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }

  if (!editingBlockId.value) return;
  const currentId = editingBlockId.value;
  let targetBlock = null;

  for (const pageBlocks of Object.values(pageBlocksMap.value)) {
    const b = pageBlocks.find(item => item.id === currentId);
    if (b) {
      targetBlock = b;
      break;
    }
  }

  if (targetBlock) {
    targetBlock.text = activeBlockText.value != null ? activeBlockText.value : '';
    targetBlock.deltaY = activeDeltaY.value || 0;

    if (targetBlock.isBlankLine) {
      if (targetBlock.text && targetBlock.text.trim()) {
        targetBlock.isEdited = true;
        editedBlocksState[targetBlock.id] = {
          newText: targetBlock.text,
          deltaY: targetBlock.deltaY,
          currentHeight: targetBlock.currentHeight || targetBlock.originalHeight
        };
      } else {
        targetBlock.isEdited = false;
        delete editedBlocksState[targetBlock.id];
        targetBlock.text = targetBlock.originalText;
        targetBlock.deltaY = 0;
        targetBlock.currentHeight = targetBlock.originalHeight;
      }
    } else if (targetBlock.text !== targetBlock.originalText || targetBlock.isMoved) {
      targetBlock.isEdited = true;
      editedBlocksState[targetBlock.id] = {
        newText: targetBlock.text,
        deltaY: targetBlock.deltaY,
        currentHeight: targetBlock.currentHeight || targetBlock.originalHeight,
        customX: targetBlock.customX,
        customY: targetBlock.customY,
        isMoved: targetBlock.isMoved
      };
    } else {
      targetBlock.isEdited = false;
      delete editedBlocksState[targetBlock.id];
      targetBlock.deltaY = 0;
      targetBlock.currentHeight = targetBlock.originalHeight;
    }
    updatePageVerticalReflow(targetBlock.pageNumber);
  }

  editingBlockId.value = null;
  activeBlockText.value = '';
  activeDeltaY.value = 0;

  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
}

function cancelBlockEdit(block) {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }

  if (editingBlockId.value === block.id) {
    activeBlockText.value = block.text;
    editingBlockId.value = null;
  }
}

function onParagraphBlur(block) {
  if (blurTimer) {
    clearTimeout(blurTimer);
  }
  blurTimer = setTimeout(() => {
    if (editingBlockId.value === block.id) {
      commitActiveBlock();
    }
    blurTimer = null;
  }, 250);
}

function revertBlock(block) {
  delete editedBlocksState[block.id];
  block.text = block.originalText;
  block.isEdited = false;
  block.isMoved = false;
  block.customX = undefined;
  block.customY = undefined;
  block.x = block.originalX;
  block.y = block.originalY;
  block.deltaY = 0;
  block.currentHeight = block.originalHeight;

  if (editingBlockId.value === block.id) {
    editingBlockId.value = null;
    activeBlockText.value = '';
    activeDeltaY.value = 0;
  }

  updatePageVerticalReflow(block.pageNumber);
  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
}

/**
 * Builds the PDF export redaction payload.
 */
function buildRedactionPayload() {
  const allEdits = [];

  for (const [pageNumStr, blocks] of Object.entries(pageBlocksMap.value)) {
    const pageNumber = Number(pageNumStr);
    const pageRot = userRotations.value[pageNumber] || 0;
    const vp = pageViewports.value[pageNumber];
    const scale = vp ? (vp.width / (vp.rawWidth || 595.28)) : props.zoom;

    for (const block of blocks) {
      const isActivelyEditing = editingBlockId.value === block.id;
      const isMoved = Boolean(block.isMoved && (block.customX != null || block.customY != null));
      const isEdited = isBlockEdited(block) || isActivelyEditing || isMoved;
      if (!isEdited) continue;

      const currentText = isActivelyEditing ? activeBlockText.value : block.text;
      if (currentText === block.originalText && !isActivelyEditing && !isMoved) continue;

      const rawOriginalBbox = [
        block.originalPdfX,
        block.originalPdfY,
        block.originalPdfWidth,
        block.originalPdfHeight
      ];
      const effectiveOriginalBbox = getRotatedBbox(rawOriginalBbox, pageNumber);

      const targetX = block.customX != null ? block.customX : block.originalX;
      const targetY = block.customY != null ? block.customY : block.y;

      const rawNewBbox = [
        targetX / scale,
        targetY / scale,
        block.originalPdfWidth,
        block.originalPdfHeight
      ];
      const effectiveNewBbox = getRotatedBbox(rawNewBbox, pageNumber);

      allEdits.push({
        id: `redact-block-${block.id}`,
        lineId: block.id,
        pageNumber,
        bbox: effectiveNewBbox,
        originalBbox: effectiveOriginalBbox,
        originalBoundingBox: effectiveOriginalBbox,
        isMoved,
        originalText: block.originalText,
        newText: currentText,
        style: {
          fontSize: block.fontSize,
          fontFamily: block.fontName || 'Helvetica',
          bold: Boolean(block.bold),
          italic: Boolean(block.italic),
          color: block.color || '#000000'
        },
        backgroundColor: '#FFFFFF',
        transparentBackground: Boolean(block.transparentBackground || block.isBlankLine),
        isBlankLine: Boolean(block.isBlankLine),
        pageRotation: pageRot,
        reflowed: (block.deltaY || 0) > 0.5
      });
    }
  }

  return allEdits;
}

let currentPdfDoc = null;
const renderTasks = {};

function setCanvasRef(pageIndex, el) {
  if (el) {
    canvasRefs.value[pageIndex] = el;
  } else {
    delete canvasRefs.value[pageIndex];
  }
}

async function prefetchPageViewports() {
  if (!currentPdfDoc) return;
  const tasks = [];
  for (let p = 1; p <= numPages.value; p++) {
    tasks.push(
      currentPdfDoc.getPage(p).then((page) => {
        const rot = (userRotations.value[p] || 0) % 360;
        const vp = page.getViewport({ scale: props.zoom, rotation: rot });
        const rawWidth = page.view ? (page.view[2] - page.view[0]) : (vp.width / props.zoom);
        const rawHeight = page.view ? (page.view[3] - page.view[1]) : (vp.height / props.zoom);
        pageViewports.value[p] = {
          width: vp.width,
          height: vp.height,
          rotation: rot,
          rawWidth,
          rawHeight
        };
      }).catch(err => {
        console.warn(`Failed to prefetch viewport for page ${p}:`, err);
      })
    );
  }
  await Promise.all(tasks);
}

async function setupIntersectionObserver() {
  await nextTick();
  if (pageObserver) {
    pageObserver.disconnect();
    pageObserver = null;
  }
  if (!containerRef.value) return;

  pageObserver = new IntersectionObserver((entries) => {
    let visibilityChanged = false;

    for (const entry of entries) {
      const pageNum = Number(entry.target.dataset.page);
      if (!pageNum) continue;

      const isIntersecting = entry.isIntersecting;
      const isCurrentlyEditingThisPage = editingBlockId.value && pageBlocksMap.value[pageNum]?.some(b => b.id === editingBlockId.value);

      if (visiblePages.value[pageNum] !== isIntersecting) {
        if (!isIntersecting && isCurrentlyEditingThisPage) {
          continue;
        }

        visiblePages.value[pageNum] = isIntersecting;
        visibilityChanged = true;

        if (!isIntersecting) {
          unrenderPage(pageNum);
        }
      }
    }

    if (visibilityChanged) {
      scheduleVisiblePagesRender();
    }
  }, {
    root: containerRef.value,
    rootMargin: '600px 0px 600px 0px',
    threshold: 0.01
  });

  for (let p = 1; p <= numPages.value; p++) {
    const el = document.getElementById(`pdf-page-${p}`);
    if (el) {
      pageObserver.observe(el);
    }
  }
}

function scheduleVisiblePagesRender() {
  if (renderDebounceTimer) {
    clearTimeout(renderDebounceTimer);
  }
  renderDebounceTimer = setTimeout(() => {
    processVisiblePagesRender();
    renderDebounceTimer = null;
  }, 35);
}

async function processVisiblePagesRender() {
  if (!currentPdfDoc) return;

  const pagesToRender = [];
  for (let p = 1; p <= numPages.value; p++) {
    if (isPageVisible(p) && !isPageCanvasRendered.value[p]) {
      pagesToRender.push(p);
    }
  }

  if (pagesToRender.length === 0) return;

  for (const pageNum of pagesToRender) {
    if (!isPageVisible(pageNum)) continue;
    await renderPage(pageNum);
  }
}

function unrenderPage(pageNumber) {
  if (renderTasks[pageNumber]) {
    try {
      renderTasks[pageNumber].cancel();
    } catch {}
    delete renderTasks[pageNumber];
  }

  isPageCanvasRendered.value[pageNumber] = false;
  delete pristineCanvasMap[pageNumber];

  const canvas = canvasRefs.value[pageNumber];
  if (canvas) {
    canvas.width = 1;
    canvas.height = 1;
  }
}

async function renderAllPages() {
  if (!documentStore.originalPdfData) return;

  try {
    const bufferCopy = documentStore.originalPdfData.slice(0);
    const loadingTask = pdfjsLib.getDocument({
      data: new Uint8Array(bufferCopy),
      cMapUrl: 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/cmaps/',
      cMapPacked: true
    });

    currentPdfDoc = await loadingTask.promise;
    numPages.value = currentPdfDoc.numPages;

    await prefetchPageViewports();

    visiblePages.value = { 1: true };
    if (numPages.value > 1) {
      visiblePages.value[2] = true;
    }

    await setupIntersectionObserver();
    scheduleVisiblePagesRender();
  } catch (err) {
    console.error('Failed to render PDF pages:', err);
    documentStore.errorMessage = `Failed to render PDF: ${err.message}`;
  }
}

async function rotatePage(pageNumber, delta = 90) {
  const currentRot = userRotations.value[pageNumber] || 0;
  const newRot = ((currentRot + delta) % 360 + 360) % 360;
  userRotations.value[pageNumber] = newRot;

  if (currentPdfDoc) {
    try {
      const page = await currentPdfDoc.getPage(pageNumber);
      const vp = page.getViewport({ scale: props.zoom, rotation: newRot });
      const rawWidth = page.view ? (page.view[2] - page.view[0]) : (vp.width / props.zoom);
      const rawHeight = page.view ? (page.view[3] - page.view[1]) : (vp.height / props.zoom);
      pageViewports.value[pageNumber] = {
        width: vp.width,
        height: vp.height,
        rotation: newRot,
        rawWidth,
        rawHeight
      };
    } catch (err) {
      console.warn(`Error updating rotation viewport for page ${pageNumber}:`, err);
    }
  }

  isPageCanvasRendered.value[pageNumber] = false;
  if (isPageVisible(pageNumber)) {
    await renderPage(pageNumber);
  }
}

async function renderPage(pageNumber) {
  if (!currentPdfDoc) return;
  if (!isPageVisible(pageNumber)) return;

  if (renderTasks[pageNumber]) {
    try {
      renderTasks[pageNumber].cancel();
    } catch {}
    delete renderTasks[pageNumber];
  }

  await nextTick();
  const canvas = canvasRefs.value[pageNumber];
  if (!canvas || !isPageVisible(pageNumber)) return;

  isPageCanvasRendered.value[pageNumber] = false;

  try {
    const page = await currentPdfDoc.getPage(pageNumber);
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

    if (!isPageVisible(pageNumber)) {
      unrenderPage(pageNumber);
      return;
    }

    // Pristine canvas snapshot for vertical slicing on dynamic expansion
    const pristineClone = document.createElement('canvas');
    pristineClone.width = canvas.width;
    pristineClone.height = canvas.height;
    const pCtx = pristineClone.getContext('2d');
    pCtx.drawImage(canvas, 0, 0);
    pristineCanvasMap[pageNumber] = pristineClone;

    // Extract text content and group into WPS Office-Style Paragraph Blocks
    const textContent = await page.getTextContent();
    extractParagraphBlocks(pageNumber, textContent, viewport);
    scanAndAppendCanvasBlankLines(pageNumber, pristineClone, viewport);
    updatePageVerticalReflow(pageNumber);

    isPageCanvasRendered.value[pageNumber] = true;
    emit('page-rendered', pageNumber);
  } catch (err) {
    if (err?.name !== 'RenderingCancelledException') {
      console.error(`Error rendering page ${pageNumber}:`, err);
    }
  }
}

/**
 * WPS Office-Style Paragraph Block Grouping:
 * Groups lines into logical Paragraph Blocks with bounding boxes and reflow capability.
 */
function extractParagraphBlocks(pageNumber, textContent, viewport) {
  const rawItems = [];

  for (let idx = 0; idx < textContent.items.length; idx++) {
    const item = textContent.items[idx];
    if (!item.str) continue;

    // Method A: Retain whitespace items if they represent fill-in-the-blank gaps (> 15px)
    const isBlankRunWithWidth = !item.str.trim() && (item.str.length >= 2 || (item.width && item.width * viewport.scale > 15));
    if (!item.str.trim() && !isBlankRunWithWidth) continue;

    const tx = pdfjsLib.Util.transform(viewport.transform, item.transform);
    const fontSizePdf = (item.height && item.height > 0)
      ? item.height
      : (Math.hypot(item.transform[2], item.transform[3]) || 11.0);
    const fontSizeViewport = fontSizePdf * viewport.scale;

    const x = tx[4];
    const y = tx[5] - (fontSizeViewport * 0.82);
    const width = Math.max(item.width * viewport.scale, 4);
    const height = Math.max(fontSizeViewport * 0.98, 10);

    const pdfX = x / viewport.scale;
    const pdfY = y / viewport.scale;
    const pdfWidth = width / viewport.scale;
    const pdfHeight = height / viewport.scale;

    rawItems.push({
      itemIndex: idx,
      str: item.str,
      fontName: item.fontName || 'Helvetica',
      fontSize: fontSizePdf,
      viewportFontSize: fontSizeViewport,
      bold: (item.fontName || '').toLowerCase().includes('bold'),
      italic: (item.fontName || '').toLowerCase().includes('italic') || (item.fontName || '').toLowerCase().includes('oblique'),
      x, y, width, height,
      pdfX, pdfY, pdfWidth, pdfHeight
    });
  }

  // 1. Group items on the same baseline into Rows
  rawItems.sort((a, b) => {
    const tol = Math.min(a.viewportFontSize * 0.35, 3.0 * viewport.scale);
    const diffY = a.y - b.y;
    if (Math.abs(diffY) > tol) return diffY;
    return a.x - b.x;
  });

  const rows = [];
  for (const item of rawItems) {
    let matchedRow = null;
    const tol = Math.min(item.viewportFontSize * 0.35, 3.0 * viewport.scale);
    for (const row of rows) {
      if (Math.abs(item.y - row.baseY) <= tol) {
        matchedRow = row;
        break;
      }
    }
    if (matchedRow) {
      matchedRow.items.push(item);
    } else {
      rows.push({
        baseY: item.y,
        items: [item]
      });
    }
  }
  rows.sort((a, b) => a.baseY - b.baseY);

  // 2. Partition rows into Column Segments (preserving table columns)
  const lines = [];
  for (let rIdx = 0; rIdx < rows.length; rIdx++) {
    const row = rows[rIdx];
    row.items.sort((a, b) => a.x - b.x);

    let currentLineItems = [row.items[0]];
    for (let i = 1; i < row.items.length; i++) {
      const prevItem = row.items[i - 1];
      const currItem = row.items[i];
      const gap = currItem.x - (prevItem.x + prevItem.width);
      const gapThreshold = Math.max(prevItem.viewportFontSize * 2.2, 35 * viewport.scale);

      if (gap > gapThreshold) {
        lines.push(createLineFromItems(currentLineItems, viewport));
        currentLineItems = [currItem];
      } else {
        currentLineItems.push(currItem);
      }
    }
    if (currentLineItems.length > 0) {
      lines.push(createLineFromItems(currentLineItems, viewport));
    }
  }

  // 2.5 Method A: Detect and expand blank horizontal lines (trailing punctuation or underscore)
  for (const line of lines) {
    const trimmed = (line.text || '').trim();
    const isOnlyPunct = /^[,\.;:\-_–—\s]+$/.test(trimmed) || trimmed === '';
    const isUnderscoreLine = /^_{2,}[,\.;:\-_–—\s]*$/.test(trimmed);

    if (isOnlyPunct || isUnderscoreLine) {
      // Find candidate lines in the same column to get the left alignment (e.g. "HindustanCopperLtd.,")
      let targetLeftX = null;
      const candidatesAbove = lines.filter(l =>
        l !== line &&
        l.y < line.y &&
        (line.y - l.y) <= 120 * viewport.scale &&
        l.x < line.x - 20 * viewport.scale &&
        (!/^[,\.;:\-_–—\s]+$/.test((l.text || '').trim()) || l.isBlankLine)
      );
      if (candidatesAbove.length > 0) {
        candidatesAbove.sort((a, b) => b.y - a.y);
        targetLeftX = candidatesAbove[0].x;
      } else {
        const candidatesBelow = lines.filter(l =>
          l !== line &&
          l.y > line.y &&
          (l.y - line.y) <= 120 * viewport.scale &&
          l.x < line.x - 20 * viewport.scale &&
          (!/^[,\.;:\-_–—\s]+$/.test((l.text || '').trim()) || l.isBlankLine)
        );
        if (candidatesBelow.length > 0) {
          candidatesBelow.sort((a, b) => a.y - b.y);
          targetLeftX = candidatesBelow[0].x;
        }
      }

      // If standalone punctuation sits to the right of column start, expand from targetLeftX
      if (targetLeftX != null && line.x > targetLeftX + 25 * viewport.scale) {
        const origRight = line.x + line.width;
        line.x = targetLeftX;
        line.width = origRight - targetLeftX;
        line.pdfX = line.x / viewport.scale;
        line.pdfWidth = line.width / viewport.scale;
        line.isBlankLine = true;
        line.transparentBackground = true;
        line.originalPunctuation = trimmed;
        line.text = '';
      } else if (isUnderscoreLine || (isOnlyPunct && line.width >= 50 * viewport.scale)) {
        line.isBlankLine = true;
        line.transparentBackground = true;
        line.originalPunctuation = trimmed;
        line.text = '';
      }

      if (line.isBlankLine) {
        // Text sits cleanly ABOVE the line:
        // Position bottom boundary of dashed box directly on the horizontal line
        const lineBaseY = line.y + (line.viewportFontSize * 0.82);
        line.height = Math.max(line.viewportFontSize * 1.25, 14);
        line.y = lineBaseY - line.height + (1.5 * viewport.scale);
        line.pdfY = line.y / viewport.scale;
        line.pdfHeight = line.height / viewport.scale;
      }
    }
  }

  // 3. Group consecutive lines into unified Paragraph Blocks
  lines.sort((a, b) => {
    const diffY = a.y - b.y;
    if (Math.abs(diffY) > 5) return diffY;
    return a.x - b.x;
  });

  const blocks = [];
  let currentBlock = null;

  for (const line of lines) {
    if (!currentBlock) {
      currentBlock = createBlockFromLine(line, pageNumber, blocks.length);
      continue;
    }

    const verticalGap = line.y - (currentBlock.y + currentBlock.height);
    // Paragraph lines have gap around 8-14px. Gaps > 15px indicate new paragraphs or headings.
    const maxGap = Math.min(currentBlock.viewportFontSize * 1.05, 14.5 * viewport.scale);
    const leftDiff = Math.abs(line.x - currentBlock.x);
    const maxLeftDiff = Math.max(30 * viewport.scale, 25);

    const isNumberedList = /^(\d+[\.\)]|\([a-zA-Z0-9]+\)|[•\-\*])\s+/.test(line.text.trim());
    const isHeading = Math.abs(line.viewportFontSize - currentBlock.viewportFontSize) > 2.0;
    const isHeaderChange = (currentBlock.lines.length === 1 && currentBlock.bold !== line.bold);
    const isBlockUppercase = currentBlock.lines.length === 1 &&
                             currentBlock.text.trim().length > 6 &&
                             /^[A-Z0-9\s,\.\-–—\(\)\/]+$/.test(currentBlock.text.trim());
    const isLineUppercase = line.text.trim().length > 6 &&
                            /^[A-Z0-9\s,\.\-–—\(\)\/]+$/.test(line.text.trim());

    // Do NOT merge blank lines into other blocks so each blank line remains distinct
    const shouldMerge = !line.isBlankLine &&
                        !currentBlock.isBlankLine &&
                        (verticalGap >= -3 && verticalGap <= maxGap) &&
                        (leftDiff <= maxLeftDiff || (line.x >= currentBlock.x - 5 && line.x <= currentBlock.x + currentBlock.width)) &&
                        !isNumberedList &&
                        !isHeading &&
                        !isHeaderChange &&
                        !isBlockUppercase &&
                        !isLineUppercase;

    if (shouldMerge) {
      currentBlock.lines.push(line);
      const joinSeparator = currentBlock.text.endsWith('-') ? '' : ' ';
      currentBlock.text = `${currentBlock.text}${joinSeparator}${line.text}`;
      currentBlock.originalText = currentBlock.text;

      const newMinX = Math.min(currentBlock.x, line.x);
      const newMaxX = Math.max(currentBlock.x + currentBlock.width, line.x + line.width);
      const newMinY = Math.min(currentBlock.y, line.y);
      const newMaxY = Math.max(currentBlock.y + currentBlock.height, line.y + line.height);

      currentBlock.x = newMinX;
      currentBlock.y = newMinY;
      currentBlock.width = newMaxX - newMinX;
      currentBlock.height = newMaxY - newMinY;

      currentBlock.originalX = currentBlock.x;
      currentBlock.originalY = currentBlock.y;
      currentBlock.originalWidth = currentBlock.width;
      currentBlock.originalHeight = currentBlock.height;

      currentBlock.pdfX = currentBlock.x / viewport.scale;
      currentBlock.pdfY = currentBlock.y / viewport.scale;
      currentBlock.pdfWidth = currentBlock.width / viewport.scale;
      currentBlock.pdfHeight = currentBlock.height / viewport.scale;

      currentBlock.originalPdfX = currentBlock.pdfX;
      currentBlock.originalPdfY = currentBlock.pdfY;
      currentBlock.originalPdfWidth = currentBlock.pdfWidth;
      currentBlock.originalPdfHeight = currentBlock.pdfHeight;
      currentBlock.currentHeight = currentBlock.height;
    } else {
      blocks.push(currentBlock);
      currentBlock = createBlockFromLine(line, pageNumber, blocks.length);
    }
  }
  if (currentBlock) {
    blocks.push(currentBlock);
  }

  // 4. Restore any existing edits for this page
  for (const block of blocks) {
    if (editedBlocksState[block.id]) {
      const state = editedBlocksState[block.id];
      block.text = state.newText;
      block.isEdited = (block.text !== block.originalText) || Boolean(state.isMoved);
      block.deltaY = state.deltaY || 0;
      block.currentHeight = state.currentHeight || block.originalHeight;
      if (state.isMoved) {
        block.isMoved = true;
        block.customX = state.customX;
        block.customY = state.customY;
        block.x = state.customX;
        block.y = state.customY;
      }
    }
  }

  pageBlocksMap.value[pageNumber] = blocks;
}

/**
 * Method B: Canvas / Vector Horizontal Line Scanner for Pure Vector Lines
 * Scans pristine canvas for horizontal dark lines not covered by text blocks.
 */
function scanAndAppendCanvasBlankLines(pageNumber, pristineCanvas, viewport) {
  if (!pristineCanvas) return;
  const pCtx = pristineCanvas.getContext('2d');
  if (!pCtx) return;

  const w = pristineCanvas.width;
  const h = pristineCanvas.height;
  if (w <= 0 || h <= 0) return;

  let imgData;
  try {
    imgData = pCtx.getImageData(0, 0, w, h);
  } catch {
    return;
  }
  const data = imgData.data;
  const dpr = w / viewport.width;

  const minLineWidthPx = Math.floor(55 * viewport.scale * dpr);
  const maxLineHeightPx = Math.ceil(4 * dpr);

  const existingBlocks = pageBlocksMap.value[pageNumber] || [];
  const detectedLines = [];

  const startY = Math.floor(h * 0.04);
  const endY = Math.floor(h * 0.96);
  const stepY = Math.max(1, Math.round(dpr));

  for (let py = startY; py < endY; py += stepY) {
    let runStart = -1;
    for (let px = 0; px < w; px++) {
      const idx = (py * w + px) * 4;
      const r = data[idx];
      const g = data[idx + 1];
      const b = data[idx + 2];
      const a = data[idx + 3];

      const isDark = a > 180 && r < 110 && g < 110 && b < 110;

      if (isDark) {
        if (runStart === -1) runStart = px;
      } else {
        if (runStart !== -1) {
          const runLength = px - runStart;
          if (runLength >= minLineWidthPx) {
            let isThin = true;
            const checkYUp = py - maxLineHeightPx - 1;
            const checkYDown = py + maxLineHeightPx + 1;
            if (checkYUp >= 0) {
              const uIdx = (checkYUp * w + Math.floor((runStart + px) / 2)) * 4;
              if (data[uIdx + 3] > 180 && data[uIdx] < 110) isThin = false;
            }
            if (checkYDown < h) {
              const dIdx = (checkYDown * w + Math.floor((runStart + px) / 2)) * 4;
              if (data[dIdx + 3] > 180 && data[dIdx] < 110) isThin = false;
            }

            if (isThin) {
              detectedLines.push({
                x1: runStart,
                x2: px,
                y: py,
                width: runLength
              });
            }
          }
          runStart = -1;
        }
      }
    }
  }

  if (detectedLines.length === 0) return;

  // Deduplicate lines within 3px vertically
  const mergedLines = [];
  for (const line of detectedLines) {
    const existing = mergedLines.find(m => 
      Math.abs(m.y - line.y) <= (3 * dpr) &&
      Math.max(m.x1, line.x1) <= Math.min(m.x2, line.x2)
    );
    if (!existing) {
      mergedLines.push(line);
    }
  }

  let appendedCount = 0;
  for (const line of mergedLines) {
    const lineX = line.x1 / dpr;
    const lineWidth = (line.x2 - line.x1) / dpr;
    const lineY = line.y / dpr;

    // Check if an existing block already covers this line
    const isCovered = existingBlocks.some(b => {
      const bBottom = b.y + b.height;
      const bTop = b.y;
      const yOverlap = Math.abs(bBottom - lineY) <= (10 * viewport.scale) ||
                       (lineY >= bTop - 4 && lineY <= bBottom + 4);
      const xOverlap = Math.max(b.x, lineX) < (Math.min(b.x + b.width, lineX + lineWidth) - (15 * viewport.scale));
      return yOverlap && xOverlap;
    });

    if (isCovered) continue;

    // Check if text sits directly above this line (underline under existing text)
    const hasTextImmediatelyAbove = existingBlocks.some(b => {
      const bBottom = b.y + b.height;
      const isDirectlyAbove = (lineY - bBottom) >= 0 && (lineY - bBottom) <= (6 * viewport.scale);
      const xOverlap = Math.max(b.x, lineX) < Math.min(b.x + b.width, lineX + lineWidth);
      return isDirectlyAbove && xOverlap && !b.isBlankLine;
    });

    if (hasTextImmediatelyAbove) continue;

    const fontSize = 11.0;
    const viewportFontSize = fontSize * viewport.scale;
    const blockHeight = Math.max(viewportFontSize * 1.25, 14);
    const blockY = lineY - blockHeight + (1.5 * viewport.scale);
    const blockIndex = existingBlocks.length + appendedCount;
    const blockId = `p${pageNumber}-vblank-${blockIndex}`;

    const newBlock = {
      id: blockId,
      pageNumber,
      blockIndex,
      lines: [],
      text: '',
      originalText: '',
      originalPunctuation: '',
      isBlankLine: true,
      transparentBackground: true,
      x: lineX,
      y: blockY,
      width: lineWidth,
      height: blockHeight,
      originalX: lineX,
      originalY: blockY,
      originalWidth: lineWidth,
      originalHeight: blockHeight,
      pdfX: lineX / viewport.scale,
      pdfY: blockY / viewport.scale,
      pdfWidth: lineWidth / viewport.scale,
      pdfHeight: blockHeight / viewport.scale,
      originalPdfX: lineX / viewport.scale,
      originalPdfY: blockY / viewport.scale,
      originalPdfWidth: lineWidth / viewport.scale,
      originalPdfHeight: blockHeight / viewport.scale,
      fontSize: fontSize,
      viewportFontSize: viewportFontSize,
      fontName: 'Helvetica',
      bold: false,
      italic: false,
      color: '#000000',
      isEdited: false,
      deltaY: 0,
      currentHeight: blockHeight
    };

    existingBlocks.push(newBlock);
    appendedCount++;
  }

  if (appendedCount > 0) {
    pageBlocksMap.value[pageNumber] = existingBlocks;
  }
}

function createLineFromItems(items, viewport) {
  const minX = Math.min(...items.map(it => it.x));
  const maxX = Math.max(...items.map(it => it.x + it.width));
  const minY = Math.min(...items.map(it => it.y));
  const maxY = Math.max(...items.map(it => it.y + it.height));
  const text = items.map(it => it.str).join(' ');
  const first = items[0];

  return {
    items,
    text,
    x: minX,
    y: minY,
    width: maxX - minX,
    height: maxY - minY,
    pdfX: minX / viewport.scale,
    pdfY: minY / viewport.scale,
    pdfWidth: (maxX - minX) / viewport.scale,
    pdfHeight: (maxY - minY) / viewport.scale,
    fontSize: first.fontSize,
    viewportFontSize: first.viewportFontSize,
    fontName: first.fontName,
    bold: first.bold,
    italic: first.italic
  };
}

function createBlockFromLine(line, pageNumber, blockIndex) {
  const blockId = `p${pageNumber}-b${blockIndex}`;
  return {
    id: blockId,
    pageNumber,
    blockIndex,
    lines: [line],
    text: line.text,
    originalText: line.originalPunctuation || line.originalText || line.text,
    originalPunctuation: line.originalPunctuation || '',
    isBlankLine: Boolean(line.isBlankLine),
    transparentBackground: Boolean(line.transparentBackground || line.isBlankLine),
    x: line.x,
    y: line.y,
    width: line.width,
    height: line.height,
    originalX: line.x,
    originalY: line.y,
    originalWidth: line.width,
    originalHeight: line.height,
    pdfX: line.pdfX,
    pdfY: line.pdfY,
    pdfWidth: line.pdfWidth,
    pdfHeight: line.pdfHeight,
    originalPdfX: line.pdfX,
    originalPdfY: line.pdfY,
    originalPdfWidth: line.pdfWidth,
    originalPdfHeight: line.pdfHeight,
    fontSize: line.fontSize,
    viewportFontSize: line.viewportFontSize,
    fontName: line.fontName,
    bold: line.bold,
    italic: line.italic,
    color: '#000000',
    isEdited: false,
    deltaY: 0,
    currentHeight: line.height
  };
}

// Watchers
watch(
  () => documentStore.originalPdfData,
  async (newData) => {
    if (newData && newData.byteLength > 0) {
      userRotations.value = {};
      isPageCanvasRendered.value = {};
      pageBlocksMap.value = {};
      await renderAllPages();
    }
  },
  { immediate: true }
);

watch(
  () => props.zoom,
  async (newZoom) => {
    if (currentPdfDoc) {
      for (let p = 1; p <= numPages.value; p++) {
        const rot = (userRotations.value[p] || 0) % 360;
        const vp = pageViewports.value[p];
        if (vp && vp.rawWidth && vp.rawHeight) {
          const isRotated90or270 = rot === 90 || rot === 270;
          const pw = isRotated90or270 ? vp.rawHeight : vp.rawWidth;
          const ph = isRotated90or270 ? vp.rawWidth : vp.rawHeight;
          vp.width = pw * newZoom;
          vp.height = ph * newZoom;
        }
      }

      for (let p = 1; p <= numPages.value; p++) {
        unrenderPage(p);
      }

      await nextTick();
      scheduleVisiblePagesRender();
    }
  }
);

onMounted(async () => {
  if (typeof window !== 'undefined') {
    window.__buildRedactionPayload = buildRedactionPayload;
    window.__documentStore = documentStore;
  }
  if (documentStore.originalPdfData && documentStore.originalPdfData.byteLength > 0) {
    await renderAllPages();
  }
});

onBeforeUnmount(() => {
  if (pageObserver) {
    pageObserver.disconnect();
    pageObserver = null;
  }
  if (renderDebounceTimer) {
    clearTimeout(renderDebounceTimer);
    renderDebounceTimer = null;
  }
  for (const p in renderTasks) {
    try {
      renderTasks[p].cancel();
    } catch {}
  }
});

defineExpose({
  buildRedactionPayload,
  getRedactionEdits: buildRedactionPayload
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

/* Dedicated Whiteout Mask Layer */
.pdf-page-whiteout-layer {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
  z-index: 5;
}

.whiteout-mask-box {
  position: absolute;
  background-color: #ffffff;
  pointer-events: none;
  box-sizing: border-box;
}

/* Interactive Paragraph Overlay Layer */
.pdf-page-overlay {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: auto;
  z-index: 10;
}

/* WPS Office-Style Live Paragraph Block */
.wps-paragraph-block {
  position: absolute;
  box-sizing: border-box;
  cursor: text;
  border-radius: 2px;
  overflow: visible;
  pointer-events: auto;
  user-select: text;
  border: 1px dashed #64748b;
  transition: border-color 0.15s ease, background-color 0.15s ease, box-shadow 0.15s ease;
}

.wps-paragraph-block:hover {
  border-color: #2563eb;
  background-color: rgba(37, 99, 235, 0.04);
}

.wps-paragraph-block.is-editing {
  border: 1.5px solid #2563eb;
  background-color: #ffffff;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.18);
  z-index: 35 !important;
}

.wps-paragraph-block.is-edited {
  border: 1px dashed #10b981;
  background-color: #ffffff;
  z-index: 25;
}

.wps-paragraph-block.is-moved {
  border: 1.5px dashed #8b5cf6;
  background-color: #ffffff;
  box-shadow: 0 2px 8px rgba(139, 92, 246, 0.15);
  z-index: 25;
}

.wps-paragraph-block.is-dragging {
  cursor: grabbing !important;
  opacity: 0.92;
  box-shadow: 0 6px 16px rgba(37, 99, 235, 0.3);
  border: 1.5px dashed #2563eb !important;
  z-index: 50 !important;
}

/* 4-Way Move / Drag Handle UI */
.block-drag-handle {
  position: absolute;
  top: -15px;
  left: -1px;
  width: 20px;
  height: 15px;
  background-color: #3b82f6;
  color: #ffffff;
  border-radius: 3px 3px 0 0;
  display: none;
  align-items: center;
  justify-content: center;
  cursor: grab;
  user-select: none;
  touch-action: none;
  z-index: 40;
  transition: background-color 0.15s ease, transform 0.1s ease;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.25);
}

.block-drag-handle:hover {
  background-color: #1d4ed8;
  transform: scale(1.05);
}

.block-drag-handle:active,
.wps-paragraph-block.is-dragging .block-drag-handle {
  cursor: grabbing;
  background-color: #1e40af;
}

.wps-paragraph-block:hover .block-drag-handle,
.wps-paragraph-block.is-active .block-drag-handle,
.wps-paragraph-block.is-editing .block-drag-handle,
.wps-paragraph-block.is-dragging .block-drag-handle {
  display: flex;
}

.icon-drag-move {
  width: 12px;
  height: 12px;
  pointer-events: none;
}

.wps-paragraph-block.is-blank-line,
.wps-paragraph-block.is-blank-line.is-editing,
.wps-paragraph-block.is-blank-line.is-edited,
.wps-paragraph-block.transparent-bg,
.wps-paragraph-block.transparent-bg.is-editing,
.wps-paragraph-block.transparent-bg.is-edited {
  background-color: transparent !important;
}

.wps-paragraph-block.is-active {
  border-color: #2563eb;
}

/* Direct In-Place Live Textarea (No popups, zero clipping) */
.wps-paragraph-textarea {
  width: 100%;
  height: 100%;
  box-sizing: border-box;
  padding: 0;
  margin: 0;
  border: none;
  outline: none;
  background: transparent;
  resize: none;
  overflow: hidden;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
  line-height: 1.22;
  color: #000000;
  cursor: text;
  font-family: inherit;
}

/* Display Mode for Edited Paragraph */
.wps-paragraph-display {
  width: 100%;
  height: 100%;
  position: relative;
  box-sizing: border-box;
  padding: 0;
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
  line-height: 1.22;
  color: #000000;
  cursor: text;
}

.wps-paragraph-content {
  white-space: pre-wrap;
  word-break: break-word;
  overflow-wrap: anywhere;
  line-height: 1.22;
}

.wps-paragraph-ghost {
  width: 100%;
  height: 100%;
  position: relative;
  cursor: text;
}

.sr-only-block-text {
  position: absolute;
  width: 100%;
  height: 100%;
  opacity: 0;
  pointer-events: none;
  user-select: text;
  font-size: 0;
  line-height: 0;
}

.hover-hint {
  display: none;
  position: absolute;
  top: -22px;
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
  z-index: 50;
}

.wps-paragraph-block:hover .hover-hint {
  display: block;
}

/* 1-Click Revert Button on Edited Paragraph */
.btn-revert-block {
  position: absolute;
  top: -9px;
  right: -9px;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background-color: #ef4444;
  color: #ffffff;
  font-size: 13px;
  font-weight: 700;
  line-height: 1;
  display: none;
  align-items: center;
  justify-content: center;
  border: 1.5px solid #ffffff;
  cursor: pointer;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.3);
  z-index: 30;
}

.wps-paragraph-block.is-edited:hover .btn-revert-block {
  display: flex;
}

.btn-revert-block:hover {
  background-color: #dc2626;
  transform: scale(1.15);
}

/* Page Loading Spinner while canvas paints */
.page-loading-mask {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background: rgba(15, 23, 42, 0.7);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 12px;
  color: #94a3b8;
  font-size: 13px;
  font-weight: 500;
  z-index: 100;
}

.spinner-sm {
  width: 24px;
  height: 24px;
  border: 2px solid rgba(255, 255, 255, 0.2);
  border-top-color: #6366f1;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.page-offscreen-placeholder {
  width: 100%;
  height: 100%;
  background: #ffffff;
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 40px;
  box-sizing: border-box;
}

.placeholder-skeleton-body {
  display: flex;
  flex-direction: column;
  gap: 16px;
  width: 100%;
}

.skeleton-shimmer-bar {
  height: 14px;
  background: linear-gradient(90deg, #f1f5f9 25%, #e2e8f0 50%, #f1f5f9 75%);
  background-size: 200% 100%;
  animation: shimmer 1.5s infinite;
  border-radius: 3px;
  width: 100%;
}

.skeleton-shimmer-bar.short { width: 45%; }
.skeleton-shimmer-bar.medium { width: 75%; }

@keyframes shimmer {
  0% { background-position: 200% 0; }
  100% { background-position: -200% 0; }
}

.placeholder-meta-tag {
  display: flex;
  justify-content: center;
  font-size: 11px;
  color: #94a3b8;
  font-weight: 500;
  letter-spacing: 0.05em;
  text-transform: uppercase;
}
</style>
