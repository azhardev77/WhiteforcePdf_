<template>
  <div class="editor-view-container">
    <!-- Top Header Bar -->
    <header class="app-header">
      <div class="header-left">
        <div
          class="brand-badge"
          :class="{ 'is-clickable': documentStore.hasDocument }"
          :title="documentStore.hasDocument ? 'Click to close document and return to upload screen' : 'PDF Visual Studio'"
          @click="handleLogoClick"
        >
          <svg class="brand-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
            <polyline points="14 2 14 8 20 8" />
            <line x1="16" y1="13" x2="8" y2="13" />
            <line x1="16" y1="17" x2="8" y2="17" />
            <polyline points="10 9 9 9 8 9" />
          </svg>
          <span class="brand-title">PDF Visual Studio</span>
          <span class="version-tag">Pixel-Perfect</span>
        </div>

        <div v-if="documentStore.hasDocument" class="document-meta-info">
          <input
            v-model="documentStore.fileName"
            class="document-title-input"
            title="Click to rename document"
            placeholder="Document Name"
            @change="handleFileNameChange"
          />
          <span v-if="documentStore.sessionMode" class="badge-session-mode" data-testid="session-mode-badge">
            {{ documentStore.sessionMode }} &bull; {{ documentStore.sessionSource || 'UPLOAD' }}
          </span>
          <span class="badge-status">
            {{ documentStore.pageCount }} {{ documentStore.pageCount === 1 ? 'Page' : 'Pages' }}
          </span>
          <span v-if="(documentStore.paragraphEditsCount || documentStore.editsCount) > 0" class="badge-edits">
            {{ documentStore.paragraphEditsCount || documentStore.editsCount }} {{ (documentStore.paragraphEditsCount || documentStore.editsCount) === 1 ? 'paragraph edit' : 'paragraph edits' }}
          </span>
        </div>
      </div>

      <div class="header-right">
        <!-- Hidden File Input for Header "Open File" Button -->
        <input
          ref="headerFileInputRef"
          type="file"
          accept="application/pdf"
          class="hidden-file-input"
          style="display: none"
          @change="handleFileInput"
        />

        <button
          v-if="documentStore.hasDocument"
          type="button"
          class="btn-secondary"
          title="Upload another PDF or create blank document"
          @click="triggerOpenNewFile"
        >
          <svg class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
            <polyline points="17 8 12 3 7 8" />
            <line x1="12" y1="3" x2="12" y2="15" />
          </svg>
          <span>Open File</span>
        </button>

        <button
          v-if="documentStore.hasDocument"
          type="button"
          class="btn-secondary btn-close-doc"
          title="Close current document and clear saved session"
          @click="handleCloseDocument"
        >
          <svg class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <line x1="18" y1="6" x2="6" y2="18" />
            <line x1="6" y1="6" x2="18" y2="18" />
          </svg>
          <span>Close</span>
        </button>

        <!-- Dedicated Save Button (Ctrl+S) -->
        <button
          v-if="documentStore.hasDocument"
          type="button"
          class="btn-save-doc"
          :class="{ 'is-saved': isRecentlySaved, 'is-saving': documentStore.isSaving }"
          :disabled="documentStore.isSaving"
          :title="`Save and overwrite directly into ${documentStore.fileName || 'this PDF file'} (Ctrl+S)`"
          @click="handleSave"
        >
          <span v-if="documentStore.isSaving" class="spinner-sm" />
          <svg v-else-if="isRecentlySaved" class="icon-sm text-green" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
            <polyline points="20 6 9 17 4 12" />
          </svg>
          <svg v-else class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M19 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11l5 5v11a2 2 0 0 1-2 2z" />
            <polyline points="17 21 17 13 7 13 7 21" />
            <polyline points="7 3 7 8 15 8" />
          </svg>
          <span v-if="documentStore.isSaving">Saving...</span>
          <span v-else-if="isRecentlySaved">Saved</span>
          <span v-else>Save</span>
          <kbd v-if="!documentStore.isSaving && !isRecentlySaved" class="btn-kbd">Ctrl+S</kbd>
        </button>

        <button
          v-if="documentStore.hasDocument"
          type="button"
          class="btn-primary"
          :disabled="documentStore.isExporting || documentStore.isSaving"
          @click="handleExport"
        >
          <span v-if="documentStore.isExporting" class="spinner" />
          <svg v-else class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
            <polyline points="7 10 12 15 17 10" />
            <line x1="12" y1="15" x2="12" y2="3" />
          </svg>
          <span>Export PDF (In-Place)</span>
        </button>
      </div>
    </header>

    <!-- Error Banner -->
    <div v-if="documentStore.errorMessage" class="error-banner">
      <div class="error-content">
        <svg class="error-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
        <span>{{ documentStore.errorMessage }}</span>
      </div>
      <button type="button" class="btn-dismiss-error" @click="documentStore.clearError">
        &times;
      </button>
    </div>

    <!-- Formatting Toolbar (Visible when document loaded) -->
    <EditorToolbar
      v-if="documentStore.hasDocument"
      :is-exporting="documentStore.isExporting"
      @save="handleSave"
      @export="handleExport"
      @undo="handleUndo"
      @redo="handleRedo"
      @revert-all="handleRevertAll"
    />

    <!-- Main Workspace Content -->
    <main class="editor-workspace">
      <!-- State 1: Dropzone Upload View when no document loaded and not hydrating -->
      <section
        v-if="!documentStore.hasDocument && !isHydrating"
        class="upload-dropzone-container"
        @dragover.prevent="isDragging = true"
        @dragleave.prevent="isDragging = false"
        @drop.prevent="handleFileDrop"
      >
        <div class="dropzone-card" :class="{ 'is-dragging': isDragging }">
          <div class="dropzone-icon-circle">
            <svg class="dropzone-svg" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8">
              <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
              <polyline points="14 2 14 8 20 8" />
              <line x1="12" y1="18" x2="12" y2="12" />
              <line x1="9" y1="15" x2="12" y2="12" />
              <line x1="15" y1="15" x2="12" y2="12" />
            </svg>
          </div>

          <h2 class="dropzone-title">Upload a PDF to Edit</h2>
          <p class="dropzone-subtitle">
            100% pixel-perfect canvas rendering. Modify any text in-place while keeping all vector tables, borders, logos, and stamps intact.
          </p>

          <div class="dropzone-actions">
            <label class="btn-browse-file" @click.prevent="triggerOpenNewFile">
              <input
                type="file"
                accept="application/pdf"
                class="hidden-file-input"
                @change="handleFileInput"
              />
              <svg class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
                <polyline points="17 8 12 3 7 8" />
                <line x1="12" y1="3" x2="12" y2="15" />
              </svg>
              <span>Browse Computer</span>
            </label>

            <span class="or-separator">or</span>

            <button
              type="button"
              class="btn-sample-doc btn-sample-tender"
              @click="loadSampleDocument"
            >
              <svg class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
                <polyline points="14 2 14 8 20 8" />
              </svg>
              <span>Open Tender RFQ PDF</span>
            </button>

            <button
              type="button"
              class="btn-sample-doc btn-sample-resume"
              @click="loadResumeDocument"
            >
              <svg class="icon-sm" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                <path d="M16 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2" />
                <circle cx="8.5" cy="7" r="4" />
                <polyline points="17 11 19 13 23 9" />
              </svg>
              <span>Open Resume PDF</span>
            </button>
          </div>

          <div class="dropzone-features">
            <div class="feature-item">
              <span class="feature-dot" />
              <span>1:1 PDF.js Canvas Rendering (Zero Layout Distortion)</span>
            </div>
            <div class="feature-item">
              <span class="feature-dot" />
              <span>Interactive Bounding-Box Inline Text Editing</span>
            </div>
            <div class="feature-item">
              <span class="feature-dot" />
              <span>Lossless In-Place PDFBox Redaction & Export</span>
            </div>
          </div>
        </div>
      </section>

      <!-- State 1.5: Seamless Hydration Loading Overlay -->
      <div v-else-if="isHydrating" class="session-hydrating-container">
        <div class="spinner-large" />
        <h3 class="loading-title">Restoring Workspace Session...</h3>
        <p class="loading-subtext">Restoring PDF document, canvas layout, and active edits from local storage.</p>
      </div>

      <!-- State 2: 100% Pixel-Perfect PDF.js Canvas with Interactive Overlay -->
      <PdfCanvasEditor
        v-else
        ref="canvasEditorRef"
        :document-model="documentStore.documentModel"
        :zoom="documentStore.zoom"
      />
    </main>

    <!-- Global Loading Overlay -->
    <div v-if="documentStore.isExtracting || documentStore.isExporting" class="loading-overlay">
      <div class="loading-modal">
        <div class="spinner-large" />
        <h3 class="loading-title">
          {{ documentStore.isExtracting ? 'Analyzing PDF Layout & Rendering...' : 'Saving In-Place Redactions...' }}
        </h3>
        <p class="loading-subtext">
          {{
            documentStore.isExtracting
              ? 'Extracting spatial text bounding boxes and preparing 1:1 canvas overlay.'
              : 'Applying whiteout bounding-box redactions and writing new text in-place using Apache PDFBox.'
          }}
        </p>
      </div>
    </div>

    <!-- Non-Blocking Confirmation Dialog for Document Close / Reset -->
    <div v-if="showCloseConfirmModal" class="loading-overlay" data-testid="close-confirm-modal">
      <div class="confirm-dialog-modal">
        <h3 class="confirm-modal-title">Close Document?</h3>
        <p class="confirm-modal-subtext">
          Are you sure you want to close the current document and return to the upload screen? Any unsaved edits will be discarded.
        </p>
        <div class="confirm-modal-actions">
          <button
            type="button"
            class="btn-confirm-cancel"
            data-testid="btn-cancel-close-doc"
            @click="showCloseConfirmModal = false"
          >
            Cancel
          </button>
          <button
            type="button"
            class="btn-confirm-proceed"
            data-testid="btn-confirm-close-doc"
            @click="executeCloseDocument"
          >
            Close Document
          </button>
        </div>
      </div>
    </div>

    <!-- Export Success Toast -->
    <transition name="toast-fade">
      <div v-if="exportToastMessage" class="export-toast" data-testid="export-toast">
        <svg class="toast-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5">
          <path d="M22 11.08V12a10 10 0 1 1-5.93-9.14" />
          <polyline points="22 4 12 14.01 9 11.01" />
        </svg>
        <span>{{ exportToastMessage }}</span>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue';
