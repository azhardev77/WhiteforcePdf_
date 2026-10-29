<template>
  <div
    class="pdf-canvas-editor-container"
    ref="containerRef"
    @mousedown="onBackgroundMouseDown"
    @click="onBackgroundClick"
  >
    <!-- Pages Scroller -->
    <div
      class="pages-wrapper"
      @mousedown="onBackgroundMouseDown"
      @click="onBackgroundClick"
    >
      <div
        v-for="pageIndex in numPages"
        :key="pageIndex"
        :id="`pdf-page-${pageIndex}`"
        :data-page="pageIndex"
        class="pdf-page-card"
        :style="getPageCardStyle(pageIndex)"
        @mousedown="onBackgroundMouseDown"
        @click="onBackgroundClick"
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
          @mousedown="onBackgroundMouseDown"
          @click="onBackgroundClick"
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
              @mousedown="onBackgroundMouseDown"
              @click="onBackgroundClick"
            >
              <div
                v-for="block in getPageBlocks(pageIndex)"
                :key="block.id"
                :data-block-id="block.id"
                :data-id="block.id"
                :data-text="block.text"
                :data-original-text="block.originalText"
                :data-is-blank-line="block.isBlankLine ? 'true' : 'false'"
                :data-is-moved="block.isMoved ? 'true' : 'false'"
                :data-font-size="block.customFontSize || block.fontSize"
                :data-has-collision="checkBlockCollision(block) ? 'true' : 'false'"
                :title="block.text"
                class="wps-paragraph-block overlay-text-box"
                :class="{
                  'is-editing': editingBlockId === block.id,
                  'is-edited': isBlockEdited(block),
                  'is-hovered': hoveredBlockId === block.id && editingBlockId !== block.id,
                  'is-active': documentStore.activeBlockId === block.id,
                  'is-blank-line': block.isBlankLine,
                  'is-moved': block.isMoved,
                  'is-dragging': draggingBlockId === block.id,
                  'has-collision': checkBlockCollision(block)
                }"
                :style="getParagraphBlockStyle(block)"
                @mouseenter="hoveredBlockId = block.id"
                @mouseleave="hoveredBlockId = null"
                @mousedown.stop="onBlockMouseDown(block, $event)"
                @click.stop="onParagraphClick(block, $event)"
              >
                <!-- Floating Formatting Mini-Toolbar on Active / Editing Block -->
                <div
                  v-if="editingBlockId === block.id || documentStore.activeBlockId === block.id"
                  class="block-floating-toolbar"
                  :style="getFloatingToolbarStyle(block)"
                  data-testid="block-floating-toolbar"
                  @pointerdown.stop
                  @mousedown.stop.prevent
                >
                  <button
                    type="button"
                    class="btn-toolbar-tool btn-font-dec"
                    data-testid="btn-font-dec"
                    title="Decrease font size by 1pt (A-)"
                    @mousedown.stop.prevent
                    @click.stop.prevent="decreaseFontSize(block)"
                  >
                    A-
                  </button>

                  <input
                    type="number"
                    class="toolbar-font-size-input"
                    title="Font size (pt)"
                    :value="Math.round(block.customFontSize || block.fontSize || 12)"
                    min="6"
                    max="120"
                    @mousedown.stop
                    @click.stop
                    @change="e => changeBlockFontSize(block, Number(e.target.value))"
                  />

                  <button
                    type="button"
                    class="btn-toolbar-tool btn-font-inc"
                    data-testid="btn-font-inc"
                    title="Increase font size by 1pt (A+)"
                    @mousedown.stop.prevent
                    @click.stop.prevent="increaseFontSize(block)"
                  >
                    A+
                  </button>

                  <button
                    type="button"
                    class="btn-toolbar-tool btn-auto-shrink"
                    data-testid="btn-auto-shrink"
                    title="Auto-shrink font size to fit container"
                    @mousedown.stop.prevent
                    @click.stop.prevent="autoShrinkToFit(block)"
                  >
                    Auto-fit
                  </button>

                  <div class="toolbar-divider" />

                  <button
                    type="button"
                    class="btn-toolbar-tool btn-font-bold"
                    :class="{ 'is-active': block.bold }"
                    title="Bold (B)"
                    @mousedown.stop.prevent
                    @click.stop.prevent="toggleBold(block)"
                  >
                    <b>B</b>
                  </button>

                  <button
                    type="button"
                    class="btn-toolbar-tool btn-font-italic"
                    :class="{ 'is-active': block.italic }"
                    title="Italic (I)"
                    @mousedown.stop.prevent
                    @click.stop.prevent="toggleItalic(block)"
                  >
                    <i>I</i>
                  </button>

                  <div class="toolbar-divider" />

                  <label class="toolbar-color-label" title="Change text color">
                    <input
                      type="color"
                      class="toolbar-color-input"
                      :value="getBlockColorHex(block)"
                      @mousedown.stop
                      @click.stop
                      @input="e => setBlockColor(block, e.target.value)"
                    />
                    <span class="color-preview-pip" :style="{ backgroundColor: getBlockColorHex(block) }" />
                  </label>

                  <div class="toolbar-divider" />

                  <!-- Revert this block -->
                  <button
                    type="button"
                    class="btn-toolbar-tool btn-toolbar-revert btn-floating-revert"
                    data-testid="btn-floating-revert"
                    :disabled="!isBlockEdited(block)"
                    title="Revert paragraph (1 click)"
                    @mousedown.stop.prevent
                    @click.stop.prevent="revertBlock(block)"
                  >
                    ↺ Revert
                  </button>

                  <!-- Delete this block -->
                  <button
                    type="button"
                    class="btn-toolbar-tool btn-toolbar-delete btn-floating-delete"
                    data-testid="btn-floating-delete"
                    title="Delete paragraph text"
                    @mousedown.stop.prevent
                    @click.stop.prevent="deleteBlock(block)"
                  >
                    ✕ Delete
                  </button>

                  <!-- Collision Warning Badge -->
                  <div
                    v-if="checkBlockCollision(block)"
                    class="toolbar-collision-badge"
                    data-testid="collision-warning-badge"
                    title="Text overlaps downstream elements"
                  >
                    <svg class="icon-sm text-amber" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                      <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
                      <line x1="12" y1="9" x2="12" y2="13" />
                      <line x1="12" y1="17" x2="12.01" y2="17" />
                    </svg>
                    <span>Text overlaps downstream elements</span>
                  </div>
                </div>

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

                <!-- Corner Resize Handle (Visible when active/editing) -->
                <div
                  v-if="editingBlockId === block.id || documentStore.activeBlockId === block.id"
                  class="block-resize-handle"
                  data-testid="block-resize-handle"
                  title="Drag to resize block"
                  @pointerdown.stop.prevent="startResizeBlock(block, $event)"
                />

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
                    @mousedown.stop
                    @click.stop
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
                      @mousedown.stop
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
import { ref, watch, nextTick, onMounted, onBeforeUnmount, toRaw } from 'vue';
import * as pdfjsLib from 'pdfjs-dist';
import pdfWorker from 'pdfjs-dist/build/pdf.worker.mjs?url';
import { useDocumentStore } from '@/stores/document';
import { useHistoryStore } from '@/stores/history';
import { redactPIIFromText, hasPII } from '@/utils/piiRedactor';
import sessionStorageService from '@/services/sessionStorageService';
import { computeLines, splitParagraphAtHeight, measureTextWidth as layoutMeasureWidth } from '@/services/textLayoutEngine';
import {
  performCascadingReflow,
  PAGE_TOP_MARGIN,
  PAGE_BOTTOM_MARGIN_OFFSET,
  getPageBottomMargin,
  cloneBlock,
  captureFlowSnapshot
} from '@/services/documentFlowEngine';

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
const historyStore = useHistoryStore();

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

// Track blocks that have already undergone initial PII automatic redaction
const autoRedactedBlockIds = new Set();

const PAGE_BOTTOM_MARGIN = 40; // points

let blurTimer = null;
let sessionSaveDebounceTimer = null;
let preEditSnapshot = null;

function captureCurrentSnapshot() {
  return {
    blockPatches: Array.from((documentStore.blockPatches || new Map()).entries()),
    edits: Array.isArray(documentStore.edits) ? JSON.parse(JSON.stringify(documentStore.edits)) : [],
    documentPages: documentStore.documentModel?.pages ? JSON.parse(JSON.stringify(documentStore.documentModel.pages)) : [],
    pageBlocksMap: JSON.parse(JSON.stringify(pageBlocksMap.value)),
    editedBlocksState: JSON.parse(JSON.stringify(editedBlocksState)),
    numPages: numPages.value,
    timestamp: Date.now()
  };
}

function createSnapshotCommand(description, beforeSnapshot, afterSnapshot) {
  return {
    id: `cmd_${Date.now()}_${Math.random().toString(36).substr(2, 6)}`,
    description: description || 'Document edit',
    execute() {
      if (afterSnapshot) applyHistorySnapshot(afterSnapshot);
    },
    undo() {
      if (beforeSnapshot) applyHistorySnapshot(beforeSnapshot);
    },
    redo() {
      if (afterSnapshot) applyHistorySnapshot(afterSnapshot);
    }
  };
}

function recordCommand(description, beforeSnapshot, afterSnapshot) {
  if (!beforeSnapshot || !afterSnapshot) return;
  const cmd = createSnapshotCommand(description, beforeSnapshot, afterSnapshot);
  historyStore.executeCommand(cmd);
  documentStore.pushHistorySnapshot(beforeSnapshot);
}

function recordHistorySnapshot() {
  documentStore.pushHistorySnapshot(captureCurrentSnapshot());
}

function getEditingBlock() {
  if (!editingBlockId.value) return null;
  for (const pageBlocks of Object.values(pageBlocksMap.value)) {
    const b = pageBlocks.find(item => item.id === editingBlockId.value);
    if (b) return b;
  }
  return null;
}

function applyHistorySnapshot(snapshot) {
  if (!snapshot) return;
  documentStore.applySnapshot(snapshot);

  if (snapshot.pageBlocksMap) {
    pageBlocksMap.value = JSON.parse(JSON.stringify(snapshot.pageBlocksMap));
  }
  if (snapshot.editedBlocksState) {
    for (const k of Object.keys(editedBlocksState)) {
      delete editedBlocksState[k];
    }
    Object.assign(editedBlocksState, JSON.parse(JSON.stringify(snapshot.editedBlocksState)));
  }
  if (snapshot.numPages != null) {
    const oldNum = numPages.value;
    numPages.value = snapshot.numPages;
    if (snapshot.numPages < oldNum) {
      for (let p = snapshot.numPages + 1; p <= oldNum; p++) {
        delete pageBlocksMap.value[p];
        delete canvasRefs.value[p];
        delete pageViewports.value[p];
        delete isPageCanvasRendered.value[p];
        delete visiblePages.value[p];
        delete pristineCanvasMap[p];
      }
    }
  }

  editingBlockId.value = null;
  activeBlockText.value = '';
  activeDeltaY.value = 0;

  for (let p = 1; p <= numPages.value; p++) {
    const canvas = canvasRefs.value[p];
    const pristine = pristineCanvasMap[p];
    if (canvas && pristine) {
      const ctx = canvas.getContext('2d');
      if (ctx) {
        ctx.save();
        ctx.setTransform(1, 0, 0, 1, 0, 0);
        ctx.drawImage(pristine, 0, 0);
        ctx.restore();
      }
    }
    // Re-paint whiteout for any edited blocks on this page
    const pBlocks = pageBlocksMap.value[p] || [];
    for (const b of pBlocks) {
      if (isBlockEdited(b) || Boolean(editedBlocksState[b.id]) || b.isOverflowChild) {
        paintBlockOnCanvas(b, '#ffffff');
      }
    }
  }

  reflowVersion.value++;
  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
  scheduleSessionSave();
}

function executeUndo() {
  if (editingBlockId.value) {
    commitActiveBlock();
  }
  if (historyStore.canUndo) {
    historyStore.undo();
  } else if (documentStore.canUndo) {
    const currentSnapshot = captureCurrentSnapshot();
    const prevSnapshot = documentStore.undo(currentSnapshot);
    if (prevSnapshot) {
      applyHistorySnapshot(prevSnapshot);
    }
  }
}

function executeRedo() {
  if (editingBlockId.value) {
    commitActiveBlock();
  }
  if (historyStore.canRedo) {
    historyStore.redo();
  } else if (documentStore.canRedo) {
    const currentSnapshot = captureCurrentSnapshot();
    const nextSnapshot = documentStore.redo(currentSnapshot);
    if (nextSnapshot) {
      applyHistorySnapshot(nextSnapshot);
    }
  }
}

function handleEditorKeydown(e) {
  const isCtrlOrMeta = e.ctrlKey || e.metaKey;
  if (!isCtrlOrMeta) return;

  const key = (e.key || '').toLowerCase();
  if (key === 'z') {
    e.preventDefault();
    if (editingBlockId.value) {
      commitActiveBlock();
    }
    if (e.shiftKey) {
      executeRedo();
    } else {
      executeUndo();
    }
  } else if (key === 'y') {
    e.preventDefault();
    if (editingBlockId.value) {
      commitActiveBlock();
    }
    executeRedo();
  }
}

function scheduleSessionSave() {
  if (sessionSaveDebounceTimer) {
    clearTimeout(sessionSaveDebounceTimer);
  }
  sessionSaveDebounceTimer = setTimeout(() => {
    saveCurrentSession();
    sessionSaveDebounceTimer = null;
  }, 150);
}

function saveCurrentSession() {
  if (!documentStore.originalPdfData || !documentStore.hasDocument) return;

  try {
    const currentEdits = buildRedactionPayload();
    const rawPdf = toRaw(documentStore.originalPdfData);
    const sessionData = {
      fileKey: documentStore.fileKey,
      pdfBytes: rawPdf,
      fileName: documentStore.fileName || 'document.pdf',
      documentModel: toRaw(documentStore.documentModel),
      edits: currentEdits,
      blockPatches: Array.from((documentStore.blockPatches || new Map()).entries()),
      editedBlocksState: JSON.parse(JSON.stringify(editedBlocksState)),
      pageBlocksMap: JSON.parse(JSON.stringify(pageBlocksMap.value)),
      autoRedactedBlockIds: Array.from(autoRedactedBlockIds),
      viewSettings: {
        zoom: props.zoom || documentStore.zoom || 1.25,
        userRotations: { ...userRotations.value }
      },
      updatedAt: Date.now()
    };

    sessionStorageService.saveSession(sessionData);
  } catch (err) {
    console.warn('[Session] Failed to auto-save session:', err);
  }
}

function isPageVisible(pageIndex) {
  if (editingBlockId.value && pageBlocksMap.value[pageIndex]?.some(b => b.id === editingBlockId.value)) {
    return true;
  }
  return Boolean(visiblePages.value[pageIndex]);
}

