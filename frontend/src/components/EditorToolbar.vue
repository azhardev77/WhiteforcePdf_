<template>
  <div class="editor-toolbar">
    <!-- Left Group: Zoom Controls -->
    <div class="toolbar-group">
      <span class="group-label">Zoom:</span>
      <button
        type="button"
        class="toolbar-btn"
        title="Zoom Out (-15%)"
        :disabled="documentStore.zoom <= 0.5"
        @click="zoomOut"
      >
        <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" />
          <line x1="8" y1="11" x2="14" y2="11" />
        </svg>
      </button>

      <span class="zoom-display" title="Current Zoom">{{ Math.round(documentStore.zoom * 100) }}%</span>

      <button
        type="button"
        class="toolbar-btn"
        title="Zoom In (+15%)"
        :disabled="documentStore.zoom >= 2.5"
        @click="zoomIn"
      >
        <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="11" cy="11" r="8" />
          <line x1="21" y1="21" x2="16.65" y2="16.65" />
          <line x1="11" y1="8" x2="11" y2="14" />
          <line x1="8" y1="11" x2="14" y2="11" />
        </svg>
      </button>

      <button
        type="button"
        class="toolbar-btn text-btn"
        title="Reset Zoom to 100%"
        @click="resetZoom"
      >
        100%
      </button>

      <button
        type="button"
        class="toolbar-btn text-btn"
        title="Fit Page Width (125%)"
        @click="fitZoom"
      >
        Fit
      </button>
    </div>

    <!-- Undo / Redo Stack Controls -->
    <div class="toolbar-divider" />
    <div class="toolbar-group history-group">
      <button
        type="button"
        class="toolbar-btn"
        title="Undo (Ctrl+Z)"
        data-testid="btn-toolbar-undo"
        :disabled="!documentStore.canUndo"
        @click="$emit('undo')"
      >
        <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="9 14 4 9 9 4" />
          <path d="M20 20v-7a4 4 0 0 0-4-4H4" />
        </svg>
      </button>

      <button
        type="button"
        class="toolbar-btn"
        title="Redo (Ctrl+Y / Ctrl+Shift+Z)"
        data-testid="btn-toolbar-redo"
        :disabled="!documentStore.canRedo"
        @click="$emit('redo')"
      >
        <svg class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <polyline points="15 14 20 9 15 4" />
          <path d="M4 20v-7a4 4 0 0 1 4-4h12" />
        </svg>
      </button>
    </div>

    <div class="toolbar-divider" />
    <div class="toolbar-group status-group">
      <div class="mode-badge">
        <svg class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <rect x="3" y="3" width="18" height="18" rx="2" ry="2" />
          <line x1="9" y1="9" x2="15" y2="15" />
          <line x1="15" y1="9" x2="9" y2="15" />
        </svg>
        <span>1:1 Visual Redaction Mode</span>
      </div>

      <div class="edits-status" :class="{ 'has-edits': (documentStore.paragraphEditsCount || documentStore.editsCount) > 0 }">
        <span class="status-dot" />
        <span v-if="(documentStore.paragraphEditsCount || documentStore.editsCount) === 0">Click any text on the page to edit</span>
        <span v-else>{{ documentStore.paragraphEditsCount || documentStore.editsCount }} {{ (documentStore.paragraphEditsCount || documentStore.editsCount) === 1 ? 'redaction edit' : 'redaction edits' }} active</span>
      </div>

      <div class="revert-all-container">
        <button
          v-if="(documentStore.paragraphEditsCount || documentStore.editsCount) > 0"
          type="button"
          class="btn-clear-edits btn-revert-all"
          data-testid="btn-revert-all"
          title="Revert all in-place edits back to original PDF"
          @click="showRevertConfirm = !showRevertConfirm"
        >
          Revert All
        </button>

        <!-- Non-blocking inline confirmation popover replacing window.confirm -->
        <div v-if="showRevertConfirm" class="inline-confirm-popover" data-testid="revert-confirm-popover">
          <div class="popover-text">Revert all edits to original PDF?</div>
          <div class="popover-actions">
            <button
              type="button"
              class="btn-popover-cancel"
              data-testid="btn-popover-cancel"
              @click="showRevertConfirm = false"
            >
              Cancel
            </button>
            <button
              type="button"
              class="btn-popover-confirm"
              data-testid="btn-popover-confirm"
              @click="confirmRevertAll"
            >
              Revert
            </button>
          </div>
        </div>
      </div>

      <!-- Active Edit Transparency Toggle -->
      <div v-if="activeEdit" class="toolbar-divider" />
      <div v-if="activeEdit" class="toolbar-group active-edit-group">
        <span class="group-label">Selected Edit:</span>
        <button
          type="button"
          class="toolbar-btn text-btn toggle-transparent-btn"
          :class="{ 'is-active': activeEdit.transparentBackground }"
          title="Toggle between overprinting on dots/lines vs whiteout redaction"
          @click="toggleActiveEditTransparency"
        >
          <span>{{ activeEdit.transparentBackground ? '☑ Overprint (Dots)' : '☐ Whiteout (Erase)' }}</span>
        </button>
      </div>
    </div>

    <!-- Right Group: Quick Export Action -->
    <div class="toolbar-group toolbar-group-right">
      <button
        type="button"
        class="btn-toolbar-save"
        :disabled="documentStore.isSaving"
        title="Save in-place into same PDF file (Ctrl+S)"
        @click="$emit('save')"
      >
        <span v-if="documentStore.isSaving" class="spinner-sm" />
        <svg v-else class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
          <polyline points="17 21 17 13 7 13 7 21" />
          <polyline points="7 3 7 8 15 8" />
        </svg>
        <span>Save</span>
      </button>

      <button
        type="button"
        class="btn-toolbar-export"
        :disabled="isExporting"
        @click="$emit('export')"
      >
        <span v-if="isExporting" class="spinner-sm" />
        <svg v-else class="icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
          <polyline points="7 10 12 15 17 10" />
          <line x1="12" y1="15" x2="12" y2="3" />
        </svg>
        <span>Save & Export</span>
      </button>
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue';
import { useDocumentStore } from '@/stores/document';