import { useRoute } from 'vue-router';
import { useDocumentStore } from '@/stores/document';
import EditorToolbar from '@/components/EditorToolbar.vue';
import PdfCanvasEditor from '@/components/PdfCanvasEditor.vue';
import sessionStorageService from '@/services/sessionStorageService';

const route = useRoute();
const documentStore = useDocumentStore();

const isDragging = ref(false);
const isHydrating = ref(true);
const headerFileInputRef = ref(null);
const canvasEditorRef = ref(null);
const exportToastMessage = ref('');
let toastTimer = null;
const isRecentlySaved = ref(false);
let saveStateTimer = null;
const showCloseConfirmModal = ref(false);

function showExportToast(msg) {
  exportToastMessage.value = msg;
  if (toastTimer) clearTimeout(toastTimer);
  toastTimer = setTimeout(() => {
    exportToastMessage.value = '';
    toastTimer = null;
  }, 4000);
}

function handleGlobalKeydown(e) {
  if ((e.ctrlKey || e.metaKey) && e.key.toLowerCase() === 's') {
    e.preventDefault();
    if (documentStore.hasDocument) {
      handleSave();
    }
  }
}

onMounted(async () => {
  window.addEventListener('keydown', handleGlobalKeydown);

  const sessionId = route?.params?.sessionId;
  if (sessionId) {
    try {
      await documentStore.loadFromSession(sessionId);
    } catch (err) {
      console.warn('[Session] Failed to load session from route:', err);
    } finally {
      isHydrating.value = false;
    }
    return;
  }

  try {
    const session = await sessionStorageService.loadSession();
    if (session && session.pdfBytes && session.pdfBytes.byteLength > 0) {
      documentStore.restoreSession(session);
    }
  } catch (err) {
    console.warn('[Session] Failed to restore session on mount:', err);
  } finally {
    isHydrating.value = false;
  }
});

onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleGlobalKeydown);
  if (saveStateTimer) clearTimeout(saveStateTimer);
  if (toastTimer) clearTimeout(toastTimer);
  if (canvasEditorRef.value?.purgeEditorState) {
    canvasEditorRef.value.purgeEditorState();
  }
  sessionStorageService.clearActiveDocument(documentStore.fileKey).catch(() => {});
});

async function triggerOpenNewFile() {
  if (typeof window !== 'undefined' && typeof window.showOpenFilePicker === 'function') {
    try {
      const [handle] = await window.showOpenFilePicker({
        types: [{
          description: 'PDF Document',
          accept: { 'application/pdf': ['.pdf'] }
        }],
        multiple: false
      });
      if (handle) {
        // Completely purge previous editor canvas overlays and state
        if (canvasEditorRef.value?.purgeEditorState) {
          canvasEditorRef.value.purgeEditorState();
        }
        const file = await handle.getFile();
        documentStore.fileHandle = handle;
        await documentStore.uploadAndExtract(file);
        return;
      }
    } catch (err) {
      if (err.name === 'AbortError') {
        return;
      }
      console.warn('[FileSystemAccess] showOpenFilePicker fallback to file input:', err);
    }
  }

  if (headerFileInputRef.value) {
    headerFileInputRef.value.value = '';
    headerFileInputRef.value.click();
  }
}