function getPageBlocks(pageIndex) {
  const blocks = pageBlocksMap.value[pageIndex] || [];
  return blocks.filter(b => {
    if (!b) return false;
    if (b.isBlankLine) return true;
    const txt = (b.text != null ? b.text : b.originalText) || '';
    const trimmed = txt.trim();
    if ((trimmed.length === 0 || /^[\s\u200B-\u200D\uFEFF]+$/.test(trimmed)) && !isBlockEdited(b)) {
      return false;
    }
    return true;
  });
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
    return Boolean((block.isEdited || editedBlocksState[block.id]) && block.text && block.text.trim());
  }
  const hasStyleChange = (block.customFontSize != null) ||
                         (block.customColor != null) ||
                         (block.customWidth != null) ||
                         (block.bold !== block.originalBold) ||
                         (block.italic !== block.originalItalic);
  const hasTextChanged = (block.text || '').trim() !== (block.originalText || '').trim();
  const isEditedFlag = Boolean(block.isEdited || editedBlocksState[block.id]);
  return Boolean((isEditedFlag || hasTextChanged || hasStyleChange) && (hasTextChanged || hasStyleChange || block.isMoved));
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
  if (pageViewports.value[1]?.rawWidth && pageViewports.value[1]?.rawHeight) {
    return { width: pageViewports.value[1].rawWidth, height: pageViewports.value[1].rawHeight };
  }
  if (props.documentModel && props.documentModel.pages) {
    const page = props.documentModel.pages.find(p => (p.pageNumber || 0) === pageNumber) 
      || props.documentModel.pages[pageNumber - 1]
      || props.documentModel.pages[0];
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

function getMaxAvailableWidth(block) {
  const pageBlocks = pageBlocksMap.value[block.pageNumber] || [];
  const dims = getPageDimensions(block.pageNumber);
  const pageW = dims.width || 600;
  const currentLeft = block.customX != null ? block.customX : block.originalX;
  const currentY = block.customY != null ? block.customY : block.originalY;
  const currentH = block.originalHeight || 20;

  // Find blocks on the same horizontal band (Y overlap) that are to the right of current block
  const rightCandidates = pageBlocks.filter(b => {
    if (b.id === block.id) return false;
    const bLeft = b.customX != null ? b.customX : b.originalX;
    const bY = b.customY != null ? b.customY : b.originalY;
    const bH = b.originalHeight || 20;
    const bBottom = bY + bH;
    const currentBottom = currentY + currentH;
    const yOverlap = Math.max(0, Math.min(bBottom, currentBottom) - Math.max(bY, currentY));
    return bLeft > currentLeft && yOverlap > 4;
  });

  if (rightCandidates.length > 0) {
    rightCandidates.sort((a, b) => {
      const aLeft = a.customX != null ? a.customX : a.originalX;
      const bLeft = b.customX != null ? b.customX : b.originalX;
      return aLeft - bLeft;
    });
    const nextOnRight = rightCandidates[0];
    const nextLeft = nextOnRight.customX != null ? nextOnRight.customX : nextOnRight.originalX;
    return Math.max(block.originalWidth, nextLeft - currentLeft - 12);
  }

  return Math.max(block.originalWidth, pageW - currentLeft - 24);
}

function getMaxAllowedWidth(block) {
  return getMaxAvailableWidth(block);
}

let _measureCanvas = null;
function measureTextWidth(text, fontSize, fontName, bold, italic) {
  if (!text) return 20;
  if (typeof document === 'undefined') return text.length * fontSize * 0.6;
  if (!_measureCanvas) {
    _measureCanvas = document.createElement('canvas');
  }
  const ctx = _measureCanvas.getContext('2d');
  const weight = bold ? 'bold' : 'normal';
  const style = italic ? 'italic' : 'normal';
  ctx.font = `${style} ${weight} ${Math.round(fontSize)}px ${fontName || 'Helvetica, Arial, sans-serif'}`;
  return ctx.measureText(text).width;
}

function rgbToHex(r, g, b) {
  const toHex = (n) => Math.min(255, Math.max(0, Math.round(n))).toString(16).padStart(2, '0');
  return `#${toHex(r)}${toHex(g)}${toHex(b)}`;
}

function mapPdfFontToCssFamily(fontName) {
  if (!fontName) return 'Helvetica, Arial, sans-serif';
  const lower = fontName.toLowerCase();
  if (lower.includes('times') || lower.includes('roman') || lower.includes('georgia') || lower.includes('serif')) {
    return 'Times New Roman, Georgia, serif';
  }
  if (lower.includes('courier') || lower.includes('mono')) {
    return 'Courier New, monospace';
  }
  if (lower.includes('calibri')) {
    return 'Calibri, Candara, Segoe UI, sans-serif';
  }
  if (lower.includes('inter')) {
    return 'Inter, sans-serif';
  }
  if (lower.includes('arial')) {
    return 'Arial, Helvetica, sans-serif';
  }
  return 'Helvetica, Arial, sans-serif';
}

function isFontBold(fontName) {
  if (!fontName) return false;
  const lower = fontName.toLowerCase();
  return lower.includes('bold') || lower.includes('medi') || lower.includes('semibold') || lower.includes('heavy') || lower.includes('black') || lower.includes('700');
}

function isFontItalic(fontName) {
  if (!fontName) return false;
  const lower = fontName.toLowerCase();
  return lower.includes('italic') || lower.includes('oblique');
}

function getBlockColorHex(block) {
  if (!block) return '#000000';
  return block.customColor || block.originalColor || block.color || '#000000';
}

function sampleTextColorFromCanvas(pristineCanvas, x, y, width, height, dpr = 1) {
  if (!pristineCanvas || width <= 0 || height <= 0) return null;
  const ctx = pristineCanvas.getContext('2d');
  if (!ctx) return null;

  try {
    const sx = Math.max(0, Math.floor(x * dpr));
    const sy = Math.max(0, Math.floor(y * dpr));
    const sw = Math.min(pristineCanvas.width - sx, Math.max(1, Math.floor(width * dpr)));
    const sh = Math.min(pristineCanvas.height - sy, Math.max(1, Math.floor(height * dpr)));
    if (sw <= 0 || sh <= 0) return null;

    const imgData = ctx.getImageData(sx, sy, sw, sh);
    const data = imgData.data;

    let coloredR = 0, coloredG = 0, coloredB = 0, coloredCount = 0;
    let darkR = 0, darkG = 0, darkB = 0, darkCount = 0;

    for (let i = 0; i < data.length; i += 4) {
      const r = data[i];
      const g = data[i + 1];
      const b = data[i + 2];
      const a = data[i + 3];

      if (a < 80) continue;
      if (r > 235 && g > 235 && b > 235) continue;

      const maxC = Math.max(r, g, b);
      const minC = Math.min(r, g, b);
      if (maxC - minC > 25) {
        coloredR += r;
        coloredG += g;
        coloredB += b;
        coloredCount++;
      } else {
        darkR += r;
        darkG += g;
        darkB += b;
        darkCount++;
      }
    }

    if (coloredCount >= 6 || (coloredCount > 0 && coloredCount >= darkCount * 0.12)) {
      return rgbToHex(coloredR / coloredCount, coloredG / coloredCount, coloredB / coloredCount);
    }
    if (darkCount > 0) {
      return rgbToHex(darkR / darkCount, darkG / darkCount, darkB / darkCount);
    }
  } catch (err) {
    console.warn('Canvas pixel color sampling failed:', err);
  }
  return null;
}

function decreaseFontSize(block) {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }
  editingBlockId.value = block.id;
  documentStore.activeBlockId = block.id;
  const currentSize = Math.round(block.customFontSize || block.fontSize || 12);
  const newSize = Math.max(6, currentSize - 1);
  changeBlockFontSize(block, newSize);
}

function increaseFontSize(block) {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }
  editingBlockId.value = block.id;
  documentStore.activeBlockId = block.id;
  const currentSize = Math.round(block.customFontSize || block.fontSize || 12);
  const newSize = Math.min(120, currentSize + 1);
  changeBlockFontSize(block, newSize);
}

function changeBlockFontSize(block, newSize) {
  if (!newSize || newSize < 6 || newSize > 120) return;
  const beforeSnapshot = captureCurrentSnapshot();
  block.customFontSize = newSize;
  block.fontSize = newSize;
  block.viewportFontSize = newSize * (props.zoom || 1);
  block.isEdited = true;

  const isSingleLine = !block.lines || block.lines.length <= 1;
  if (isSingleLine && !block.customWidth) {
    const maxW = getMaxAvailableWidth(block);
    const currentText = editingBlockId.value === block.id ? activeBlockText.value : block.text;
    const measured = measureTextWidth(currentText, block.viewportFontSize, block.fontFamily, block.bold, block.italic) + 16;
    block.width = Math.min(maxW, Math.max(block.originalWidth, measured));
  }

  if (editingBlockId.value === block.id) {
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
        block.deltaY = extraH;
        block.currentHeight = currentH;
        activeDeltaY.value = extraH;
      }
      updatePageVerticalReflow(block.pageNumber);
    });
  } else {
    reflowVersion.value++;
    updatePageVerticalReflow(block.pageNumber);
  }

  editedBlocksState[block.id] = {
    ...(editedBlocksState[block.id] || {}),
    newText: block.text,
    deltaY: block.deltaY || 0,
    currentHeight: block.currentHeight || block.originalHeight,
    customFontSize: block.customFontSize,
    customColor: block.customColor,
    customWidth: block.customWidth,
    bold: block.bold,
    italic: block.italic
  };

  documentStore.setPatch(block.pageNumber, block.id, {
    newText: block.text,
    originalText: block.originalText,
    deltaY: block.deltaY || 0,
    currentHeight: block.currentHeight || block.originalHeight,
    customFontSize: block.customFontSize,
    fontSize: block.fontSize,
    customColor: block.customColor,
    customWidth: block.customWidth,
    bold: block.bold,
    italic: block.italic,
    isEdited: true
  });

  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
  recordCommand('Change font size', beforeSnapshot, captureCurrentSnapshot());
  saveCurrentSession();
}

function toggleBold(block) {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }
  const beforeSnapshot = captureCurrentSnapshot();
  editingBlockId.value = block.id;
  documentStore.activeBlockId = block.id;
  block.bold = !block.bold;
  block.isEdited = isBlockEdited(block);
  reflowVersion.value++;
  editedBlocksState[block.id] = {
    ...(editedBlocksState[block.id] || {}),
    newText: block.text,
    deltaY: block.deltaY || 0,
    currentHeight: block.currentHeight || block.originalHeight,
    bold: block.bold,
    italic: block.italic
  };
  documentStore.setPatch(block.pageNumber, block.id, {
    newText: block.text,
    originalText: block.originalText,
    deltaY: block.deltaY || 0,
    currentHeight: block.currentHeight || block.originalHeight,
    customFontSize: block.customFontSize,
    fontSize: block.fontSize,
    customColor: block.customColor,
    customWidth: block.customWidth,
    bold: block.bold,
    italic: block.italic,
    isEdited: true
  });
  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
  recordCommand('Toggle bold', beforeSnapshot, captureCurrentSnapshot());
  saveCurrentSession();
}

function toggleItalic(block) {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }
  const beforeSnapshot = captureCurrentSnapshot();
  editingBlockId.value = block.id;
  documentStore.activeBlockId = block.id;
  block.italic = !block.italic;
  block.isEdited = isBlockEdited(block);
  reflowVersion.value++;
  editedBlocksState[block.id] = {
    ...(editedBlocksState[block.id] || {}),
    newText: block.text,
    deltaY: block.deltaY || 0,
    currentHeight: block.currentHeight || block.originalHeight,
    bold: block.bold,
    italic: block.italic
  };
  documentStore.setPatch(block.pageNumber, block.id, {
    newText: block.text,
    originalText: block.originalText,
    deltaY: block.deltaY || 0,
    currentHeight: block.currentHeight || block.originalHeight,
    customFontSize: block.customFontSize,
    fontSize: block.fontSize,
    customColor: block.customColor,
    customWidth: block.customWidth,
    bold: block.bold,
    italic: block.italic,
    isEdited: true
  });
  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
  recordCommand('Toggle italic', beforeSnapshot, captureCurrentSnapshot());
  saveCurrentSession();
}

function setBlockColor(block, hexColor) {
  if (!hexColor) return;
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }
  const beforeSnapshot = captureCurrentSnapshot();
  editingBlockId.value = block.id;
  documentStore.activeBlockId = block.id;
  block.customColor = hexColor;
  block.color = hexColor;
  block.isEdited = true;
  reflowVersion.value++;
  editedBlocksState[block.id] = {
    ...(editedBlocksState[block.id] || {}),
    newText: block.text,
    deltaY: block.deltaY || 0,
    currentHeight: block.currentHeight || block.originalHeight,
    customColor: hexColor
  };
  documentStore.setPatch(block.pageNumber, block.id, {
    newText: block.text,
    originalText: block.originalText,
    deltaY: block.deltaY || 0,
    currentHeight: block.currentHeight || block.originalHeight,
    customFontSize: block.customFontSize,
    fontSize: block.fontSize,
    customColor: hexColor,
    customWidth: block.customWidth,
    bold: block.bold,
    italic: block.italic,
    isEdited: true
  });
  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
  recordCommand('Change text color', beforeSnapshot, captureCurrentSnapshot());
  saveCurrentSession();
}

function startResizeBlock(block, event) {
  if (event.button !== 0) return;
  const resizeStartSnapshot = captureCurrentSnapshot();
  const startX = event.clientX;
  const startW = block.customWidth || block.width || block.originalWidth;
  const maxW = getMaxAvailableWidth(block);
  const zoomScale = props.zoom || 1.0;

  function onResizeMove(e) {
    const dx = (e.clientX - startX) / zoomScale;
    const newW = Math.min(maxW, Math.max(30, startW + dx));
    block.customWidth = newW;
    block.width = newW;
    block.isEdited = true;
    reflowVersion.value++;
  }

  function onResizeUp() {
    window.removeEventListener('pointermove', onResizeMove);
    window.removeEventListener('pointerup', onResizeUp);
    window.removeEventListener('pointercancel', onResizeUp);

    if (block.customWidth !== startW) {
      if (resizeStartSnapshot) {
        documentStore.pushHistorySnapshot(resizeStartSnapshot);
      }
    }

    editedBlocksState[block.id] = {
      ...(editedBlocksState[block.id] || {}),
      newText: block.text,
      deltaY: block.deltaY || 0,
      currentHeight: block.currentHeight || block.originalHeight,
      customWidth: block.customWidth
    };
    documentStore.setPatch(block.pageNumber, block.id, {
      newText: block.text,
      originalText: block.originalText,
      deltaY: block.deltaY || 0,
      currentHeight: block.currentHeight || block.originalHeight,
      customFontSize: block.customFontSize,
      fontSize: block.fontSize,
      customColor: block.customColor,
      customWidth: block.customWidth,
      bold: block.bold,
      italic: block.italic,
      isEdited: true
    });
    const edits = buildRedactionPayload();
    documentStore.setEdits(edits);
    saveCurrentSession();
  }

  window.addEventListener('pointermove', onResizeMove);
  window.addEventListener('pointerup', onResizeUp);
  window.addEventListener('pointercancel', onResizeUp);
}

function isContactOrListLine(text) {
  if (!text) return false;
  const t = text.trim();
  if (/^[•●○◦▪▫‣⁃∙·→\-\*]/.test(t) || /^(\d+[\.\)]|\([a-zA-Z0-9]+\))\s+/.test(t)) {
    return true;
  }
  const lower = t.toLowerCase();
  if (lower.includes('@') || lower.includes('linkedin') || lower.includes('github') || lower.includes('leetcode') || lower.includes('scaler') || lower.includes('portfolio') || lower.includes('phone') || /\+\d{2}/.test(lower)) {
    return true;
  }
  return false;
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

function onAddBlankPageCallback(pageNumber, width, height) {
  if (pageNumber > numPages.value) {
    documentStore.addBlankPage(width, height);
    numPages.value = Math.max(numPages.value, pageNumber);
    visiblePages.value[pageNumber] = true;
    pageViewports.value[pageNumber] = {
      width: width * (props.zoom || 1.0),
      height: height * (props.zoom || 1.0),
      rotation: 0,
      rawWidth: width,
      rawHeight: height
    };
    if (!pageBlocksMap.value[pageNumber]) {
      pageBlocksMap.value[pageNumber] = [];
    }
    nextTick(() => {
      renderBlankPage(pageNumber);
    });
  }
}

function onRemoveDynamicPageCallback(pageNumber) {
  if (pageNumber > (currentPdfDoc ? currentPdfDoc.numPages : 1)) {
    documentStore.removeDynamicPage(pageNumber);
    delete pageBlocksMap.value[pageNumber];
    delete canvasRefs.value[pageNumber];
    delete pageViewports.value[pageNumber];
    delete isPageCanvasRendered.value[pageNumber];
    delete visiblePages.value[pageNumber];
    delete pristineCanvasMap[pageNumber];
    if (numPages.value >= pageNumber) {
      numPages.value = pageNumber - 1;
    }
  }
}