const props = defineProps({
  isExporting: {
    type: Boolean,
    default: false
  }
});

const emit = defineEmits(['save', 'export', 'undo', 'redo', 'revert-all']);

const documentStore = useDocumentStore();
const showRevertConfirm = ref(false);

const activeEdit = computed(() => {
  if (!documentStore.activeBlockId) return null;
  return documentStore.getEdit(documentStore.activeBlockId);
});

function toggleActiveEditTransparency() {
  if (activeEdit.value) {
    activeEdit.value.transparentBackground = !activeEdit.value.transparentBackground;
  }
}

function zoomIn() {
  documentStore.setZoom(documentStore.zoom + 0.15);
}

function zoomOut() {
  documentStore.setZoom(documentStore.zoom - 0.15);
}

function resetZoom() {
  documentStore.setZoom(1.0);
}

function fitZoom() {
  documentStore.setZoom(1.25);
}

function confirmRevertAll() {
  showRevertConfirm.value = false;
  emit('revert-all');
  documentStore.clearAllPatches();
  documentStore.edits = [];
  documentStore.activeBlockId = null;
}
</script>

<style scoped>
.editor-toolbar {
  height: var(--toolbar-height, 46px);
  background-color: #1e293b;
  border-bottom: 1px solid #334155;
  display: flex;
  align-items: center;
  padding: 0 16px;
  gap: 8px;
  user-select: none;
  z-index: 25;
  flex-shrink: 0;
}

.toolbar-group {
  display: flex;
  align-items: center;
  gap: 6px;
}

.toolbar-group-right {
  margin-left: auto;
}

.group-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  color: #64748b;
  letter-spacing: 0.04em;
  margin-right: 2px;
}

.toolbar-divider {
  width: 1px;
  height: 22px;
  background-color: #334155;
  margin: 0 8px;
}

.toolbar-btn {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 28px;
  padding: 0 6px;
  border-radius: var(--radius-sm, 4px);
  color: #94a3b8;
  background: transparent;
  border: 1px solid transparent;
  cursor: pointer;
  transition: all 0.15s ease;
  font-size: 12px;
  font-weight: 500;
}