async function handleLogoClick() {
  if (documentStore.hasDocument) {
    await handleCloseDocument();
  }
}

function handleCloseDocument() {
  showCloseConfirmModal.value = true;
}

async function executeCloseDocument() {
  showCloseConfirmModal.value = false;
  const currentKey = documentStore.fileKey;
  if (canvasEditorRef.value?.purgeEditorState) {
    canvasEditorRef.value.purgeEditorState();
  }
  await sessionStorageService.clearActiveDocument(currentKey);
  await sessionStorageService.clearDraftCache();
  documentStore.resetDocument();
}

async function handleFileNameChange() {
  try {
    const session = await sessionStorageService.loadSession();
    if (session) {
      session.fileName = documentStore.fileName;
      await sessionStorageService.saveSession(session);
    }
  } catch (err) {
    console.warn('[Session] Failed to update fileName in session:', err);
  }
}

async function handleFileDrop(event) {
  isDragging.value = false;
  const files = event.dataTransfer?.files;
  if (files && files.length > 0) {
    const file = files[0];
    if (file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')) {
      if (canvasEditorRef.value?.purgeEditorState) {
        canvasEditorRef.value.purgeEditorState();
      }
      await uploadFile(file);
    } else {
      documentStore.errorMessage = 'Please upload a valid PDF document (.pdf).';
    }
  }
}

async function handleFileInput(event) {
  const files = event.target?.files;
  if (files && files.length > 0) {
    if (canvasEditorRef.value?.purgeEditorState) {
      canvasEditorRef.value.purgeEditorState();
    }
    await uploadFile(files[0]);
  }
  if (event.target) {
    event.target.value = '';
  }
}

async function uploadFile(file) {
  try {
    if (canvasEditorRef.value?.purgeEditorState) {
      canvasEditorRef.value.purgeEditorState();
    }
    await sessionStorageService.clearActiveDocument();
    await documentStore.uploadAndExtract(file);
  } catch {
    // Error is set in store
  }
}

async function loadSampleDocument() {
  try {
    if (canvasEditorRef.value?.purgeEditorState) {
      canvasEditorRef.value.purgeEditorState();
    }
    await sessionStorageService.clearActiveDocument();
    await documentStore.loadSample('sample.pdf');
  } catch {
    // Error is set in store
  }
}

async function loadResumeDocument() {
  try {
    if (canvasEditorRef.value?.purgeEditorState) {
      canvasEditorRef.value.purgeEditorState();
    }
    await sessionStorageService.clearActiveDocument();
    await documentStore.loadSample('resume.pdf');
  } catch {
    // Error is set in store
  }
}

async function handleSave() {
  if (documentStore.isSaving || !documentStore.hasDocument) return;

  try {
    if (canvasEditorRef.value?.commitActiveBlock) {
      canvasEditorRef.value.commitActiveBlock();
    }

    let edits = [];
    if (canvasEditorRef.value?.buildRedactionPayload) {
      edits = canvasEditorRef.value.buildRedactionPayload();
      documentStore.setEdits(edits);
    }
    const userRotations = canvasEditorRef.value?.getUserRotations ? canvasEditorRef.value.getUserRotations() : {};

    const result = await documentStore.saveDocument({ edits, userRotations });
    if (result?.cancelled) {
      return;
    }

    // Commit baseline in canvas editor so edits counter drops to 0 and canvas cleanly re-renders
    if (canvasEditorRef.value?.commitSavedBaseline && result?.pdfBytes) {
      await canvasEditorRef.value.commitSavedBaseline(result.pdfBytes);
    }

    isRecentlySaved.value = true;
    if (saveStateTimer) clearTimeout(saveStateTimer);
    saveStateTimer = setTimeout(() => {
      isRecentlySaved.value = false;
      saveStateTimer = null;
    }, 2500);

    const targetName = result.fileName || documentStore.fileName || 'PDF';
    const feedbackMsg = result.overwrittenDisk
      ? `Saved directly to ${targetName} ✓`
      : `Saved in workspace: ${targetName} ✓`;
    showExportToast(feedbackMsg);
  } catch (err) {
    console.error('Save failed:', err);
    documentStore.errorMessage = err.message || 'Failed to save document.';
  } finally {
    documentStore.isCommittedSave = false;
  }
}