function updatePageVerticalReflow(pageNumber, customOptions = {}) {
  const result = performCascadingReflow(
    pageNumber,
    pageBlocksMap.value,
    getPageDimensions,
    {
      onAddBlankPage: onAddBlankPageCallback,
      onRemoveDynamicPage: onRemoveDynamicPageCallback,
      activeEditingId: editingBlockId.value,
      activeDeltaY: activeDeltaY.value,
      activeText: activeBlockText.value,
      totalDocumentPages: currentPdfDoc ? currentPdfDoc.numPages : (numPages.value || 1),
      ...customOptions
    }
  );

  reflowVersion.value++;
  return result;
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

  // Invariant: Canvas dimensions MUST remain strictly fixed to pristine native dimensions (no ribbon stretching)
  canvas.width = pristine.width;
  canvas.height = pristine.height;
  canvas.style.width = `${Math.floor(vp.width)}px`;
  canvas.style.height = `${Math.floor(vp.height)}px`;
  canvas.style.maxHeight = `${Math.floor(vp.height)}px`;
  canvas.style.overflow = 'hidden';

  if (expanded.length === 0) {
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.drawImage(pristine, 0, 0);
  } else {
    const totalScale = (props.zoom || 1.0) * dpr;
    ctx.setTransform(1, 0, 0, 1, 0, 0);
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, canvas.width, canvas.height);

    let prevSourceBottomY = 0;
    let currentDestY = 0;

    for (const exp of expanded) {
      const splitY = Math.round(exp.bottomY * totalScale);
      const sliceHeight = Math.max(0, splitY - prevSourceBottomY);

      if (sliceHeight > 0 && currentDestY < canvas.height) {
        const drawH = Math.min(sliceHeight, canvas.height - currentDestY);
        ctx.drawImage(
          pristine,
          0, prevSourceBottomY,
          pristine.width, drawH,
          0, currentDestY,
          pristine.width, drawH
        );
        currentDestY += sliceHeight;
      }

      currentDestY += Math.round(exp.deltaY * totalScale);
      prevSourceBottomY = splitY;
    }

    const remainingH = Math.max(0, pristine.height - prevSourceBottomY);
    if (remainingH > 0 && currentDestY < canvas.height) {
      const drawH = Math.min(remainingH, canvas.height - currentDestY);
      ctx.drawImage(
        pristine,
        0, prevSourceBottomY,
        pristine.width, drawH,
        0, currentDestY,
        pristine.width, drawH
      );
    }
  }

  // Paint whiteout on canvas for any active or edited blocks on this page
  const pageBlocks = pageBlocksMap.value[pageNumber] || [];
  for (const b of pageBlocks) {
    if (editingBlockId.value === b.id || isBlockEdited(b) || Boolean(editedBlocksState[b.id])) {
      paintBlockOnCanvas(b, '#ffffff', 3);
    }
  }

  // Also paint whiteout for blocks that originally belonged to this page but spilled over to another page
  for (const [pStr, pBlocks] of Object.entries(pageBlocksMap.value)) {
    if (Number(pStr) !== pageNumber) {
      for (const spilled of pBlocks) {
        if (spilled.originalPageNumber === pageNumber) {
          paintBlockOnCanvas({
            ...spilled,
            pageNumber,
            x: spilled.originalX,
            y: spilled.originalY,
            width: spilled.originalWidth,
            height: spilled.originalHeight,
            lines: spilled.originalLines || spilled.lines
          }, '#ffffff', 3);
        }
      }
    }
  }
}

/**
 * Calculates a left-expanded bounding box for fully-redacted PII blocks
 * to cover preceding vector/drawn icons, safely avoiding overlap with left siblings.
 *
 * @param {Object} block - The paragraph block
 * @param {Array<Object>} pageBlocks - All blocks on the same page
 * @returns {{ x: number, y: number, width: number, height: number }} Viewport coordinates
 */
function getLeftExpandedBlockBox(block, pageBlocks) {
  const origX = block.customX != null ? block.customX : (block.originalX != null ? block.originalX : block.x);
  const origY = block.customY != null ? block.customY : (block.originalY != null ? block.originalY : block.y);
  const origW = block.customWidth || block.originalWidth || block.width;
  const origH = block.originalHeight || block.height;

  // Approx 16px–22px left expansion based on line height
  const padLeft = (origH || 14) * 1.6;
  let expandedX = Math.max(0, origX - padLeft);
  const boxTop = origY;
  const boxBottom = origY + origH;

  for (const sibling of pageBlocks) {
    if (sibling.id === block.id) continue;
    // Check if sibling is a valid, non-redacted text block
    const siblingText = (editingBlockId.value === sibling.id ? activeBlockText.value : sibling.text);
    if (!siblingText || siblingText.trim().length === 0) continue;

    const sibX = sibling.customX != null ? sibling.customX : (sibling.originalX != null ? sibling.originalX : sibling.x);
    const sibY = sibling.customY != null ? sibling.customY : (sibling.originalY != null ? sibling.originalY : sibling.y);
    const sibW = sibling.customWidth || sibling.originalWidth || sibling.width;
    const sibH = sibling.originalHeight || sibling.height;
    const sibTop = sibY;
    const sibBottom = sibY + sibH;

    // Check vertical overlap on same baseline / horizontal band
    const yOverlap = Math.max(0, Math.min(boxBottom, sibBottom) - Math.max(boxTop, sibTop));
    if (yOverlap > 3) {
      const sibRight = sibX + sibW;
      // If sibling is to the left of the redacted block
      if (sibRight <= origX && sibRight > expandedX) {
        // Clamp redactX to the right edge of any adjacent left sibling box (+ 2px safety)
        expandedX = Math.min(origX, sibRight + 2);
      }
    }
  }

  const expandedWidth = origW + (origX - expandedX);
  return {
    x: expandedX,
    y: origY,
    width: expandedWidth,
    height: origH
  };
}

/**
 * Helper to get all bounding rects for a block (supports multi-line paragraph blocks).
 */
function getBlockRects(block) {
  if (block.lines && block.lines.length > 1) {
    return block.lines.map(line => ({
      x: line.originalX != null ? line.originalX : line.x,
      y: line.originalY != null ? line.originalY : line.y,
      width: line.originalWidth != null ? line.originalWidth : line.width,
      height: line.originalHeight != null ? line.originalHeight : line.height
    }));
  }
  return [{
    x: block.originalX != null ? block.originalX : block.x,
    y: block.originalY != null ? block.originalY : block.y,
    width: block.originalWidth != null ? block.originalWidth : block.width,
    height: block.originalHeight != null ? block.originalHeight : block.height
  }];
}

/**
 * Paints a solid whiteout box with bleed padding directly onto the underlying PDF.js canvas,
 * erasing any underlying text, descenders, link underlines, or anti-aliasing edges.
 */
function paintBlockOnCanvas(block, color = '#ffffff', bleedPadding = null) {
  if (!block) return;
  const origPage = block.originalPageNumber || block.originalPage || block.pageNumber;
  const canvas = canvasRefs.value[origPage];
  if (!canvas) return;

  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const dpr = window.devicePixelRatio || 1;
  const totalScale = (props.zoom || 1.0) * dpr;
  const bleed = (bleedPadding != null ? bleedPadding : 3) * totalScale;

  ctx.save();
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.fillStyle = color;

  const origX = block.originalX != null ? block.originalX : (block.origX != null ? block.origX : block.x);
  const origY = block.originalY != null ? block.originalY : (block.origY != null ? block.origY : block.y);
  const origW = block.originalWidth != null ? block.originalWidth : (block.origW != null ? block.origW : (block.width || 100));
  const origH = block.originalHeight != null ? block.originalHeight : (block.origH != null ? block.origH : (block.height || 20));

  ctx.fillRect(
    (origX * totalScale) - bleed,
    (origY * totalScale) - bleed,
    (origW * totalScale) + (bleed * 2),
    (origH * totalScale) + (bleed * 2)
  );

  if (block.lines && block.lines.length > 0) {
    for (const line of block.lines) {
      const lx = line.originalX != null ? line.originalX : line.x;
      const ly = line.originalY != null ? line.originalY : line.y;
      const lw = line.originalWidth != null ? line.originalWidth : line.width;
      const lh = line.originalHeight != null ? line.originalHeight : line.height;
      ctx.fillRect(
        (lx * totalScale) - bleed,
        (ly * totalScale) - bleed,
        (lw * totalScale) + (bleed * 2),
        (lh * totalScale) + (bleed * 2)
      );
    }
  }
  ctx.restore();
}

/**
 * Restores the original canvas region for a block from the pristine canvas snapshot,
 * reverting any whiteout paint when an edit is cancelled or blurred without change.
 */
function restoreBlockOnCanvas(block, bleed = 4) {
  if (!block) return;
  const pageNumber = block.pageNumber;
  const canvas = canvasRefs.value[pageNumber];
  const pristine = pristineCanvasMap[pageNumber];
  if (!canvas || !pristine) return;

  const ctx = canvas.getContext('2d');
  if (!ctx) return;
  const dpr = window.devicePixelRatio || 1;
  const totalScale = (props.zoom || 1.0) * dpr;

  const expanded = getPageExpandedEdits(pageNumber);
  if (expanded.length > 0) {
    applyLiveCanvasReflow(pageNumber);
  } else {
    const rects = getBlockRects(block);
    ctx.save();
    ctx.setTransform(1, 0, 0, 1, 0, 0);

    for (const rect of rects) {
      const rx = Math.max(0, Math.floor(rect.x * totalScale - (bleed * dpr)));
      const ry = Math.max(0, Math.floor(rect.y * totalScale - (bleed * dpr)));
      const rw = Math.min(canvas.width - rx, Math.ceil((rect.width * totalScale) + (bleed * 2 * dpr)));
      const rh = Math.min(canvas.height - ry, Math.ceil((rect.height * totalScale) + (bleed * 2 * dpr)));
      if (rw > 0 && rh > 0) {
        ctx.drawImage(pristine, rx, ry, rw, rh, rx, ry, rw, rh);
      }
    }
    ctx.restore();
  }

  // Ensure any other active or edited blocks on this page retain their whiteout paint
  const pageBlocks = pageBlocksMap.value[pageNumber] || [];
  for (const otherBlock of pageBlocks) {
    if (otherBlock.id !== block.id && (isBlockEdited(otherBlock) || editingBlockId.value === otherBlock.id || Boolean(editedBlocksState[otherBlock.id]))) {
      paintBlockOnCanvas(otherBlock, '#ffffff', 3);
    }
  }
}

function getPageWhiteoutMasks(pageIndex) {
  const blocks = pageBlocksMap.value[pageIndex] || [];
  const masks = [];
  const pad = 3; // 3px bleed padding (x - 3, y - 3, width + 6, height + 6)

  // Collect blocks on this page
  const allBlocksToMask = [...blocks];

  // Also include any blocks that originally belonged to this page but spilled over
  for (const [pStr, pBlocks] of Object.entries(pageBlocksMap.value)) {
    if (Number(pStr) !== pageIndex) {
      for (const spilled of pBlocks) {
        if (spilled.originalPageNumber === pageIndex) {
          allBlocksToMask.push({
            ...spilled,
            x: spilled.originalX,
            y: spilled.originalY,
            width: spilled.originalWidth,
            height: spilled.originalHeight,
            lines: spilled.originalLines || spilled.lines,
            forceMask: true
          });
        }
      }
    }
  }

  for (const block of allBlocksToMask) {
    const isEditing = editingBlockId.value === block.id;
    const isEdited = block.forceMask || isBlockEdited(block) || isEditing || Boolean(editedBlocksState[block.id]);
    if (!isEdited) continue;

    // Blank lines: when not actively editing, do not mask line unless edited with text
    if (block.isBlankLine && !isEditing && !isBlockEdited(block) && !block.forceMask) {
      continue;
    }

    const currentText = isEditing ? activeBlockText.value : block.text;
    const isFullyRedacted = block.forceMask || (!currentText || currentText.trim().length === 0);
    const isPiiBlock = hasPII(block.originalText) || autoRedactedBlockIds.has(block.id);

    if (isFullyRedacted && isPiiBlock && !block.forceMask) {
      const exp = getLeftExpandedBlockBox(block, blocks);
      masks.push({
        id: `mask-${block.id}`,
        style: {
          left: `${Math.round(exp.x - pad)}px`,
          top: `${Math.round(exp.y - pad)}px`,
          width: `${Math.round(exp.width + (pad * 2))}px`,
          height: `${Math.round(exp.height + (pad * 2))}px`,
          backgroundColor: '#ffffff'
        }
      });
    } else if (block.lines && block.lines.length > 1) {
      for (let lIdx = 0; lIdx < block.lines.length; lIdx++) {
        const line = block.lines[lIdx];
        const lx = line.originalX != null ? line.originalX : line.x;
        const ly = line.originalY != null ? line.originalY : line.y;
        const lw = line.originalWidth != null ? line.originalWidth : line.width;
        const lh = line.originalHeight != null ? line.originalHeight : line.height;
        masks.push({
          id: lIdx === 0 ? `mask-${block.id}` : `mask-${block.id}-l${lIdx}`,
          style: {
            left: `${Math.round(lx - pad)}px`,
            top: `${Math.round(ly - pad)}px`,
            width: `${Math.round(lw + (pad * 2))}px`,
            height: `${Math.round(lh + (pad * 2))}px`,
            backgroundColor: '#ffffff'
          }
        });
      }
    } else {
      const bx = block.originalX != null ? block.originalX : block.x;
      const by = block.originalY != null ? block.originalY : block.y;
      const bw = block.originalWidth != null ? block.originalWidth : block.width;
      const bh = block.originalHeight != null ? block.originalHeight : block.height;
      masks.push({
        id: `mask-${block.id}`,
        style: {
          left: `${Math.round(bx - pad)}px`,
          top: `${Math.round(by - pad)}px`,
          width: `${Math.round(bw + (pad * 2))}px`,
          height: `${Math.round(bh + (pad * 2))}px`,
          backgroundColor: '#ffffff'
        }
      });
    }
  }
  return masks;
}

function getParagraphBlockStyle(block) {
  const _ = reflowVersion.value;
  const maxW = getMaxAvailableWidth(block);
  const isEditing = editingBlockId.value === block.id;
  const isEdited = isBlockEdited(block) || Boolean(editedBlocksState[block.id]);
  const heightPx = (isEditing || isEdited)
    ? Math.max((isEditing ? (activeDeltaY.value + block.originalHeight) : block.currentHeight) || block.originalHeight, block.originalHeight)
    : block.originalHeight;

  const isSingleLine = !block.lines || block.lines.length <= 1;
  let widthPx = block.customWidth;
  if (!widthPx) {
    if (isSingleLine && (isEditing || isEdited)) {
      const currentText = isEditing ? activeBlockText.value : block.text;
      const measured = measureTextWidth(currentText, block.customFontSize || block.fontSize, block.fontFamily, block.bold, block.italic) + 16;
      widthPx = Math.min(maxW, Math.max(block.originalWidth, measured));
    } else {
      widthPx = Math.max(block.originalWidth, 20);
    }
  }

  const posX = block.customX != null ? block.customX : (block.x != null ? block.x : block.originalX);
  const posY = block.customY != null ? block.customY : (block.y != null ? block.y : block.originalY);

  let zIndexVal = 15;
  if (isEditing) {
    zIndexVal = 50;
  } else if (isEdited) {
    zIndexVal = 25;
  } else if (block.isBlankLine) {
    zIndexVal = 10;
  }

  const styleObj = {
    left: `${Math.round(posX)}px`,
    top: `${Math.round(posY)}px`,
    width: `${Math.round(widthPx)}px`,
    maxWidth: `${Math.round(maxW)}px`,
    height: `${Math.round(heightPx)}px`,
    fontSize: `${block.customFontSize || block.fontSize || 11}px`,
    fontFamily: block.fontFamily || block.fontName || 'Helvetica, Arial, sans-serif',
    fontWeight: block.bold ? 'bold' : 'normal',
    fontStyle: block.italic ? 'italic' : 'normal',
    color: getBlockColorHex(block),
    zIndex: zIndexVal,
    pointerEvents: 'auto'
  };

  if (block.isBlankLine || block.transparentBackground) {
    styleObj.backgroundColor = 'transparent';
  } else if (isEditing) {
    styleObj.backgroundColor = '#ffffff';
    styleObj.opacity = '1';
    styleObj.zIndex = 50;
  } else if (isEdited) {
    styleObj.backgroundColor = '#ffffff';
    styleObj.opacity = '1';
  }

  return styleObj;
}