.toolbar-btn:hover:not(:disabled) {
  background-color: #334155;
  color: #f1f5f9;
}

.toolbar-btn:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}

.text-btn {
  padding: 0 8px;
  font-size: 11px;
  font-weight: 600;
  background-color: rgba(51, 65, 85, 0.5);
  border: 1px solid #334155;
}

.zoom-display {
  font-size: 12px;
  font-weight: 600;
  color: #e2e8f0;
  min-width: 44px;
  text-align: center;
}

.status-group {
  display: flex;
  align-items: center;
  gap: 12px;
}

.mode-badge {
  display: flex;
  align-items: center;
  gap: 6px;
  background: #0f172a;
  border: 1px solid #334155;
  padding: 3px 10px;
  border-radius: 9999px;
  font-size: 11px;
  font-weight: 600;
  color: #818cf8;
}

.edits-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: #94a3b8;
}

.edits-status.has-edits {
  color: #34d399;
  font-weight: 600;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background-color: #64748b;
}

.edits-status.has-edits .status-dot {
  background-color: #10b981;
  box-shadow: 0 0 8px rgba(16, 185, 129, 0.6);
}

.btn-clear-edits {
  background: transparent;
  border: 1px solid #ef4444;
  color: #f87171;
  font-size: 11px;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-clear-edits:hover {
  background: rgba(239, 68, 68, 0.15);
}

.revert-all-container {
  position: relative;
  display: inline-flex;
  align-items: center;
}

.inline-confirm-popover {
  position: absolute;
  top: calc(100% + 8px);
  left: 0;
  background: #1e293b;
  border: 1px solid #475569;
  border-radius: 6px;
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.5), 0 8px 10px -6px rgba(0, 0, 0, 0.4);
  padding: 10px 12px;
  min-width: 210px;
  z-index: 100;
  display: flex;
  flex-direction: column;
  gap: 8px;
  animation: popoverFadeIn 0.15s ease-out;
}

@keyframes popoverFadeIn {
  from {
    opacity: 0;
    transform: translateY(-4px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.popover-text {
  font-size: 11px;
  font-weight: 500;
  color: #e2e8f0;
  white-space: normal;
}

.popover-actions {
  display: flex;
  justify-content: flex-end;
  gap: 6px;
}

.btn-popover-cancel {
  background: transparent;
  border: 1px solid #475569;
  color: #94a3b8;
  font-size: 11px;
  font-weight: 500;
  padding: 3px 8px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.1s ease;
}

.btn-popover-cancel:hover {
  background: #334155;
  color: #f1f5f9;
}

.btn-popover-confirm {
  background: #dc2626;
  border: 1px solid #ef4444;
  color: #ffffff;
  font-size: 11px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.1s ease;
}

.btn-popover-confirm:hover {
  background: #b91c1c;
}

.btn-toolbar-save {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 12px;
  height: 30px;
  border-radius: var(--radius-sm, 4px);
  background: #047857;
  color: #ffffff;
  font-size: 12px;
  font-weight: 600;
  border: 1px solid #059669;
  cursor: pointer;
  box-shadow: 0 2px 4px rgba(5, 150, 105, 0.25);
  transition: all 0.15s ease;
}

.btn-toolbar-save:hover:not(:disabled) {
  background: #059669;
  box-shadow: 0 4px 8px rgba(5, 150, 105, 0.4);
}

.btn-toolbar-save:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.btn-toolbar-export {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  height: 30px;
  border-radius: var(--radius-sm, 4px);
  background: #4f46e5;
  color: #ffffff;
  font-size: 12px;
  font-weight: 600;
  border: none;
  cursor: pointer;
  box-shadow: 0 2px 4px rgba(79, 70, 229, 0.3);
  transition: all 0.15s ease;
}

.btn-toolbar-export:hover:not(:disabled) {
  background: #4338ca;
  box-shadow: 0 4px 8px rgba(79, 70, 229, 0.4);
}

.btn-toolbar-export:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.icon {
  width: 15px;
  height: 15px;
}

.icon-sm {
  width: 13px;
  height: 13px;
}

.spinner-sm {
  width: 14px;
  height: 14px;
  border: 2px solid rgba(255, 255, 255, 0.3);
  border-top-color: #ffffff;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