async function handleExport() {
  try {
    if (canvasEditorRef.value?.commitActiveBlock) {
      canvasEditorRef.value.commitActiveBlock();
    }
    if (canvasEditorRef.value?.buildRedactionPayload) {
      const edits = canvasEditorRef.value.buildRedactionPayload();
      documentStore.setEdits(edits);
    }
    const userRotations = canvasEditorRef.value?.getUserRotations ? canvasEditorRef.value.getUserRotations() : {};
    await documentStore.downloadPdf({ userRotations });
    showExportToast('PDF modified and downloaded successfully!');
  } catch (err) {
    console.error('Export failed:', err);
  }
}

function handleRevertAll() {
  if (canvasEditorRef.value?.revertAll) {
    canvasEditorRef.value.revertAll();
  } else {
    documentStore.clearAllPatches(true);
  }
}

function handleUndo() {
  if (canvasEditorRef.value?.executeUndo) {
    canvasEditorRef.value.executeUndo();
  } else {
    documentStore.undo();
  }
}

function handleRedo() {
  if (canvasEditorRef.value?.executeRedo) {
    canvasEditorRef.value.executeRedo();
  } else {
    documentStore.redo();
  }
}

function confirmReset() {
  triggerOpenNewFile();
}
</script>

<style scoped>
.editor-view-container {
  display: flex;
  flex-direction: column;
  width: 100%;
  height: 100%;
  overflow: hidden;
  background-color: var(--color-bg-workspace, #0f172a);
}

/* Header Bar */
.app-header {
  height: var(--header-height, 56px);
  background-color: #0f172a;
  color: #ffffff;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  border-bottom: 1px solid #1e293b;
  flex-shrink: 0;
  z-index: 30;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 16px;
}

.brand-badge {
  display: flex;
  align-items: center;
  gap: 10px;
  font-weight: 700;
  font-size: 16px;
  letter-spacing: -0.02em;
  color: #f8fafc;
}

.brand-icon {
  width: 22px;
  height: 22px;
  color: #818cf8;
}

.version-tag {
  font-size: 10px;
  font-weight: 700;
  text-transform: uppercase;
  background: rgba(99, 102, 241, 0.2);
  color: #818cf8;
  border: 1px solid rgba(99, 102, 241, 0.4);
  padding: 2px 6px;
  border-radius: 4px;
}

.document-meta-info {
  display: flex;
  align-items: center;
  gap: 12px;
}

.document-title-input {
  background: transparent;
  border: 1px solid transparent;
  color: #e2e8f0;
  font-size: 14px;
  font-weight: 500;
  padding: 4px 8px;
  border-radius: var(--radius-sm, 4px);
  outline: none;
  max-width: 260px;
  transition: all 0.15s ease;
}

.document-title-input:hover,
.document-title-input:focus {
  background: #1e293b;
  border-color: #334155;
  color: #ffffff;
}

.badge-status {
  background: #1e293b;
  color: #94a3b8;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 500;
  border: 1px solid #334155;
}

.badge-edits {
  background: rgba(16, 185, 129, 0.15);
  color: #34d399;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  border: 1px solid rgba(16, 185, 129, 0.3);
}

.badge-session-mode {
  background: rgba(99, 102, 241, 0.2);
  color: #a5b4fc;
  padding: 2px 8px;
  border-radius: 4px;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.04em;
  border: 1px solid rgba(99, 102, 241, 0.4);
  text-transform: uppercase;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.btn-secondary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  height: 32px;
  border-radius: var(--radius-sm, 4px);
  background: #1e293b;
  color: #e2e8f0;
  font-size: 13px;
  font-weight: 500;
  border: 1px solid #334155;
  transition: all 0.15s ease;
}

.btn-secondary:hover {
  background: #334155;
  color: #ffffff;
}

.btn-primary {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 16px;
  height: 32px;
  border-radius: var(--radius-sm, 4px);
  background: #4f46e5;
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
  box-shadow: 0 2px 4px rgba(79, 70, 229, 0.3);
  transition: all 0.15s ease;
  border: none;
  cursor: pointer;
}

.btn-primary:hover:not(:disabled) {
  background: #4338ca;
  box-shadow: 0 4px 8px rgba(79, 70, 229, 0.4);
}

.btn-primary:disabled {
  opacity: 0.6;
  cursor: not-allowed;
}

.icon-sm {
  width: 14px;
  height: 14px;
}

/* Error Banner */
.error-banner {
  background-color: #450a0a;
  border-bottom: 1px solid #7f1d1d;
  color: #fca5a5;
  padding: 10px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 13px;
  font-weight: 500;
}

.error-content {
  display: flex;
  align-items: center;
  gap: 10px;
}

.error-icon {
  width: 16px;
  height: 16px;
  flex-shrink: 0;
}

.btn-dismiss-error {
  font-size: 18px;
  background: none;
  border: none;
  color: #fca5a5;
  cursor: pointer;
  opacity: 0.7;
}

.btn-dismiss-error:hover {
  opacity: 1;
}

/* Workspace */
.editor-workspace {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  position: relative;
  background-color: #0f172a;
}

/* Dropzone */
.upload-dropzone-container {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 40px 20px;
}

.dropzone-card {
  max-width: 580px;
  width: 100%;
  background: #1e293b;
  border: 2px dashed #475569;
  border-radius: var(--radius-lg, 12px);
  padding: 48px 36px;
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.3);
  transition: all 0.2s ease;
}