function getFloatingToolbarStyle(block) {
  if (!block) return {};
  const currentScale = props.zoom || 1.0;
  const pageIndex = block.pageIndex || block.pageNumber || block.page || 1;
  const dims = getPageDimensions(pageIndex);
  const pageWidth = dims ? dims.width : 595;
  const pageTopMargin = PAGE_TOP_MARGIN || 40;

  const blockY = block.customY != null ? block.customY : (block.originalY != null ? block.originalY : (block.y || 0));
  const blockX = block.customX != null ? block.customX : (block.originalX != null ? block.originalX : (block.x || 0));
  const toolbarWidth = 340;

  let placeBelow = false;
  // 1. If block.y - 45 < pageTopMargin, position below the block
  if (blockY - 45 < pageTopMargin) {
    placeBelow = true;
  } else {
    // 2. Check collision with upstream blocks (e.g. section headings directly above)
    const blocks = pageBlocksMap.value[pageIndex] || [];
    for (const sibling of blocks) {
      if (!sibling || sibling.id === block.id) continue;
      const sibY = sibling.customY != null ? sibling.customY : (sibling.originalY != null ? sibling.originalY : (sibling.y || 0));
      const sibH = sibling.currentHeight || sibling.height || sibling.originalHeight || 14;
      const sibBottom = sibY + sibH;
      // If sibling bottom encroaches the 45px zone above this block
      if (sibBottom > (blockY - 45) && sibY < blockY) {
        const sibX = sibling.customX != null ? sibling.customX : (sibling.originalX != null ? sibling.originalX : (sibling.x || 0));
        const sibW = sibling.customWidth || sibling.width || sibling.originalWidth || 100;
        const toolbarRight = blockX + toolbarWidth;
        const sibRight = sibX + sibW;
        if (Math.max(blockX, sibX) < Math.min(toolbarRight, sibRight)) {
          placeBelow = true;
          break;
        }
      }
    }
  }

  // 3. Clamp horizontal position so toolbar never clips off-screen regardless of zoom or page width:
  // Math.max(8, Math.min(block.x, pageWidth - toolbarWidth - 8))
  const targetX = Math.max(8, Math.min(blockX, pageWidth - toolbarWidth - 8));
  const relativeLeft = targetX - blockX;

  return {
    top: placeBelow ? 'calc(100% + 8px)' : '-42px',
    left: `${Math.round(relativeLeft)}px`,
    zIndex: 100,
    pointerEvents: 'auto'
  };
}

function getParagraphTextareaStyle(block) {
  const linePitch = (block.lines && block.lines.length > 1)
    ? (block.originalHeight / block.lines.length)
    : ((block.customFontSize || block.fontSize || 11) * 1.22);
  return {
    fontSize: `${block.customFontSize || block.fontSize || 11}px`,
    fontFamily: block.fontFamily || block.fontName || 'Helvetica, Arial, sans-serif',
    fontWeight: block.bold ? 'bold' : 'normal',
    fontStyle: block.italic ? 'italic' : 'normal',
    color: getBlockColorHex(block),
    lineHeight: `${Math.round(linePitch)}px`,
    padding: '0',
    margin: '0',
    backgroundColor: '#ffffff',
    opacity: '1',
    zIndex: 50
  };
}

function getParagraphDisplayStyle(block) {
  const linePitch = (block.lines && block.lines.length > 1)
    ? (block.originalHeight / block.lines.length)
    : ((block.customFontSize || block.fontSize || 11) * 1.20);
  return {
    fontSize: `${block.customFontSize || block.fontSize || 11}px`,
    fontFamily: block.fontFamily || block.fontName || 'Helvetica, Arial, sans-serif',
    fontWeight: block.bold ? 'bold' : 'normal',
    fontStyle: block.italic ? 'italic' : 'normal',
    color: getBlockColorHex(block),
    lineHeight: `${Math.round(linePitch)}px`
  };
}

function getPageCardStyle(pageIndex) {
  const currentScale = props.zoom || 1.0;
  const dims = getPageDimensions(pageIndex);
  const scaledWidth = Math.ceil(dims.width * currentScale);
  return {
    width: `${scaledWidth}px`,
    minWidth: `${scaledWidth}px`
  };
}

function getPageStageStyle(pageIndex) {
  const currentScale = props.zoom || 1.0;
  const dims = getPageDimensions(pageIndex);
  const scaledWidth = Math.ceil(dims.width * currentScale);
  const scaledHeight = Math.ceil(dims.height * currentScale);
  return {
    width: `${scaledWidth}px`,
    minWidth: `${scaledWidth}px`,
    maxWidth: `${scaledWidth}px`,
    height: `${scaledHeight}px`,
    minHeight: `${scaledHeight}px`,
    maxHeight: `${scaledHeight}px`,
    position: 'relative',
    overflow: 'hidden'
  };
}