.dropzone-card.is-dragging {
  border-color: #6366f1;
  background-color: rgba(99, 102, 241, 0.1);
  transform: scale(1.01);
}

.dropzone-icon-circle {
  width: 68px;
  height: 68px;
  border-radius: 50%;
  background-color: rgba(99, 102, 241, 0.15);
  color: #818cf8;
  display: flex;
  align-items: center;
  justify-content: center;
  margin-bottom: 20px;
}

.dropzone-svg {
  width: 34px;
  height: 34px;
}

.dropzone-title {
  font-size: 22px;
  font-weight: 700;
  color: #f8fafc;
  margin-bottom: 8px;
}

.dropzone-subtitle {
  font-size: 14px;
  color: #94a3b8;
  margin-bottom: 32px;
  line-height: 1.6;
}

.dropzone-actions {
  display: flex;
  align-items: center;
  gap: 14px;
  margin-bottom: 36px;
}

.btn-browse-file {
  position: relative;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 20px;
  height: 42px;
  border-radius: var(--radius-sm, 6px);
  background-color: #4f46e5;
  color: #ffffff;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 4px 12px rgba(79, 70, 229, 0.3);
  transition: all 0.15s ease;
}

.btn-browse-file:hover {
  background-color: #4338ca;
  box-shadow: 0 6px 16px rgba(79, 70, 229, 0.4);
}

.hidden-file-input {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  opacity: 0;
  cursor: pointer;
}

.or-separator {
  color: #64748b;
  font-size: 13px;
  font-weight: 500;
}

.btn-sample-doc {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 0 18px;
  height: 42px;
  border-radius: var(--radius-sm, 6px);
  background-color: #334155;
  color: #f8fafc;
  border: 1px solid #475569;
  font-size: 14px;
  font-weight: 600;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-sample-doc:hover {
  background-color: #475569;
  border-color: #64748b;
}

.dropzone-features {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: 100%;
  padding-top: 24px;
  border-top: 1px solid #334155;
}

.feature-item {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12.5px;
  color: #94a3b8;
  justify-content: center;
}

.feature-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background-color: #10b981;
}

/* Loading Overlay */
.loading-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 100%;
  height: 100%;
  background-color: rgba(15, 23, 42, 0.75);
  backdrop-filter: blur(4px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 50;
}

.loading-modal {
  background: #1e293b;
  border: 1px solid #334155;
  padding: 32px 40px;
  border-radius: var(--radius-lg, 12px);
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5);
  display: flex;
  flex-direction: column;
  align-items: center;
  text-align: center;
  max-width: 420px;
}

.spinner-large {
  width: 46px;
  height: 46px;
  border: 4px solid #334155;
  border-top-color: #818cf8;
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  margin-bottom: 20px;
}

.loading-title {
  font-size: 18px;
  font-weight: 700;
  color: #f8fafc;
  margin-bottom: 8px;
}

.loading-subtext {
  font-size: 13px;
  color: #94a3b8;
  line-height: 1.5;
}

@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}

.session-hydrating-container {
  flex: 1;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 40px;
}

.brand-badge.is-clickable {
  cursor: pointer;
  transition: opacity 0.15s ease;
}

.brand-badge.is-clickable:hover {
  opacity: 0.85;
}

.btn-close-doc {
  background-color: transparent;
  border-color: #475569;
  color: #94a3b8;
}

.btn-close-doc:hover {
  background-color: rgba(239, 68, 68, 0.15);
  border-color: #ef4444;
  color: #f87171;
}

.export-toast {
  position: fixed;
  bottom: 24px;
  right: 24px;
  display: flex;
  align-items: center;
  gap: 10px;
  background: rgba(16, 185, 129, 0.95);
  backdrop-filter: blur(8px);
  color: #ffffff;
  padding: 12px 20px;
  border-radius: 8px;
  font-size: 14px;
  font-weight: 600;
  box-shadow: 0 10px 25px -5px rgba(0, 0, 0, 0.5), 0 0 15px rgba(16, 185, 129, 0.4);
  z-index: 9999;
}

.toast-icon {
  width: 20px;
  height: 20px;
  stroke: #ffffff;
}

.toast-fade-enter-active,
.toast-fade-leave-active {
  transition: all 0.3s ease;
}

.toast-fade-enter-from,
.toast-fade-leave-to {
  opacity: 0;
  transform: translateY(10px);
}

.btn-save-doc {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 0 14px;
  height: 32px;
  border-radius: var(--radius-sm, 4px);
  background: #047857;
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
  border: 1px solid #059669;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-save-doc:hover:not(:disabled) {
  background: #059669;
  box-shadow: 0 0 10px rgba(16, 185, 129, 0.4);
}

.btn-save-doc.is-saved {
  background: #065f46;
  border-color: #10b981;
}

.btn-save-doc:disabled {
  opacity: 0.65;
  cursor: not-allowed;
}

.btn-kbd {
  display: inline-block;
  padding: 1px 5px;
  font-size: 10px;
  font-family: inherit;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.85);
  background: rgba(0, 0, 0, 0.25);
  border-radius: 3px;
  border: 1px solid rgba(255, 255, 255, 0.2);
  margin-left: 2px;
}

.text-green {
  color: #34d399;
}

.confirm-dialog-modal {
  background-color: #1e293b;
  border: 1px solid #334155;
  border-radius: var(--radius-lg, 12px);
  padding: 24px;
  max-width: 420px;
  width: 90%;
  display: flex;
  flex-direction: column;
  gap: 12px;
  box-shadow: 0 20px 25px -5px rgba(0, 0, 0, 0.5), 0 10px 10px -5px rgba(0, 0, 0, 0.4);
  animation: modalScaleIn 0.2s cubic-bezier(0.16, 1, 0.3, 1);
}

@keyframes modalScaleIn {
  from {
    opacity: 0;
    transform: scale(0.95);
  }
  to {
    opacity: 1;
    transform: scale(1);
  }
}

.confirm-modal-title {
  margin: 0;
  font-size: 17px;
  font-weight: 700;
  color: #ffffff;
}

.confirm-modal-subtext {
  margin: 0;
  font-size: 13px;
  color: #94a3b8;
  line-height: 1.5;
}

.confirm-modal-actions {
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 8px;
}

.btn-confirm-cancel {
  background: transparent;
  border: 1px solid #475569;
  color: #cbd5e1;
  font-size: 13px;
  font-weight: 500;
  padding: 7px 14px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-confirm-cancel:hover {
  background: #334155;
  color: #f8fafc;
}

.btn-confirm-proceed {
  background: #dc2626;
  border: 1px solid #ef4444;
  color: #ffffff;
  font-size: 13px;
  font-weight: 600;
  padding: 7px 16px;
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s ease;
}

.btn-confirm-proceed:hover {
  background: #b91c1c;
}
</style>