function getPageOverlayStyle(pageIndex) {
  const currentScale = props.zoom || 1.0;
  const dims = getPageDimensions(pageIndex);
  return {
    width: `${Math.ceil(dims.width)}px`,
    height: `${Math.ceil(dims.height)}px`,
    maxHeight: `${Math.ceil(dims.height)}px`,
    overflow: 'hidden',
    transform: `scale(${currentScale})`,
    transformOrigin: 'top left'
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

/**
 * 2D Bounding Box Collision (AABB Check):
 * Calculates whether the active/candidate box breaches sibling downstream elements' 2D bounds.
 * Performs a full 2D Axis-Aligned Bounding Box (AABB) intersection check (both X and Y overlap).
 * Side-by-side columns/links on the same horizontal row do NOT trigger false-positive warnings.
 */
function checkBlockCollision(block) {
  if (!block) return false;
  const pageBlocks = pageBlocksMap.value[block.pageNumber] || [];
  const startX = block.customX != null ? block.customX : (block.originalX != null ? block.originalX : block.x);
  const blockW = block.customWidth || block.width || block.originalWidth || 100;
  const endX = startX + blockW;

  const startY = block.customY != null ? block.customY : (block.originalY != null ? block.originalY : block.y);
  const isEditing = editingBlockId.value === block.id;

  // Calculate effective height including font-size expansion and multi-line wrapping
  const currentFs = block.customFontSize || block.fontSize || 12;
  const origFs = block.originalFontSize || block.fontSize || 12;
  const fsScale = Math.max(1.0, currentFs / (origFs || 12));
  const estimatedFsHeight = (block.originalHeight || 20) * fsScale;

  const currentHeight = isEditing
    ? Math.max(block.currentHeight || block.originalHeight, block.originalHeight + (activeDeltaY.value || 0), estimatedFsHeight)
    : Math.max(block.currentHeight || block.originalHeight || 20, estimatedFsHeight);
  const endY = startY + currentHeight;

  for (const sib of pageBlocks) {
    if (sib.id === block.id) continue;

    const sibStartX = sib.customX != null ? sib.customX : (sib.originalX != null ? sib.originalX : sib.x);
    const sibW = sib.customWidth || sib.width || sib.originalWidth || 100;
    const sibEndX = sibStartX + sibW;

    const sibStartY = sib.customY != null ? sib.customY : (sib.originalY != null ? sib.originalY : sib.y);
    const sibH = sib.currentHeight || sib.originalHeight || sib.height || 20;
    const sibEndY = sibStartY + sibH;

    // Full 2D Axis-Aligned Bounding Box (AABB) intersection:
    // Both horizontal (X) and vertical (Y) intervals must intersect.
    const xOverlap = Math.min(endX, sibEndX) - Math.max(startX, sibStartX);
    const yOverlap = Math.min(endY, sibEndY) - Math.max(startY, sibStartY);

    // Require genuine horizontal intersection (> 6px) to avoid touching boundary edges
    // and vertical intersection (> 2px)
    if (xOverlap > 6 && yOverlap > 2) {
      // Must be an overlap encroaching downstream sibling (endY breaches sibStartY)
      if (endY > sibStartY + 2 && sibStartY >= startY - 2) {
        return true;
      }
    }
  }
  return false;
}

/**
 * Auto-Shrink to Fit Container:
 * Helper function using binary search on font-size based on container scrollHeight <= clientHeight.
 */
function autoShrinkToFit(block) {
  if (!block) return;
  const currentSize = Math.round(block.customFontSize || block.fontSize || 12);
  const targetHeight = block.originalHeight || 24;
  const targetWidth = block.width || block.originalWidth || 200;

  let low = 8.5;
  let high = currentSize;
  let bestSize = low;

  const el = activeBlockTextareaRef.value;
  const textarea = Array.isArray(el) ? el[0] : el;

  while (low <= high) {
    const mid = Math.floor((low + high) / 2);
    let fits = false;

    if (textarea && editingBlockId.value === block.id) {
      const prevFs = textarea.style.fontSize;
      const prevH = textarea.style.height;
      textarea.style.fontSize = `${mid}px`;
      textarea.style.height = `${targetHeight}px`;
      fits = textarea.scrollHeight <= textarea.clientHeight + 2;
      textarea.style.fontSize = prevFs;
      textarea.style.height = prevH;
    } else {
      const text = (editingBlockId.value === block.id ? activeBlockText.value : block.text) || block.originalText || '';
      const charWidth = mid * 0.55;
      const charsPerLine = Math.max(1, Math.floor(targetWidth / charWidth));
      const lineCount = Math.max(1, Math.ceil(text.length / charsPerLine));
      const estimatedHeight = lineCount * (mid * 1.25);
      fits = estimatedHeight <= targetHeight + 2;
    }

    if (fits) {
      bestSize = mid;
      low = mid + 1;
    } else {
      high = mid - 1;
    }
  }

  bestSize = Math.min(bestSize, Math.round(block.originalFontSize || 12));
  changeBlockFontSize(block, bestSize);
  return bestSize;
}

/**
 * Strict Hit-Testing:
 * Verifies that a clicked block is a valid text block extracted from PDF.js getTextContent().
 */
function isValidTextBlock(block) {
  if (!block || !block.id) return false;
  if (block.isBlankLine) return true;
  const hasText = Boolean(
    (block.text && block.text.trim().length > 0) ||
    (block.originalText && block.originalText.trim().length > 0)
  );
  return hasText;
}

function isClickIntersectingBlock(block, event) {
  if (!event || typeof event.clientX !== 'number' || typeof event.clientY !== 'number') return true;
  const target = event.target;
  const blockEl = target?.closest?.('.wps-paragraph-block');
  if (blockEl && (blockEl.getAttribute('data-block-id') === block.id || blockEl.getAttribute('data-id') === block.id)) {
    const rect = blockEl.getBoundingClientRect();
    const tol = 3;
    return (
      event.clientX >= rect.left - tol &&
      event.clientX <= rect.right + tol &&
      event.clientY >= rect.top - tol &&
      event.clientY <= rect.bottom + tol
    );
  }
  return true;
}

function dismissActiveSelection() {
  if (editingBlockId.value) {
    commitActiveBlock();
  }
  documentStore.activeBlockId = null;
  editingBlockId.value = null;
  activeBlockText.value = '';
  activeDeltaY.value = 0;
}

const draggingBlockId = ref(null);

function onBlockMouseDown(block, event) {
  if (event.button !== 0) return; // Only primary mouse button

  // If clicked inside floating toolbar, drag handle, resize handle, or revert button, let them handle it
  if (
    event.target.closest('.block-floating-toolbar') ||
    event.target.closest('.block-drag-handle') ||
    event.target.closest('.block-resize-handle') ||
    event.target.closest('.btn-revert-block')
  ) {
    return;
  }

  // Prevent Re-Selection Loop:
  // If this block is ALREADY in edit mode, do NOT re-trigger startEditingBlock or selectAll!
  // Allow the browser to natively position the cursor where the user clicked.
  if (editingBlockId.value === block.id) {
    return;
  }

  // Fix Block Switching:
  // If another block is currently being edited, commit it immediately on mousedown
  // so its blur timer does not race or swallow the upcoming click!
  if (editingBlockId.value && editingBlockId.value !== block.id) {
    commitActiveBlock();
  }
}

function onBackgroundMouseDown(event) {
  const target = event.target;
  if (!target) return;

  // Don't commit/dismiss if clicking inside active editing block or its toolbar/controls
  if (
    target.closest('.block-floating-toolbar') ||
    target.closest('.block-drag-handle') ||
    target.closest('.block-resize-handle') ||
    target.closest('.wps-paragraph-textarea') ||
    target.closest('.btn-toolbar-tool') ||
    target.closest('.toolbar-font-size-input') ||
    target.closest('.toolbar-color-label')
  ) {
    return;
  }

  // If clicking on another block, let onBlockMouseDown / onParagraphClick handle the switch
  if (target.closest('.wps-paragraph-block')) {
    return;
  }

  // Empty whitespace click: immediately blur/deselect active box and dismiss toolbar
  dismissActiveSelection();
}

function onBackgroundClick(event) {
  const target = event.target;
  if (!target) return;

  if (
    target.closest('.block-floating-toolbar') ||
    target.closest('.block-drag-handle') ||
    target.closest('.block-resize-handle') ||
    target.closest('.wps-paragraph-textarea') ||
    target.closest('.wps-paragraph-block')
  ) {
    return;
  }

  dismissActiveSelection();
}

function startDragBlock(block, event) {
  if (event.button !== 0) return; // Only primary mouse button
  if (editingBlockId.value) {
    commitActiveBlock();
  }

  const dragStartSnapshot = captureCurrentSnapshot();
  draggingBlockId.value = block.id;
  documentStore.activeBlockId = block.id;

  const startX = event.clientX;
  const startY = event.clientY;

  const blockStartX = block.customX != null ? block.customX : (block.x != null ? block.x : block.originalX);
  const blockStartY = block.customY != null ? block.customY : (block.y != null ? block.y : block.originalY);

  const dims = getPageDimensions(block.pageNumber);
  const maxW = dims.width || 800;
  const maxH = dims.height || 1100;
  const zoomScale = props.zoom || 1.0;

  const blockW = block.originalWidth || block.width || 100;
  const blockH = block.originalHeight || block.height || 30;

  const clampMinX = 0;
  const clampMaxX = Math.max(0, maxW - blockW);
  const clampMinY = 0;
  const clampMaxY = Math.max(0, maxH - blockH);

  let hasMoved = false;

  function onPointerMove(e) {
    const dx = (e.clientX - startX) / zoomScale;
    const dy = (e.clientY - startY) / zoomScale;

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

  function onPointerUp() {
    window.removeEventListener('pointermove', onPointerMove);
    window.removeEventListener('pointerup', onPointerUp);
    window.removeEventListener('pointercancel', onPointerUp);

    setTimeout(() => {
      draggingBlockId.value = null;
    }, 50);

    if (hasMoved || block.isMoved) {
      if (dragStartSnapshot) {
        documentStore.pushHistorySnapshot(dragStartSnapshot);
      }
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

      documentStore.setPatch(block.pageNumber, block.id, {
        newText: block.text,
        customX: block.customX,
        customY: block.customY,
        isMoved: true,
        isEdited: true
      });

      const edits = buildRedactionPayload();
      documentStore.setEdits(edits);
      recordCommand('Move block', dragStartSnapshot, captureCurrentSnapshot());
      saveCurrentSession();
    }
  }

  window.addEventListener('pointermove', onPointerMove);
  window.addEventListener('pointerup', onPointerUp);
  window.addEventListener('pointercancel', onPointerUp);
}

function onParagraphClick(block, event) {
  // Requirement 2: Strict hit-testing
  if (!isValidTextBlock(block)) {
    dismissActiveSelection();
    return;
  }
  if (!isClickIntersectingBlock(block, event)) {
    dismissActiveSelection();
    return;
  }
  if (editingBlockId.value === block.id) return;
  if (draggingBlockId.value) return;
  if (editingBlockId.value && editingBlockId.value !== block.id) {
    commitActiveBlock();
  }
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

  preEditSnapshot = captureCurrentSnapshot();
  editingBlockId.value = block.id;
  // Ensure underlying canvas text is completely knocked out with a clean whiteout rect before textarea gains focus
  paintBlockOnCanvas(block, '#ffffff', 3);

  if (block.isBlankLine) {
    const raw = block.text != null ? block.text : block.originalText;
    activeBlockText.value = (/^[,\.;:\-_–—\s]*$/.test(raw || '')) ? '' : raw;
  } else {
    activeBlockText.value = block.text != null ? block.text : block.originalText;
  }
  activeDeltaY.value = block.deltaY || 0;

  const isSingleLine = !block.lines || block.lines.length <= 1;
  if (isSingleLine && !block.customWidth) {
    const maxAvail = getMaxAvailableWidth(block);
    const measured = measureTextWidth(activeBlockText.value, block.customFontSize || block.fontSize, block.fontFamily, block.bold, block.italic) + 16;
    block.width = Math.min(maxAvail, Math.max(block.originalWidth, measured));
  }

  documentStore.activeBlockId = block.id;
  emit('select-item', block);

  nextTick(() => {
    let el = activeBlockTextareaRef.value;
    if (Array.isArray(el)) el = el[0];
    if (el) {
      el.style.height = 'auto';
      const scrollH = el.scrollHeight;
      const baseH = block.originalHeight;
      const isLineWrapped = isSingleLine ? (scrollH > baseH * 1.35) : (scrollH > baseH + 3);
      const currentH = isLineWrapped ? scrollH : baseH;
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

  const linePitch = (block.lines && block.lines.length > 1)
    ? (block.originalHeight / block.lines.length)
    : ((block.customFontSize || block.fontSize || 12) * 1.22);

  const fontSpec = {
    fontSize: block.customFontSize || block.fontSize || 12,
    fontFamily: block.fontFamily || 'Helvetica, Arial, sans-serif',
    bold: Boolean(block.bold),
    italic: Boolean(block.italic),
    lineHeight: Math.round(linePitch)
  };

  const isSingleLine = !block.lines || block.lines.length <= 1;
  const maxAvail = getMaxAvailableWidth(block);
  if (isSingleLine && !block.customWidth && (activeBlockText.value || '').length < 80) {
    const measuredW = layoutMeasureWidth(activeBlockText.value, fontSpec) + 16;
    block.width = Math.min(maxAvail, Math.max(block.originalWidth, measuredW));
  }

  const containerW = block.width || block.originalWidth || maxAvail || 500;
  const computed = computeLines(activeBlockText.value, fontSpec, containerW);

  let currentH = computed.height;
  if (el) {
    el.style.height = 'auto';
    const scrollH = el.scrollHeight;
    const baseH = block.originalHeight || 20;
    const isLineWrapped = isSingleLine ? (scrollH > baseH * 1.35) : (scrollH > baseH + 3);
    currentH = isLineWrapped ? Math.max(scrollH, computed.height) : Math.max(baseH, computed.height);
    el.style.height = `${currentH}px`;
  }

  const deltaFromInput = Math.max(0, currentH - (block.originalHeight || 20));
  activeDeltaY.value = deltaFromInput;
  block.deltaY = deltaFromInput;
  block.currentHeight = currentH;

  const reflowResult = updatePageVerticalReflow(block.pageNumber, {
    activeEditingId: block.id,
    activeText: activeBlockText.value,
    activeDeltaY: deltaFromInput
  });

  if (reflowResult && reflowResult.splitInfo) {
    activeBlockText.value = reflowResult.splitInfo.fitText;
    block.text = reflowResult.splitInfo.fitText;
    block.currentHeight = reflowResult.splitInfo.fitHeight;
    block.deltaY = Math.max(0, reflowResult.splitInfo.fitHeight - (block.originalHeight || 20));
    activeDeltaY.value = block.deltaY;
    if (el) {
      el.value = reflowResult.splitInfo.fitText;
      el.style.height = `${reflowResult.splitInfo.fitHeight}px`;
    }
  } else {
    const effectiveHeight = block.currentHeight || currentH;
    block.currentHeight = effectiveHeight;
    block.deltaY = Math.max(0, effectiveHeight - (block.originalHeight || 20));
    activeDeltaY.value = block.deltaY;
    if (el) {
      el.style.height = `${effectiveHeight}px`;
      if (block.customFontSize) {
        el.style.fontSize = `${block.customFontSize}px`;
      }
    }
  }

  // Paint whiteout on canvas so underlying original raster text never bleeds through
  paintBlockOnCanvas(block, '#ffffff');

  // Synchronize patch with modular edit store
  documentStore.setPatch(block.pageNumber, block.id, {
    newText: activeBlockText.value,
    originalText: block.originalText,
    deltaY: block.deltaY,
    currentHeight: block.currentHeight,
    isEdited: true
  });

  // Synchronize redaction payload with store
  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
  scheduleSessionSave();
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
    targetBlock.currentHeight = (targetBlock.originalHeight || 20) + (targetBlock.deltaY || 0);

    const hasStyleOrPosChanged = Boolean(
      targetBlock.isMoved ||
      targetBlock.customFontSize != null ||
      targetBlock.customColor != null ||
      targetBlock.customWidth != null ||
      targetBlock.bold !== targetBlock.originalBold ||
      targetBlock.italic !== targetBlock.originalItalic
    );
    const hasTextChanged = (targetBlock.text || '').trim() !== (targetBlock.originalText || '').trim();

    if (targetBlock.isBlankLine) {
      if (targetBlock.text && targetBlock.text.trim()) {
        if (preEditSnapshot) {
          recordCommand('Edit line text', preEditSnapshot, captureCurrentSnapshot());
          preEditSnapshot = null;
        }
        targetBlock.isEdited = true;
        editedBlocksState[targetBlock.id] = {
          newText: targetBlock.text,
          deltaY: targetBlock.deltaY,
          currentHeight: targetBlock.currentHeight || targetBlock.originalHeight,
          customFontSize: targetBlock.customFontSize,
          customColor: targetBlock.customColor,
          customWidth: targetBlock.customWidth,
          bold: targetBlock.bold,
          italic: targetBlock.italic
        };
        documentStore.setPatch(targetBlock.pageNumber, targetBlock.id, {
          newText: targetBlock.text,
          originalText: targetBlock.originalText,
          deltaY: targetBlock.deltaY,
          currentHeight: targetBlock.currentHeight || targetBlock.originalHeight,
          customFontSize: targetBlock.customFontSize,
          customColor: targetBlock.customColor,
          customWidth: targetBlock.customWidth,
          bold: targetBlock.bold,
          italic: targetBlock.italic,
          isEdited: true
        });
      } else {
        targetBlock.isEdited = false;
        delete editedBlocksState[targetBlock.id];
        documentStore.deletePatch(targetBlock.pageNumber, targetBlock.id);
        targetBlock.text = targetBlock.originalText;
        targetBlock.deltaY = 0;
        targetBlock.currentHeight = targetBlock.originalHeight;
        preEditSnapshot = null;
      }
    } else if (hasTextChanged || hasStyleOrPosChanged) {
      if (preEditSnapshot) {
        recordCommand('Edit paragraph text', preEditSnapshot, captureCurrentSnapshot());
        preEditSnapshot = null;
      }
      targetBlock.isEdited = true;
      editedBlocksState[targetBlock.id] = {
        newText: targetBlock.text,
        deltaY: targetBlock.deltaY,
        currentHeight: targetBlock.currentHeight || targetBlock.originalHeight,
        customX: targetBlock.customX,
        customY: targetBlock.customY,
        customFontSize: targetBlock.customFontSize,
        customColor: targetBlock.customColor,
        customWidth: targetBlock.customWidth,
        bold: targetBlock.bold,
        italic: targetBlock.italic,
        isMoved: targetBlock.isMoved
      };
      documentStore.setPatch(targetBlock.pageNumber, targetBlock.id, {
        newText: targetBlock.text,
        originalText: targetBlock.originalText,
        deltaY: targetBlock.deltaY,
        currentHeight: targetBlock.currentHeight || targetBlock.originalHeight,
        customX: targetBlock.customX,
        customY: targetBlock.customY,
        customFontSize: targetBlock.customFontSize,
        customColor: targetBlock.customColor,
        customWidth: targetBlock.customWidth,
        bold: targetBlock.bold,
        italic: targetBlock.italic,
        isMoved: targetBlock.isMoved,
        isEdited: true
      });
      // Keep solid whiteout painted on canvas
      paintBlockOnCanvas(targetBlock, '#ffffff', 3);
    } else {
      // No-op edit: unchanged text and style -> revert block, clear state, restore pristine canvas
      targetBlock.text = targetBlock.originalText;
      targetBlock.isEdited = false;
      delete editedBlocksState[targetBlock.id];
      documentStore.deletePatch(targetBlock.pageNumber, targetBlock.id);
      targetBlock.deltaY = 0;
      targetBlock.currentHeight = targetBlock.originalHeight;
      restoreBlockOnCanvas(targetBlock, 4);
      preEditSnapshot = null;
    }
    updatePageVerticalReflow(targetBlock.pageNumber);
  }

  editingBlockId.value = null;
  activeBlockText.value = '';
  activeDeltaY.value = 0;

  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
  saveCurrentSession();
}

function cancelBlockEdit(block) {
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }

  if (editingBlockId.value === block.id) {
    const wasAlreadyEdited = Boolean(editedBlocksState[block.id]);
    activeBlockText.value = block.text;
    editingBlockId.value = null;

    // Rule 3: Restore pristine canvas region on edit cancel
    if (!wasAlreadyEdited) {
      restoreBlockOnCanvas(block, 4);
    }
  }
}

function onParagraphBlur(block) {
  if (blurTimer) {
    clearTimeout(blurTimer);
  }
  blurTimer = setTimeout(() => {
    if (typeof document !== 'undefined' && document.activeElement?.closest?.('.block-floating-toolbar')) {
      return;
    }
    if (editingBlockId.value === block.id) {
      commitActiveBlock();
    }
    blurTimer = null;
  }, 250);
}

function revertBlock(block) {
  if (!block) return;
  const beforeSnapshot = captureCurrentSnapshot();
  const pageIndex = block.pageNumber;
  const blockId = block.id;

  // Requirement 4: Delete patch from modular patch store
  documentStore.deletePatch(pageIndex, blockId);
  delete editedBlocksState[blockId];

  // If this block had spilled from another page, return it back
  if (block.originalPageNumber && block.originalPageNumber !== block.pageNumber) {
    const origPage = block.originalPageNumber;
    pageBlocksMap.value[block.pageNumber] = (pageBlocksMap.value[block.pageNumber] || []).filter(b => b.id !== block.id);
    block.pageNumber = origPage;
    if (!pageBlocksMap.value[origPage]) pageBlocksMap.value[origPage] = [];
    pageBlocksMap.value[origPage].push(block);
  }

  block.text = block.originalText;
  block.isEdited = false;
  block.isMoved = false;
  block.customX = undefined;
  block.customY = undefined;
  block.customWidth = undefined;
  block.width = block.originalWidth;
  block.customFontSize = null;
  block.fontSize = block.originalFontSize || block.fontSize;
  block.viewportFontSize = block.fontSize;
  block.customColor = null;
  block.color = block.originalColor || '#000000';
  block.bold = block.originalBold;
  block.italic = block.originalItalic;
  block.x = block.originalX;
  block.y = block.originalY;
  block.deltaY = 0;
  block.currentHeight = block.originalHeight;

  if (editingBlockId.value === block.id) {
    editingBlockId.value = null;
    activeBlockText.value = '';
    activeDeltaY.value = 0;
  }

  // Restore pristine canvas region
  restoreBlockOnCanvas(block, 4);

  updatePageVerticalReflow(block.pageNumber);
  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
  recordCommand('Revert paragraph block', beforeSnapshot, captureCurrentSnapshot());
  saveCurrentSession();
}

function deleteBlock(block) {
  if (!block) return;
  const beforeSnapshot = captureCurrentSnapshot();
  const pageIndex = block.pageNumber;
  const blockId = block.id;

  block.text = '';
  activeBlockText.value = '';
  block.isEdited = true;
  block.deltaY = 0;
  block.currentHeight = block.originalHeight;

  documentStore.setPatch(pageIndex, blockId, {
    newText: '',
    originalText: block.originalText,
    isEdited: true
  });
  editedBlocksState[blockId] = {
    newText: '',
    deltaY: 0,
    currentHeight: block.originalHeight
  };

  paintBlockOnCanvas(block, '#ffffff');
  updatePageVerticalReflow(pageIndex);

  const edits = buildRedactionPayload();
  documentStore.setEdits(edits);
  recordCommand('Delete paragraph block', beforeSnapshot, captureCurrentSnapshot());
  saveCurrentSession();
}

/**
 * Reverts all blocks across all pages back to their original text and coordinates.
 */
function revertAll() {
  const beforeSnapshot = captureCurrentSnapshot();
  // Requirement 4: Clear all patches simultaneously
  documentStore.clearAllPatches();

  for (const blockId of Object.keys(editedBlocksState)) {
    delete editedBlocksState[blockId];
  }

  // Restore any spilled blocks back to their original pages
  for (const [pageNumStr, blocks] of Object.entries(pageBlocksMap.value)) {
    const pNum = Number(pageNumStr);
    const staying = [];
    for (const b of blocks) {
      if (b.originalPageNumber && b.originalPageNumber !== pNum) {
        b.pageNumber = b.originalPageNumber;
        if (!pageBlocksMap.value[b.originalPageNumber]) pageBlocksMap.value[b.originalPageNumber] = [];
        pageBlocksMap.value[b.originalPageNumber].push(b);
      } else {
        staying.push(b);
      }
    }
    pageBlocksMap.value[pNum] = staying;
  }

  for (const [pageNumStr, blocks] of Object.entries(pageBlocksMap.value)) {
    for (const block of blocks) {
      if (block.isEdited || block.isMoved) {
        block.text = block.originalText;
        block.isEdited = false;
        block.isMoved = false;
        block.customX = undefined;
        block.customY = undefined;
        block.customWidth = undefined;
        block.width = block.originalWidth;
        block.customFontSize = null;
        block.fontSize = block.originalFontSize || block.fontSize;
        block.viewportFontSize = block.fontSize;
        block.customColor = null;
        block.color = block.originalColor || '#000000';
        block.bold = block.originalBold;
        block.italic = block.originalItalic;
        block.x = block.originalX;
        block.y = block.originalY;
        block.deltaY = 0;
        block.currentHeight = block.originalHeight;
      }
    }
    updatePageVerticalReflow(Number(pageNumStr));
  }

  // Restore all pages to pristine canvases and original viewport dimensions
  for (const p in pristineCanvasMap) {
    const canvas = canvasRefs.value[p];
    const pristine = pristineCanvasMap[p];
    if (canvas && pristine) {
      const vp = pageViewports.value[p];
      if (canvas.height !== pristine.height || canvas.width !== pristine.width) {
        canvas.width = pristine.width;
        canvas.height = pristine.height;
        if (vp) {
          canvas.style.width = `${Math.floor(vp.width)}px`;
          canvas.style.height = `${Math.floor(vp.height)}px`;
          canvas.style.maxHeight = `${Math.floor(vp.height)}px`;
          canvas.style.overflow = 'hidden';
        }
      }
      const ctx = canvas.getContext('2d');
      ctx.setTransform(1, 0, 0, 1, 0, 0);
      ctx.drawImage(pristine, 0, 0);
    }
  }
  autoRedactedBlockIds.clear();
  editingBlockId.value = null;
  activeBlockText.value = '';
  activeDeltaY.value = 0;
  reflowVersion.value++;
  documentStore.edits = [];
  documentStore.activeBlockId = null;
  recordCommand('Revert all edits', beforeSnapshot, captureCurrentSnapshot());
  saveCurrentSession();
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
      const origPage = block.originalPageNumber || block.originalPage || pageNumber;
      const isMovedToAnotherPage = origPage !== pageNumber;
      const patch = documentStore.getPatch ? (documentStore.getPatch(pageNumber, block.id) || documentStore.getPatch(origPage, block.id)) : null;
      const isActivelyEditing = editingBlockId.value === block.id;
      const isMoved = Boolean((block.isMoved || patch?.isMoved) && (block.customX != null || block.customY != null || patch?.customX != null || patch?.customY != null));
      const isEdited = isMovedToAnotherPage || Boolean(patch) || isBlockEdited(block) || isActivelyEditing || isMoved;
      if (!isEdited) continue;

      const currentText = isActivelyEditing
        ? activeBlockText.value
        : (patch?.newText !== undefined ? patch.newText : block.text);

      const effectiveFontSize = patch?.customFontSize || patch?.fontSize || block.customFontSize || block.fontSize;
      const effectiveColor = patch?.customColor || patch?.color || block.customColor || block.color;
      const effectiveBold = patch?.bold !== undefined ? patch.bold : block.bold;
      const effectiveItalic = patch?.italic !== undefined ? patch.italic : block.italic;

      const hasTextDiff = (currentText || '').trim() !== (block.originalText || '').trim();
      const hasStyleDiff = Boolean(
        isMoved ||
        isMovedToAnotherPage ||
        (effectiveFontSize && effectiveFontSize !== block.originalFontSize) ||
        effectiveColor ||
        block.customWidth ||
        effectiveBold !== block.originalBold ||
        effectiveItalic !== block.originalItalic
      );
      if (!hasTextDiff && !hasStyleDiff && !patch && !isMovedToAnotherPage) continue;

      if (isMovedToAnotherPage) {
        // 1. On origPage, erase the original text with solid whiteout
        const rawOrigBbox = [
          block.originalPdfX != null ? block.originalPdfX : block.originalX,
          block.originalPdfY != null ? block.originalPdfY : block.originalY,
          block.originalPdfWidth != null ? block.originalPdfWidth : block.originalWidth,
          block.originalPdfHeight != null ? block.originalPdfHeight : block.originalHeight
        ];
        const effOrigBbox = getRotatedBbox(rawOrigBbox, origPage);
        allEdits.push({
          id: `redact-block-${block.id}-clear`,
          lineId: `${block.id}-clear`,
          pageNumber: origPage,
          bbox: effOrigBbox,
          originalBbox: effOrigBbox,
          originalBoundingBox: effOrigBbox,
          originalText: block.originalText,
          newText: '',
          style: {
            fontSize: block.originalFontSize || block.fontSize || 12,
            fontFamily: block.fontFamily || block.fontName || 'Helvetica',
            bold: false,
            italic: false,
            color: '#000000'
          },
          backgroundColor: '#FFFFFF',
          transparentBackground: false,
          isBlankLine: false,
          pageRotation: userRotations.value[origPage] || 0
        });

        // 2. On destination pageNumber, place the new text at the new Y coordinate
        const targetX = block.customX != null ? block.customX : (patch?.customX != null ? patch.customX : block.originalX);
        const targetY = block.y;
        const rawNewBbox = [
          targetX,
          targetY,
          block.originalPdfWidth || block.originalWidth || block.width,
          block.currentHeight || block.originalPdfHeight || block.originalHeight || block.height
        ];
        const effNewBbox = getRotatedBbox(rawNewBbox, pageNumber);
        allEdits.push({
          id: `redact-block-${block.id}`,
          lineId: block.id,
          pageNumber,
          bbox: effNewBbox,
          originalBbox: effNewBbox,
          originalBoundingBox: effNewBbox,
          isMoved: true,
          originalText: '',
          newText: currentText,
          style: {
            fontSize: effectiveFontSize,
            fontFamily: patch?.fontFamily || block.fontFamily || block.fontName || 'Helvetica',
            bold: Boolean(effectiveBold),
            italic: Boolean(effectiveItalic),
            color: effectiveColor ? (effectiveColor.startsWith('#') ? effectiveColor : getBlockColorHex(block)) : getBlockColorHex(block)
          },
          backgroundColor: '#FFFFFF',
          transparentBackground: true,
          isBlankLine: false,
          pageRotation: pageRot,
          reflowed: true
        });
        continue;
      }

      const isFullyRedacted = (!currentText || currentText.trim().length === 0);
      const isPiiBlock = hasPII(block.originalText) || autoRedactedBlockIds.has(block.id);

      let effectiveOriginalBbox;
      let effectiveNewBbox;

      if (isFullyRedacted && isPiiBlock) {
        const exp = getLeftExpandedBlockBox(block, blocks);
        const expPdfX = exp.x;
        const expPdfWidth = exp.width;
        const expPdfY = exp.y;
        const expPdfHeight = exp.height;

        const rawOriginalBbox = [expPdfX, expPdfY, expPdfWidth, expPdfHeight];
        effectiveOriginalBbox = getRotatedBbox(rawOriginalBbox, pageNumber);

        const rawNewBbox = [expPdfX, expPdfY, expPdfWidth, expPdfHeight];
        effectiveNewBbox = getRotatedBbox(rawNewBbox, pageNumber);
      } else {
        const rawOriginalBbox = [
          block.originalPdfX != null ? block.originalPdfX : block.originalX,
          block.originalPdfY != null ? block.originalPdfY : block.originalY,
          block.originalPdfWidth != null ? block.originalPdfWidth : block.originalWidth,
          block.originalPdfHeight != null ? block.originalPdfHeight : block.originalHeight
        ];
        effectiveOriginalBbox = getRotatedBbox(rawOriginalBbox, pageNumber);

        const targetX = block.customX != null ? block.customX : (patch?.customX != null ? patch.customX : block.originalX);
        const targetY = block.customY != null ? block.customY : (patch?.customY != null ? patch.customY : block.y);

        const rawNewBbox = [
          targetX,
          targetY,
          block.originalPdfWidth || block.originalWidth || block.width,
          block.originalPdfHeight || block.originalHeight || block.height
        ];
        effectiveNewBbox = getRotatedBbox(rawNewBbox, pageNumber);
      }

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
          fontSize: effectiveFontSize,
          fontFamily: patch?.fontFamily || block.fontFamily || block.fontName || 'Helvetica',
          bold: Boolean(effectiveBold),
          italic: Boolean(effectiveItalic),
          color: effectiveColor ? (effectiveColor.startsWith('#') ? effectiveColor : getBlockColorHex(block)) : getBlockColorHex(block)
        },
        backgroundColor: '#FFFFFF',
        transparentBackground: Boolean(block.transparentBackground || block.isBlankLine),
        isBlankLine: Boolean(block.isBlankLine),
        pageRotation: pageRot,
        reflowed: (block.deltaY || 0) > 0.5,
        whiteoutBoxes: (block.lines && block.lines.length > 1) ? block.lines.map(line => {
          const rawBox = [line.pdfX, line.pdfY, line.pdfWidth, line.pdfHeight];
          return getRotatedBbox(rawBox, pageNumber);
        }) : undefined
      });
    }
  }

  // Ensure all edits have pageIndex and isNewPage flags
  for (const edit of allEdits) {
    if (edit.pageIndex == null) {
      edit.pageIndex = edit.pageNumber - 1;
    }
    if (edit.isNewPage == null) {
      edit.isNewPage = Boolean(
        edit.pageNumber > (currentPdfDoc ? currentPdfDoc.numPages : 1) ||
        documentStore.documentModel?.pages?.[edit.pageNumber - 1]?.isBlankPage
      );
    }
  }

  // Flag any newly created pages: { pageIndex, isNewPage: true, width, height }
  for (let p = 1; p <= numPages.value; p++) {
    const isNew = Boolean(
      p > (currentPdfDoc ? currentPdfDoc.numPages : 1) ||
      documentStore.documentModel?.pages?.[p - 1]?.isBlankPage
    );
    if (isNew) {
      const hasPageInit = allEdits.some(e => e.pageNumber === p && e.isNewPage);
      if (!hasPageInit) {
        const dims = getPageDimensions(p);
        allEdits.push({
          id: `page-init-${p}`,
          lineId: `page-init-${p}`,
          pageNumber: p,
          pageIndex: p - 1,
          isNewPage: true,
          width: dims.width,
          height: dims.height,
          bbox: [0, 0, dims.width, dims.height],
          originalBbox: [0, 0, dims.width, dims.height],
          newText: '',
          transparentBackground: true,
          backgroundColor: '#FFFFFF',
          isBlankLine: false
        });
      }
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

async function renderAllPages(isRestored = false) {
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
    if (documentStore.documentModel && Array.isArray(documentStore.documentModel.pages)) {
      documentStore.documentModel.pages = documentStore.documentModel.pages.slice(0, currentPdfDoc.numPages);
    }

    await prefetchPageViewports();

    // Initial layout extraction and PII redaction across all pages of the document
    for (let p = 1; p <= numPages.value; p++) {
      try {
        if (isRestored && pageBlocksMap.value[p] && pageBlocksMap.value[p].length > 0) {
          continue;
        }
        if (p > currentPdfDoc.numPages) {
          continue;
        }
        const page = await currentPdfDoc.getPage(p);
        const rot = userRotations.value[p] || 0;
        const baseVp = page.getViewport({ scale: 1.0, rotation: rot });
        const textContent = await page.getTextContent();
        extractParagraphBlocks(p, textContent, baseVp, null, isRestored);
      } catch (err) {
        console.warn(`Initial block extraction for page ${p} failed:`, err);
      }
    }

    // Immediately record auto-redactions/restored edits into store so toolbar counters and Revert All light up
    const currentEdits = buildRedactionPayload();
    documentStore.setEdits(currentEdits);

    visiblePages.value = { 1: true };
    if (numPages.value > 1) {
      visiblePages.value[2] = true;
    }

    await setupIntersectionObserver();
    scheduleVisiblePagesRender();
    saveCurrentSession();
  } catch (err) {
    console.error('Failed to render PDF pages:', err);
    documentStore.errorMessage = `Failed to render PDF: ${err.message}`;
  }
}

async function rotatePage(pageNumber, delta = 90) {
  const currentRot = userRotations.value[pageNumber] || 0;
  const newRot = ((currentRot + delta) % 360 + 360) % 360;
  userRotations.value[pageNumber] = newRot;

  if (currentPdfDoc && pageNumber <= currentPdfDoc.numPages) {
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

  delete pageBlocksMap.value[pageNumber];
  isPageCanvasRendered.value[pageNumber] = false;
  if (isPageVisible(pageNumber)) {
    await renderPage(pageNumber);
  }
  saveCurrentSession();
}

function renderBlankPage(pageNumber) {
  const canvas = canvasRefs.value[pageNumber];
  if (!canvas) return;

  const dims = getPageDimensions(pageNumber);
  const currentScale = props.zoom || 1.0;
  const dpr = window.devicePixelRatio || 1;

  pageViewports.value[pageNumber] = {
    width: dims.width * currentScale,
    height: dims.height * currentScale,
    rotation: 0,
    rawWidth: dims.width,
    rawHeight: dims.height
  };

  canvas.width = Math.floor(dims.width * currentScale * dpr);
  canvas.height = Math.floor(dims.height * currentScale * dpr);
  canvas.style.width = `${Math.floor(dims.width * currentScale)}px`;
  canvas.style.height = `${Math.floor(dims.height * currentScale)}px`;
  canvas.style.maxHeight = `${Math.floor(dims.height * currentScale)}px`;
  canvas.style.overflow = 'hidden';

  const ctx = canvas.getContext('2d');
  ctx.setTransform(1, 0, 0, 1, 0, 0);
  ctx.fillStyle = '#ffffff';
  ctx.fillRect(0, 0, canvas.width, canvas.height);

  const pristineClone = document.createElement('canvas');
  pristineClone.width = canvas.width;
  pristineClone.height = canvas.height;
  const pCtx = pristineClone.getContext('2d');
  pCtx.fillStyle = '#ffffff';
  pCtx.fillRect(0, 0, pristineClone.width, pristineClone.height);
  pristineCanvasMap[pageNumber] = pristineClone;

  isPageCanvasRendered.value[pageNumber] = true;
  if (!pageBlocksMap.value[pageNumber]) {
    pageBlocksMap.value[pageNumber] = [];
  }
}

async function renderPage(pageNumber) {
  if (!isPageVisible(pageNumber)) return;

  if (currentPdfDoc && pageNumber > currentPdfDoc.numPages) {
    renderBlankPage(pageNumber);
    return;
  }

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
    canvas.style.maxHeight = `${Math.floor(viewport.height)}px`;
    canvas.style.overflow = 'hidden';

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

    // Extract text content and group into WPS Office-Style Paragraph Blocks if not already cached
    if (!pageBlocksMap.value[pageNumber] || pageBlocksMap.value[pageNumber].length === 0) {
      const baseViewport = page.getViewport({ scale: 1.0, rotation: pageUserRotation });
      const textContent = await page.getTextContent();
      const isRestored = Boolean(documentStore.isRestoredSession);
      extractParagraphBlocks(pageNumber, textContent, baseViewport, pristineClone, isRestored);
      scanAndAppendCanvasBlankLines(pageNumber, pristineClone, baseViewport);
      updatePageVerticalReflow(pageNumber);
    }

    // Paint whiteout on canvas for any already edited or actively editing blocks on this page
    const pBlocks = pageBlocksMap.value[pageNumber] || [];
    for (const b of pBlocks) {
      if (editingBlockId.value === b.id || isBlockEdited(b) || Boolean(editedBlocksState[b.id])) {
        paintBlockOnCanvas(b, '#ffffff', 3);
      }
    }

    isPageCanvasRendered.value[pageNumber] = true;
    emit('page-rendered', pageNumber);
  } catch (err) {
    if (err?.name !== 'RenderingCancelledException') {
      console.error(`Error rendering page ${pageNumber}:`, err);
    }
  }
}

function areColorsMatching(c1, c2) {
  if (c1 === c2) return true;
  if (!c1 || !c2) return true;
  const parseHex = (hex) => {
    const clean = (hex || '').replace('#', '');
    if (clean.length === 6) {
      return [parseInt(clean.slice(0, 2), 16), parseInt(clean.slice(2, 4), 16), parseInt(clean.slice(4, 6), 16)];
    }
    return [0, 0, 0];
  };
  const [r1, g1, b1] = parseHex(c1);
  const [r2, g2, b2] = parseHex(c2);
  const isDark1 = (r1 < 70 && g1 < 70 && b1 < 70);
  const isDark2 = (r2 < 70 && g2 < 70 && b2 < 70);
  if (isDark1 && isDark2) return true;
  const dist = Math.hypot(r1 - r2, g1 - g2, b1 - b2);
  return dist < 35;
}

/**
 * WPS Office-Style Paragraph Block Grouping:
 * Groups lines into logical Paragraph Blocks with bounding boxes and reflow capability.
 */
function extractParagraphBlocks(pageNumber, textContent, viewport, pristineCanvas = null, isRestored = false) {
  const rawItems = [];

  for (let idx = 0; idx < textContent.items.length; idx++) {
    const item = textContent.items[idx];
    if (!item.str) continue;

    // Method A: Retain whitespace items if they represent fill-in-the-blank gaps (> 15px)
    // PDF.js generates synthetic space items with height === 0 or single space between columns; skip them
    const isSyntheticGap = (item.height === 0) || (item.str === ' ');
    const isBlankRunWithWidth = !item.str.trim() && !isSyntheticGap && (item.width && item.width * viewport.scale > 15);
    if (!item.str.trim() && !isBlankRunWithWidth) continue;

    const tx = pdfjsLib.Util.transform(viewport.transform, item.transform);
    const fontSizePdf = (item.height && item.height > 0)
      ? item.height
      : (Math.hypot(item.transform[2], item.transform[3]) || 11.0);
    const fontSizeViewport = fontSizePdf * viewport.scale;

    const x = tx[4];
    // Baseline is tx[5]; calculate top-left y from exact font ascent (0.85 * fontSize)
    const baselineY = tx[5];
    const y = baselineY - (fontSizeViewport * 0.85);
    const width = Math.max(item.width * viewport.scale, 4);
    const height = Math.max(fontSizeViewport * 1.05, 10);

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
      x, y, baselineY, width, height,
      pdfX, pdfY, pdfWidth, pdfHeight
    });
  }

  // Deduplicate overlapping / shadowed text items (e.g. from previous whiteout saves)
  // If duplicate items share exact coordinates (Math.abs(itemA.x - itemB.x) < 2 && Math.abs(itemA.y - itemB.y) < 2),
  // retain only the latest/topmost stream item (higher itemIndex) and discard underlying shadowed items.
  const shadowedIndices = new Set();
  const coordTol = 2.0;

  for (let i = 0; i < rawItems.length; i++) {
    for (let j = i + 1; j < rawItems.length; j++) {
      const itemA = rawItems[i];
      const itemB = rawItems[j];

      const dx = Math.abs(itemA.x - itemB.x);
      const dy = Math.abs(itemA.y - itemB.y);
      const dBaseY = Math.abs(itemA.baselineY - itemB.baselineY);
      const pdfDx = Math.abs(itemA.pdfX - itemB.pdfX);
      const pdfDy = Math.abs(itemA.pdfY - itemB.pdfY);

      // Exact coordinate overlap in viewport px or PDF pts
      const isExactCoordOverlap = (pdfDx < coordTol && pdfDy < coordTol) ||
                                  (dx < coordTol * viewport.scale && dy < coordTol * viewport.scale) ||
                                  (dx < coordTol * viewport.scale && dBaseY < coordTol * viewport.scale) ||
                                  (dx < coordTol && dy < coordTol);

      // Horizontal baseline containment: if a later stream item covers/replaces an earlier item on the same baseline
      const isSameBaseline = dBaseY < (3.5 * viewport.scale) || pdfDy < 3.5;
      const bCoversA = isSameBaseline &&
                       (itemB.itemIndex > itemA.itemIndex) &&
                       ((Math.abs(itemA.x - itemB.x) < 4.0 * viewport.scale) ||
                        (itemA.x >= itemB.x - (coordTol * viewport.scale) &&
                         (itemA.x + itemA.width) <= (itemB.x + itemB.width + (coordTol * viewport.scale))) ||
                        (Math.max(itemA.x, itemB.x) < Math.min(itemA.x + itemA.width, itemB.x + itemB.width) - (2.0 * viewport.scale)));

      const aCoversB = isSameBaseline &&
                       (itemA.itemIndex > itemB.itemIndex) &&
                       ((Math.abs(itemB.x - itemA.x) < 4.0 * viewport.scale) ||
                        (itemB.x >= itemA.x - (coordTol * viewport.scale) &&
                         (itemB.x + itemB.width) <= (itemA.x + itemA.width + (coordTol * viewport.scale))) ||
                        (Math.max(itemA.x, itemB.x) < Math.min(itemA.x + itemA.width, itemB.x + itemB.width) - (2.0 * viewport.scale)));

      if (isExactCoordOverlap || bCoversA || aCoversB) {
        if (itemA.itemIndex < itemB.itemIndex) {
          shadowedIndices.add(i);
        } else {
          shadowedIndices.add(j);
        }
      }
    }
  }

  // Propagation pass for short-text replacements:
  // If an older stream token was shadowed by a newer stream token, any contiguous older tokens
  // on that same baseline (following with normal word spacing < 35px) were part of the replaced line
  // and must also be shadowed so they never reappear after short-text edits.
  let newlyShadowed = true;
  while (newlyShadowed) {
    newlyShadowed = false;
    for (let i = 0; i < rawItems.length; i++) {
      if (shadowedIndices.has(i)) continue;
      const itemCand = rawItems[i];

      for (const shadowedIdx of Array.from(shadowedIndices)) {
        const itemShad = rawItems[shadowedIdx];
        if (itemCand.itemIndex >= itemShad.itemIndex) continue; // Must be an older item

        const dBaseY = Math.abs(itemCand.baselineY - itemShad.baselineY);
        const isSameBaseline = dBaseY < (3.5 * viewport.scale);
        if (!isSameBaseline) continue;

        // Is itemCand directly adjacent/contiguous to itemShad on the right?
        const gap = itemCand.x - (itemShad.x + itemShad.width);
        const maxWordGap = Math.min(Math.max(itemShad.viewportFontSize * 2.2, 28 * viewport.scale), 35 * viewport.scale);
        if (gap >= -2.0 && gap <= maxWordGap) {
          shadowedIndices.add(i);
          newlyShadowed = true;
          break;
        }
      }
    }
  }

  const cleanRawItems = shadowedIndices.size > 0
    ? rawItems.filter((_, idx) => !shadowedIndices.has(idx))
    : rawItems;

  // 1. Group items on the same baseline into Rows
  cleanRawItems.sort((a, b) => {
    const diffBaseY = a.baselineY - b.baselineY;
    const tol = Math.min(Math.min(a.viewportFontSize, b.viewportFontSize) * 0.35, 3.0 * viewport.scale);
    if (Math.abs(diffBaseY) > tol) return diffBaseY;
    return a.x - b.x;
  });

  const rows = [];
  for (const item of cleanRawItems) {
    let matchedRow = null;
    const tol = Math.min(item.viewportFontSize * 0.35, 3.0 * viewport.scale);
    for (const row of rows) {
      if (Math.abs(item.baselineY - row.baseY) <= tol) {
        matchedRow = row;
        break;
      }
    }
    if (matchedRow) {
      matchedRow.items.push(item);
    } else {
      rows.push({
        baseY: item.baselineY,
        items: [item]
      });
    }
  }
  rows.sort((a, b) => a.baseY - b.baseY);

  // 2. Partition rows into Column Segments (preserving table columns and multi-column headers)
  const lines = [];
  for (let rIdx = 0; rIdx < rows.length; rIdx++) {
    const row = rows[rIdx];
    row.items.sort((a, b) => a.x - b.x);

    let currentLineItems = [row.items[0]];
    for (let i = 1; i < row.items.length; i++) {
      const prevItem = row.items[i - 1];
      const currItem = row.items[i];
      const gap = currItem.x - (prevItem.x + prevItem.width);

      // Coordinate overlap safeguard inside the same row:
      // If two items start at virtually identical X (Math.abs(currItem.x - prevItem.x) < 2),
      // or if currItem substantially overlaps with prevItem (negative gap),
      // retain only the newer/topmost stream item and discard the shadowed item.
      const isCoordOverlap = Math.abs(currItem.x - prevItem.x) < Math.max(2.0, 2.0 * viewport.scale) ||
                             Math.abs(currItem.pdfX - prevItem.pdfX) < 2.0;
      const isNegativeOverlap = currItem.x < (prevItem.x + prevItem.width - Math.max(2.0, 2.0 * viewport.scale));

      if (isCoordOverlap || isNegativeOverlap) {
        if (currItem.itemIndex > prevItem.itemIndex) {
          // currItem is newer: replace prevItem in currentLineItems
          currentLineItems.pop();
          currentLineItems.push(currItem);
        }
        // If prevItem was newer, keep prevItem and discard currItem
        continue;
      }

      // Fix 1: Horizontal Gap Check
      // Standard inter-word space in fonts is 0.25 - 0.40 * fontSize (typically 3-8px).
      // If gap > 35 * scale or > prevItem.fontSize * 2.5, split into separate column segments!
      const maxWordGap = Math.min(Math.max(prevItem.viewportFontSize * 2.2, 28 * viewport.scale), 35 * viewport.scale);
      const isColumnGap = gap > maxWordGap || gap > (35 * viewport.scale);

      // Fix 1: Font Size Mismatch Check
      // If two items on the same baseline differ by > 2.5px or > 25% in font size, they must NEVER be merged!
      const fontSizeDiff = Math.abs(currItem.viewportFontSize - prevItem.viewportFontSize);
      const minFontSize = Math.min(currItem.viewportFontSize, prevItem.viewportFontSize);
      const isFontSizeMismatch = (fontSizeDiff > 2.5 * viewport.scale) || (minFontSize > 0 && (fontSizeDiff / minFontSize) > 0.25);

      // Boldness / Style mismatch across any noticeable gap
      const isStyleMismatch = (prevItem.bold !== currItem.bold && gap > 12 * viewport.scale);

      if (isColumnGap || isFontSizeMismatch || isStyleMismatch) {
        lines.push(createLineFromItems(currentLineItems, viewport, pristineCanvas));
        currentLineItems = [currItem];
      } else {
        currentLineItems.push(currItem);
      }
    }
    if (currentLineItems.length > 0) {
      lines.push(createLineFromItems(currentLineItems, viewport, pristineCanvas));
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
    const maxGap = Math.min(currentBlock.viewportFontSize * 1.15, 14.5 * viewport.scale);

    // Fix 2: Font Size Match Check (Math.abs(line.fontSize - currentBlock.fontSize) <= 1.5 * scale)
    const fontSizeDiff = Math.abs(line.viewportFontSize - currentBlock.viewportFontSize);
    const fontMatches = fontSizeDiff <= (1.5 * viewport.scale);

    // Fix 3: Strict Left Alignment Check (leftDiff <= 6 * scale)
    const leftDiff = Math.abs(line.x - currentBlock.x);
    const isLeftAligned = leftDiff <= (6.0 * viewport.scale);

    // Fix 3: Contact lines, emails, links, and bullet points NEVER merge!
    const isContactOrSpecial = isContactOrListLine(line.text) || isContactOrListLine(currentBlock.text);

    // Fix 3: Only merge full-width prose lines (> 200 * scale). Short / right-aligned lines never merge!
    const isRightSide = (currentBlock.x > 200 * viewport.scale) || (line.x > 200 * viewport.scale);
    let isProseWidth = (currentBlock.originalWidth >= 70 * viewport.scale) && (line.width >= 70 * viewport.scale);
    if (isRightSide) {
      isProseWidth = (currentBlock.originalWidth >= 200 * viewport.scale) && (line.width >= 200 * viewport.scale);
    }

    // Fix 3: Color Matching Check
    const colorMatches = areColorsMatching(line.color, currentBlock.color);

    // Disjoint column check: If line is completely to the right or left of currentBlock
    const isHorizontallyDisjoint = (line.x >= currentBlock.x + currentBlock.width + 10 * viewport.scale) ||
                                   (currentBlock.x >= line.x + line.width + 10 * viewport.scale);

    const isNumberedList = /^(\d+[\.\)]|\([a-zA-Z0-9]+\)|[•●○◦▪▫‣⁃∙·→\-\*])\s*/.test(line.text.trim());
    const isHeaderChange = (currentBlock.lines.length === 1 && currentBlock.bold !== line.bold);
    const isBlockUppercase = currentBlock.lines.length === 1 &&
                             currentBlock.text.trim().length > 6 &&
                             /^[A-Z0-9\s,\.\-–—\(\)\/]+$/.test(currentBlock.text.trim());
    const isLineUppercase = line.text.trim().length > 6 &&
                            /^[A-Z0-9\s,\.\-–—\(\)\/]+$/.test(line.text.trim());

    // Do NOT merge blank lines into other blocks so each blank line remains distinct
    const shouldMerge = !line.isBlankLine &&
                        !currentBlock.isBlankLine &&
                        fontMatches &&
                        isLeftAligned &&
                        isProseWidth &&
                        colorMatches &&
                        !isContactOrSpecial &&
                        !isHorizontallyDisjoint &&
                        (verticalGap >= -2 * viewport.scale && verticalGap <= maxGap) &&
                        !isNumberedList &&
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

  // 4. Restore any existing edits for this page (if restored from explicit saved session)
  for (const block of blocks) {
    if (editedBlocksState[block.id]) {
      const state = editedBlocksState[block.id];
      block.text = state.newText;
      block.isEdited = (block.text !== block.originalText) || Boolean(state.isMoved);
      block.deltaY = state.deltaY || 0;
      block.currentHeight = state.currentHeight || block.originalHeight;
      if (state.customFontSize != null) {
        block.customFontSize = state.customFontSize;
        block.viewportFontSize = state.customFontSize * (props.zoom || 1);
      }
      if (state.customColor != null) {
        block.customColor = state.customColor;
        block.color = state.customColor;
      }
      if (state.customWidth != null) {
        block.customWidth = state.customWidth;
        block.width = state.customWidth;
      }
      if (state.bold != null) block.bold = state.bold;
      if (state.italic != null) block.italic = state.italic;
      if (state.isMoved) {
        block.isMoved = true;
        block.customX = state.customX;
        block.customY = state.customY;
        block.x = state.customX;
        block.y = state.customY;
      }
    } else if (!autoRedactedBlockIds.has(block.id) && !isRestored && documentStore.sessionMode !== 'RESUME') {
      if (!block.isBlankLine && hasPII(block.text)) {
        const cleanedText = redactPIIFromText(block.text);
        if (cleanedText !== block.text) {
          autoRedactedBlockIds.add(block.id);
          block.text = cleanedText;
          block.isEdited = true;
          editedBlocksState[block.id] = {
            newText: cleanedText,
            deltaY: 0,
            currentHeight: block.currentHeight || block.originalHeight
          };
          documentStore.setPatch(block.pageNumber, block.id, {
            newText: cleanedText,
            originalText: block.originalText,
            isEdited: true
          });
        }
      }
    }
  }

  // 5. Scan for tiny sibling icon boxes immediately to the left of redacted PII blocks
  if (!isRestored && documentStore.sessionMode !== 'RESUME') {
    for (const piiBox of blocks) {
      if (!piiBox.isEdited || piiBox.isBlankLine) continue;
      const isPiiRedacted = hasPII(piiBox.originalText) || (piiBox.text === '' && autoRedactedBlockIds.has(piiBox.id));
      if (!isPiiRedacted) continue;

      for (const sibling of blocks) {
        if (sibling.id === piiBox.id || sibling.isBlankLine) continue;
        const sibW = sibling.originalWidth || sibling.width;
        const isTiny = sibW < (25 * viewport.scale);
        if (!isTiny) continue;

        const sameBaseline = Math.abs(sibling.y - piiBox.y) < (6 * viewport.scale);
        if (!sameBaseline) continue;

        const sibRight = sibling.x + sibW;
        const gap = piiBox.x - sibRight;
        const isImmediatelyLeft = sibling.x < piiBox.x && gap >= -2 && gap < (18 * viewport.scale);
        if (!isImmediatelyLeft) continue;

        const textTrim = (sibling.text || '').trim();
        const isIconGlyph = textTrim.length <= 2 || !/[a-zA-Z0-9]/.test(textTrim) || /^[\u2600-\u27BF\uE000-\uF8FF\uD83C-\uDBFF\uDC00-\uDFFF✉☎✆📞📧📱📍\s]+$/.test(textTrim);
        if (isIconGlyph) {
          sibling.text = '';
          sibling.isEdited = true;
          autoRedactedBlockIds.add(sibling.id);
          editedBlocksState[sibling.id] = {
            newText: '',
            deltaY: 0,
            currentHeight: sibling.originalHeight
          };
          documentStore.setPatch(sibling.pageNumber, sibling.id, {
            newText: '',
            originalText: sibling.originalText,
            isEdited: true
          });
        }
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
  // Guard: Dynamic flow pages must never be scanned for blank lines
  if (currentPdfDoc && pageNumber > currentPdfDoc.numPages) return;
  const pModel = documentStore.documentModel?.pages?.[pageNumber - 1];
  if (pModel?.isBlankPage) return;

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

  const canvasScale = (props.zoom || 1.0) * dpr;
  let appendedCount = 0;
  for (const line of mergedLines) {
    const lineX = line.x1 / canvasScale;
    const lineWidth = (line.x2 - line.x1) / canvasScale;
    const lineY = line.y / canvasScale;

    // Check if an existing block already covers this line
    const isCovered = existingBlocks.some(b => {
      const bBottom = b.y + b.height;
      const bTop = b.y;
      const yOverlap = Math.abs(bBottom - lineY) <= 10 ||
                       (lineY >= bTop - 4 && lineY <= bBottom + 4);
      const xOverlap = Math.max(b.x, lineX) < (Math.min(b.x + b.width, lineX + lineWidth) - 15);
      return yOverlap && xOverlap;
    });

    if (isCovered) continue;

    // Check if text sits directly above this line (underline under existing text)
    const hasTextImmediatelyAbove = existingBlocks.some(b => {
      const bBottom = b.y + b.height;
      const isDirectlyAbove = (lineY - bBottom) >= 0 && (lineY - bBottom) <= 6;
      const xOverlap = Math.max(b.x, lineX) < Math.min(b.x + b.width, lineX + lineWidth);
      return isDirectlyAbove && xOverlap && !b.isBlankLine;
    });

    if (hasTextImmediatelyAbove) continue;

    const fontSize = 11.0;
    const blockHeight = Math.max(fontSize * 1.25, 14);
    const blockY = lineY - blockHeight + 1.5;
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
      pdfX: lineX,
      pdfY: blockY,
      pdfWidth: lineWidth,
      pdfHeight: blockHeight,
      originalPdfX: lineX,
      originalPdfY: blockY,
      originalPdfWidth: lineWidth,
      originalPdfHeight: blockHeight,
      fontSize: fontSize,
      viewportFontSize: fontSize,
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

function createLineFromItems(items, viewport, pristineCanvas = null) {
  const textItems = items.filter(it => it.str && it.str.trim().length > 0);
  const effectiveItems = textItems.length > 0 ? textItems : items;
  const minX = Math.min(...effectiveItems.map(it => it.x));
  const maxX = Math.max(...effectiveItems.map(it => it.x + it.width));
  const first = effectiveItems[0] || items[0];
  const fontSize = first.fontSize;
  const viewportFontSize = first.viewportFontSize;
  const baselineY = first.baselineY != null ? first.baselineY : (first.y + first.viewportFontSize * 0.85);
  // Fix 3: Accurate top-left y from exact block fontSize (ascent 0.85)
  const y = baselineY - (viewportFontSize * 0.85);
  const height = Math.max(viewportFontSize * 1.05, Math.max(...effectiveItems.map(it => it.height)));
  const text = effectiveItems.map(it => it.str).join(' ');

  const fontName = first.fontName || 'Helvetica';
  const fontFamily = mapPdfFontToCssFamily(fontName);
  const bold = isFontBold(fontName);
  const italic = isFontItalic(fontName);

  // Sample exact text ink color from canvas before whiteout
  let color = '#000000';
  if (pristineCanvas) {
    const dpr = pristineCanvas.width / viewport.width;
    const sampled = sampleTextColorFromCanvas(pristineCanvas, minX, y, maxX - minX, height, dpr);
    if (sampled) {
      color = sampled;
    }
  }

  return {
    items,
    text,
    x: minX,
    y: y,
    baselineY,
    width: maxX - minX,
    height: height,
    pdfX: minX / viewport.scale,
    pdfY: y / viewport.scale,
    pdfWidth: (maxX - minX) / viewport.scale,
    pdfHeight: height / viewport.scale,
    fontSize: fontSize,
    viewportFontSize: viewportFontSize,
    fontName: fontName,
    fontFamily: fontFamily,
    bold: bold,
    italic: italic,
    color: color
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
    originalFontSize: line.fontSize,
    customFontSize: null,
    fontName: line.fontName,
    fontFamily: line.fontFamily || mapPdfFontToCssFamily(line.fontName),
    originalFontFamily: line.fontFamily || mapPdfFontToCssFamily(line.fontName),
    bold: Boolean(line.bold),
    originalBold: Boolean(line.bold),
    italic: Boolean(line.italic),
    originalItalic: Boolean(line.italic),
    color: line.color || '#000000',
    originalColor: line.color || '#000000',
    customColor: null,
    customWidth: null,
    isEdited: false,
    deltaY: 0,
    currentHeight: line.height
  };
}

/**
 * Completely purges all previous editor state, in-flight render tasks,
 * canvas snapshots, active edits, and overlays before rendering a new PDF canvas.
 */
function purgeEditorState() {
  // 1. Reset active editing selection
  editingBlockId.value = null;
  activeBlockText.value = '';
  hoveredBlockId.value = null;
  activeDeltaY.value = 0;
  reflowVersion.value = 0;

  // 2. Clear all debounce and blur timers
  if (sessionSaveDebounceTimer) {
    clearTimeout(sessionSaveDebounceTimer);
    sessionSaveDebounceTimer = null;
  }
  if (renderDebounceTimer) {
    clearTimeout(renderDebounceTimer);
    renderDebounceTimer = null;
  }
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }

  // 3. Cancel any in-flight PDF.js page rendering tasks
  for (const p in renderTasks) {
    try {
      renderTasks[p].cancel();
    } catch {}
    delete renderTasks[p];
  }

  // 4. Clear pristine canvas caches
  for (const p in pristineCanvasMap) {
    delete pristineCanvasMap[p];
  }

  // 5. Clear tracked edit states and auto-redacted blocks
  for (const k of Object.keys(editedBlocksState)) {
    delete editedBlocksState[k];
  }
  autoRedactedBlockIds.clear();

  // 6. Reset page blocks, viewports, rotations, and rendering tracking
  pageBlocksMap.value = {};
  pageViewports.value = {};
  userRotations.value = {};
  isPageCanvasRendered.value = {};
  visiblePages.value = {};
  canvasRefs.value = {};
  numPages.value = 0;

  // 7. Cleanup active PDF.js document handle
  if (currentPdfDoc) {
    try {
      currentPdfDoc.destroy();
    } catch {}
    currentPdfDoc = null;
  }
}

// Watchers
watch(
  () => documentStore.originalPdfData,
  async (newData) => {
    if (newData && newData.byteLength > 0) {
      if (documentStore.isCommittedSave) {
        return;
      }
      // Completely purge previous canvas state, edit tracking, and overlays before rendering new canvas
      purgeEditorState();

      const isRestored = Boolean(documentStore.isRestoredSession && documentStore.restoredSession);
      if (isRestored) {
        const restored = documentStore.restoredSession;
        if (restored.viewSettings?.userRotations) {
          userRotations.value = { ...restored.viewSettings.userRotations };
        }
        if (restored.editedBlocksState) {
          Object.assign(editedBlocksState, restored.editedBlocksState);
        }
        if (restored.autoRedactedBlockIds && Array.isArray(restored.autoRedactedBlockIds)) {
          restored.autoRedactedBlockIds.forEach(id => autoRedactedBlockIds.add(id));
        }
        if (restored.pageBlocksMap && Object.keys(restored.pageBlocksMap).length > 0) {
          pageBlocksMap.value = { ...restored.pageBlocksMap };
        }
      }

      await renderAllPages(isRestored);
    }
  },
  { immediate: true }
);

watch(
  () => documentStore.edits,
  (newEdits) => {
    if ((!newEdits || newEdits.length === 0) && Object.keys(editedBlocksState).length > 0) {
      revertAll();
    }
  },
  { deep: false }
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
      scheduleSessionSave();
    }
  }
);

onMounted(async () => {
  if (typeof window !== 'undefined') {
    window.__buildRedactionPayload = buildRedactionPayload;
    window.__revertAll = revertAll;
    window.__revertBlock = revertBlock;
    window.__deleteBlock = deleteBlock;
    window.__checkBlockCollision = checkBlockCollision;
    window.__autoShrinkToFit = autoShrinkToFit;
    window.__pageBlocksMap = pageBlocksMap;
    window.__documentStore = documentStore;
    window.__saveCurrentSession = saveCurrentSession;
    window.__sessionStorageService = sessionStorageService;
    window.__purgeEditorState = purgeEditorState;
    window.__commitActiveBlock = commitActiveBlock;
    window.__buildRedactionPayload = buildRedactionPayload;
    window.__documentStore = documentStore;
    window.__revertAll = revertAll;
    window.addEventListener('mousedown', onBackgroundMouseDown);
    window.addEventListener('keydown', handleEditorKeydown);
    window.__executeUndo = executeUndo;
    window.__executeRedo = executeRedo;
  }
});

onBeforeUnmount(() => {
  if (typeof window !== 'undefined') {
    window.removeEventListener('mousedown', onBackgroundMouseDown);
    window.removeEventListener('keydown', handleEditorKeydown);
    delete window.__executeUndo;
    delete window.__executeRedo;
    delete window.__commitActiveBlock;
    delete window.__buildRedactionPayload;
    delete window.__documentStore;
    delete window.__revertAll;
    delete window.__revertBlock;
    delete window.__deleteBlock;
    delete window.__checkBlockCollision;
    delete window.__autoShrinkToFit;
    delete window.__pageBlocksMap;
    delete window.__saveCurrentSession;
    delete window.__sessionStorageService;
    delete window.__purgeEditorState;
  }
  if (pageObserver) {
    pageObserver.disconnect();
    pageObserver = null;
  }
  if (sessionSaveDebounceTimer) {
    clearTimeout(sessionSaveDebounceTimer);
    sessionSaveDebounceTimer = null;
  }
  if (renderDebounceTimer) {
    clearTimeout(renderDebounceTimer);
    renderDebounceTimer = null;
  }
  if (blurTimer) {
    clearTimeout(blurTimer);
    blurTimer = null;
  }
  for (const p in renderTasks) {
    try {
      renderTasks[p].cancel();
    } catch {}
  }
  purgeEditorState();
});

/**
 * Commits the current edited/redacted state as the new baseline after an in-place save.
 * Bakes the changes into the canvas by re-loading the PDF document with newBuffer,
 * resets the edited state on blocks, clears pending edits, and preserves block layout.
 *
 * @param {ArrayBuffer} newBuffer - The newly baked PDF bytes from pdf-lib
 */
async function commitSavedBaseline(newBuffer) {
  // 1. Commit any currently active block
  commitActiveBlock();

  // 2. Mark every block as unedited baseline matching current text
  for (const pageNumber in pageBlocksMap.value) {
    const blocks = pageBlocksMap.value[pageNumber];
    if (Array.isArray(blocks)) {
      for (const block of blocks) {
        if (isBlockEdited(block)) {
          block.originalText = block.text;
          block.originalFontSize = block.customFontSize || block.fontSize;
          block.originalBold = block.bold;
          block.originalItalic = block.italic;
          block.originalColor = block.customColor || block.color;
          block.originalWidth = block.customWidth || block.width;
          block.originalHeight = block.currentHeight || block.height;
          if (block.customX != null) block.originalX = block.customX;
          if (block.customY != null) block.originalY = block.customY;
          block.isEdited = false;
          block.isMoved = false;
        }
      }
    }
  }

  // 3. Clear tracked edits map and auto-redacted IDs
  for (const k of Object.keys(editedBlocksState)) {
    delete editedBlocksState[k];
  }
  autoRedactedBlockIds.clear();
  documentStore.edits = [];

  // 4. Update the current PDF.js document handle with newBuffer
  try {
    const rawPdf = toRaw(newBuffer);
    const bufferCopy = rawPdf.slice(0);
    const loadingTask = pdfjsLib.getDocument({
      data: new Uint8Array(bufferCopy),
      cMapUrl: 'https://cdn.jsdelivr.net/npm/pdfjs-dist@4.10.38/cmaps/',
      cMapPacked: true
    });
    currentPdfDoc = await loadingTask.promise;
  } catch (err) {
    console.warn('[PdfCanvasEditor] Failed to reload currentPdfDoc on baseline commit:', err);
  }

  // 5. Re-render the visible canvas pages with the newly baked PDF
  for (const p in isPageCanvasRendered.value) {
    isPageCanvasRendered.value[p] = false;
  }
  scheduleVisiblePagesRender();

  // 6. Persist clean session to IndexedDB
  saveCurrentSession();
}

defineExpose({
  buildRedactionPayload,
  getRedactionEdits: buildRedactionPayload,
  revertAll,
  commitActiveBlock,
  commitSavedBaseline,
  purgeEditorState,
  executeUndo,
  executeRedo,
  captureCurrentSnapshot,
  applyHistorySnapshot,
  getUserRotations: () => ({ ...userRotations.value })
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
  min-width: min-content;
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
  overflow: hidden !important;
}

.pdf-page-canvas {
  display: block;
  overflow: hidden !important;
}

/* Dedicated Whiteout Mask Layer */
.pdf-page-whiteout-layer {
  position: absolute;
  top: 0;
  left: 0;
  pointer-events: none;
  z-index: 5;
  overflow: hidden !important;
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
  overflow: hidden !important;
}

/* WPS Office-Style Live Paragraph Block */
.wps-paragraph-block {
  position: absolute;
  box-sizing: border-box;
  cursor: text;
  border-radius: 2px;
  overflow: visible;
  pointer-events: auto !important;
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
  background-color: #ffffff !important;
  opacity: 1 !important;
  box-shadow: 0 0 0 3px rgba(37, 99, 235, 0.18);
  z-index: 50 !important;
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
.wps-paragraph-block.transparent-bg {
  background-color: transparent !important;
}

.wps-paragraph-block.is-blank-line:not(.is-editing):not(.is-edited),
.wps-paragraph-block.transparent-bg:not(.is-editing):not(.is-edited) {
  z-index: 10 !important;
}

.wps-paragraph-block.is-editing.is-blank-line,
.wps-paragraph-block.is-editing.transparent-bg {
  z-index: 50 !important;
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
  background-color: #ffffff !important;
  opacity: 1 !important;
  z-index: 50 !important;
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

/* WPS-Style Floating Mini-Toolbar on Active / Selected Block */
.block-floating-toolbar {
  position: absolute;
  top: -38px;
  left: 0;
  height: 30px;
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 0 6px;
  background: #1e293b;
  border: 1px solid #334155;
  border-radius: 6px;
  box-shadow: 0 4px 12px rgba(0, 0, 0, 0.35);
  z-index: 60;
  user-select: none;
  white-space: nowrap;
}

.btn-toolbar-tool {
  background: transparent;
  border: none;
  color: #e2e8f0;
  font-size: 11px;
  font-weight: 600;
  height: 22px;
  min-width: 22px;
  padding: 0 4px;
  border-radius: 3px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  transition: background-color 0.15s ease, color 0.15s ease;
}

.btn-toolbar-tool:hover {
  background-color: #334155;
  color: #ffffff;
}

.btn-toolbar-tool.is-active {
  background-color: #3b82f6;
  color: #ffffff;
}

.toolbar-font-size-input {
  width: 42px;
  height: 22px;
  background: #0f172a;
  border: 1px solid #475569;
  border-radius: 3px;
  color: #f8fafc;
  font-size: 11px;
  font-weight: 600;
  text-align: center;
  outline: none;
  padding: 0 2px;
}

.toolbar-divider {
  width: 1px;
  height: 16px;
  background-color: #475569;
  margin: 0 2px;
}

.toolbar-color-label {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  position: relative;
  width: 22px;
  height: 22px;
  border-radius: 3px;
}

.toolbar-color-label:hover {
  background-color: #334155;
}

.toolbar-color-input {
  position: absolute;
  opacity: 0;
  width: 100%;
  height: 100%;
  cursor: pointer;
}

.color-preview-pip {
  width: 14px;
  height: 14px;
  border-radius: 50%;
  border: 1.5px solid #ffffff;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.4);
}

.btn-toolbar-revert {
  color: #f87171;
  font-size: 13px;
}

.btn-toolbar-revert:hover {
  background-color: rgba(239, 68, 68, 0.2);
  color: #ef4444;
}

/* Corner Resize Handle on Active / Selected Block */
.block-resize-handle {
  position: absolute;
  right: -5px;
  bottom: -5px;
  width: 10px;
  height: 10px;
  background-color: #2563eb;
  border: 1.5px solid #ffffff;
  border-radius: 2px;
  cursor: nwse-resize;
  box-shadow: 0 1px 4px rgba(0, 0, 0, 0.35);
  z-index: 45;
  touch-action: none;
}

.block-resize-handle:hover {
  background-color: #1d4ed8;
  transform: scale(1.2);
}

/* Collision Warning Border and Badges (Objective 3) */
.wps-paragraph-block.has-collision {
  border: 1.5px dashed #f59e0b !important;
  box-shadow: 0 0 6px rgba(245, 158, 11, 0.45) !important;
}

.toolbar-collision-badge {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  background: rgba(245, 158, 11, 0.15);
  border: 1px solid #f59e0b;
  border-radius: 3px;
  padding: 1px 6px;
  font-size: 10px;
  font-weight: 600;
  color: #fbbf24;
  white-space: nowrap;
}

.toolbar-collision-badge .text-amber {
  color: #f59e0b;
  width: 13px;
  height: 13px;
}

.btn-auto-shrink {
  background-color: #1e293b;
  color: #38bdf8;
  font-size: 11px;
  font-weight: 600;
  padding: 0 6px;
}

.btn-auto-shrink:hover {
  background-color: #0284c7;
  color: #ffffff;
}

.btn-floating-revert {
  color: #f87171;
  font-size: 11px;
  font-weight: 600;
}

.btn-floating-revert:hover:not(:disabled) {
  background-color: rgba(239, 68, 68, 0.2);
  color: #ef4444;
}

.btn-floating-revert:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-floating-delete {
  color: #94a3b8;
  font-size: 11px;
}

.btn-floating-delete:hover {
  background-color: rgba(239, 68, 68, 0.2);
  color: #ef4444;
}
</style>
