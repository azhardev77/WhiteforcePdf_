import { defineStore } from 'pinia';
import { extractPdfClient } from '@/services/clientPdfExtractor';
import { exportModifiedPdf, triggerBrowserDownload } from '@/services/pdfLibExportService';
import sessionStorageService from '@/services/sessionStorageService';
import { extractPdf, extractSample, fetchRawSamplePdf } from '@/api/documents';
import { exportPdf, exportRedactedPdf, requestRedactedPdf, exportHtml } from '@/api/export';
import { fetchAppSessionPdf, fetchAppSessionInfo } from '@/api/sessions';
import { convertTipTapToDocumentModel } from '@/utils/tipTapToJson';

/**
 * Extracts plain text from a Block or Cell structure.
 */
function extractBlockText(block) {
  if (!block) return '';
  if (Array.isArray(block.runs) && block.runs.length > 0) {
    return block.runs.map(r => r.text || '').join('');
  }
  if (block.type === 'TABLE' && Array.isArray(block.cells)) {
    return block.cells
      .map(row => (Array.isArray(row) ? row.map(cell => extractBlockText(cell)).join('\t') : ''))
      .join('\n');
  }
  return typeof block.text === 'string' ? block.text : '';
}

/**
 * Extracts typography and color styles from a Block.
 */
function extractBlockStyle(block, defaultStyle = {}) {
  const firstRun = block?.runs?.find(r => r && r.style) || null;
  const runStyle = firstRun?.style || {};
  return {
    fontSize: runStyle.fontSize || block?.fontSize || block?.customFontSize || defaultStyle.fontSize || 12,
    fontFamily: runStyle.fontFamily || block?.fontFamily || block?.fontName || defaultStyle.fontFamily || 'Helvetica',
    bold: runStyle.bold !== undefined ? runStyle.bold : (block?.bold !== undefined ? block.bold : Boolean(defaultStyle.bold)),
    italic: runStyle.italic !== undefined ? runStyle.italic : (block?.italic !== undefined ? block.italic : Boolean(defaultStyle.italic)),
    color: runStyle.color || block?.color || block?.customColor || defaultStyle.color || '#000000'
  };
}

/**
 * Checks whether a TipTap AST node contains explicit styling marks.
 */
function nodeHasMarks(node) {
  if (!node) return false;
  if (Array.isArray(node.content)) {
    return node.content.some(child => {
      if (Array.isArray(child.marks) && child.marks.length > 0) return true;
      if (Array.isArray(child.content)) return nodeHasMarks(child);
      return false;
    });
  }
  return false;
}

export const useDocumentStore = defineStore('document', {
  state: () => ({
    documentModel: null,
    originalPdfData: null, // ArrayBuffer or Uint8Array
    originalFile: null,    // File or Blob
    fileHandle: null,      // FileSystemFileHandle when opened via File System Access API
    fileKey: null,         // Unique document hash/identifier for scoped storage
    edits: [],             // Array of TextRedactionEdit
    blockPatches: new Map(), // Map<string, BlockEditPatch> where key is `${pageIndex}_${blockId}`
    activeBlockId: null,
    zoom: 1.25,
    isExtracting: false,
    isExporting: false,
    isSaving: false,
    isCommittedSave: false,
    lastSavedTime: null,
    errorMessage: null,
    fileName: '',
    restoredSession: null,
    isRestoredSession: false,
    sessionId: null,
    sessionMode: null,
    sessionSource: null,
    history: [],           // Array<HistorySnapshot>, max 30
    future: []             // Array<HistorySnapshot> for redo
  }),

  getters: {
    canUndo: (state) => (state.history?.length || 0) > 0,
    canRedo: (state) => (state.future?.length || 0) > 0,
    hasDocument: (state) => {
      return Boolean(
        state.documentModel &&
        Array.isArray(state.documentModel.pages) &&
        state.documentModel.pages.length > 0 &&
        state.originalPdfData
      );
    },
    pageCount: (state) => {
      return state.documentModel?.pages?.length || 0;
    },
    totalPages: (state) => {
      return state.documentModel?.pages || [];
    },
    paragraphEditsCount: (state) => {
      if (!state.blockPatches) return 0;
      let count = 0;
      for (const [key, patch] of state.blockPatches.entries()) {
        if (patch && key.includes('_')) {
          count++;
        }
      }
      return count;
    },
    editsCount: (state) => {
      let patchCount = 0;
      if (state.blockPatches) {
        for (const [key, patch] of state.blockPatches.entries()) {
          if (patch && key.includes('_')) {
            patchCount++;
          }
        }
      }
      return patchCount > 0 ? patchCount : state.edits.length;
    }
  },

  actions: {
    /**
     * Constructs the canonical key for a block patch: `${pageIndex}_${blockId}`.
     */
    getPatchKey(pageIndex, blockId) {
      return `${pageIndex}_${blockId}`;
    },

    /**
     * Retrieves an individual block patch by pageIndex and blockId.
     */
    getPatch(pageIndex, blockId) {
      if (!this.blockPatches) return null;
      const key = this.getPatchKey(pageIndex, blockId);
      return this.blockPatches.get(key) || this.blockPatches.get(`${pageIndex}${blockId}`) || null;
    },

    /**
     * Sets or updates an individual BlockEditPatch in the modular patch store.
     */
    setPatch(pageIndex, blockId, patchData) {
      if (!this.blockPatches) {
        this.blockPatches = new Map();
      }
      const key = this.getPatchKey(pageIndex, blockId);
      const existing = this.blockPatches.get(key) || {};
      const updatedPatch = {
        ...existing,
        ...patchData,
        pageIndex,
        blockId,
        timestamp: Date.now()
      };
      this.blockPatches.set(key, updatedPatch);
      // Also set secondary key for flexible lookup
      this.blockPatches.set(`${pageIndex}${blockId}`, updatedPatch);
    },

    /**
     * Deletes a specific block edit patch (per-paragraph revert).
     */
    deletePatch(pageIndex, blockId) {
      if (!this.blockPatches) return;
      const key = this.getPatchKey(pageIndex, blockId);
      this.blockPatches.delete(key);
      this.blockPatches.delete(`${pageIndex}${blockId}`);
    },

    /**
     * Clears all patches simultaneously (global Revert All).
     */
    clearAllPatches(recordHistory = false) {
      if (recordHistory && this.blockPatches && this.blockPatches.size > 0) {
        this.pushHistorySnapshot();
      }
      if (this.blockPatches) {
        this.blockPatches.clear();
      }
      this.edits = [];
      this.activeBlockId = null;
    },

    /**
     * Pushes a history snapshot for Undo/Redo tracking.
     */
    pushHistorySnapshot(customSnapshot = null) {
      if (!this.history) this.history = [];
      const snapshot = customSnapshot || {
        blockPatches: Array.from((this.blockPatches || new Map()).entries()),
        edits: Array.isArray(this.edits) ? JSON.parse(JSON.stringify(this.edits)) : [],
        documentPages: this.documentModel?.pages ? JSON.parse(JSON.stringify(this.documentModel.pages)) : [],
        timestamp: Date.now()
      };
      this.history.push(snapshot);
      if (this.history.length > 30) {
        this.history.shift();
      }
      this.future = [];
    },

    /**
     * Undoes the last edit action.
     */
    undo(customCurrentSnapshot = null) {
      if (!this.history || this.history.length === 0) return null;
      if (!this.future) this.future = [];
      const currentSnapshot = customCurrentSnapshot || {
        blockPatches: Array.from((this.blockPatches || new Map()).entries()),
        edits: Array.isArray(this.edits) ? JSON.parse(JSON.stringify(this.edits)) : [],
        documentPages: this.documentModel?.pages ? JSON.parse(JSON.stringify(this.documentModel.pages)) : [],
        timestamp: Date.now()
      };
      this.future.push(currentSnapshot);

      const prevSnapshot = this.history.pop();
      this.applySnapshot(prevSnapshot);
      return prevSnapshot;
    },

    /**
     * Redoes the last undone action.
     */
    redo(customCurrentSnapshot = null) {
      if (!this.future || this.future.length === 0) return null;
      if (!this.history) this.history = [];
      const currentSnapshot = customCurrentSnapshot || {
        blockPatches: Array.from((this.blockPatches || new Map()).entries()),
        edits: Array.isArray(this.edits) ? JSON.parse(JSON.stringify(this.edits)) : [],
        documentPages: this.documentModel?.pages ? JSON.parse(JSON.stringify(this.documentModel.pages)) : [],
        timestamp: Date.now()
      };
      this.history.push(currentSnapshot);

      const nextSnapshot = this.future.pop();
      this.applySnapshot(nextSnapshot);
      return nextSnapshot;
    },

    applySnapshot(snapshot) {
      if (!snapshot) return;
      this.blockPatches = new Map(snapshot.blockPatches || []);
      this.edits = Array.isArray(snapshot.edits) ? [...snapshot.edits] : [];
      if (snapshot.documentPages && Array.isArray(snapshot.documentPages) && this.documentModel) {
        this.documentModel.pages = JSON.parse(JSON.stringify(snapshot.documentPages));
      }
    },

    /**
     * Adds a dynamic blank page to the document model for spillover.
     */
    addBlankPage(width = 595.28, height = 841.89) {
      if (!this.documentModel) return 1;
      if (!Array.isArray(this.documentModel.pages)) {
        this.documentModel.pages = [];
      }
      const prevPage = this.documentModel.pages[this.documentModel.pages.length - 1];
      const newPageNum = this.documentModel.pages.length + 1;
      const pageW = prevPage?.width || width;
      const pageH = prevPage?.height || height;
      this.documentModel.pages.push({
        pageNumber: newPageNum,
        width: pageW,
        height: pageH,
        blocks: [],
        lines: [],
        isBlankPage: true
      });
      return newPageNum;
    },

    /**
     * Removes a dynamic blank page from the document model if empty or cleaned up.
     */
    removeDynamicPage(pageNumber) {
      if (!this.documentModel?.pages) return;
      this.documentModel.pages = this.documentModel.pages.filter(
        p => !(p.pageNumber === pageNumber && p.isBlankPage)
      );
    },

    /**
     * Completely purges all document-related states, edit arrays, and session markers.
     */
    purgeDocumentState() {
      this.documentModel = null;
      this.originalPdfData = null;
      this.originalFile = null;
      this.fileHandle = null;
      this.fileKey = null;
      this.edits = [];
      if (this.blockPatches) {
        this.blockPatches.clear();
      }
      this.activeBlockId = null;
      this.fileName = '';
      this.errorMessage = null;
      this.isExtracting = false;
      this.isExporting = false;
      this.isSaving = false;
      this.isCommittedSave = false;
      this.lastSavedTime = null;
      this.restoredSession = null;
      this.isRestoredSession = false;
      this.sessionId = null;
      this.sessionMode = null;
      this.sessionSource = null;
      this.zoom = 1.25;
      this.history = [];
      this.future = [];
    },

    /**
     * Uploads a user PDF, purges previous state, stores raw binary, and extracts layout client-side.
     *
     * @param {File} file - PDF file to upload
     * @returns {Promise<Object>} Extracted DocumentModel
     */
    async uploadAndExtract(file) {
      if (!file) return null;

      // 1. Completely purge any previous document state before new load
      this.purgeDocumentState();
      this.isExtracting = true;
      this.fileName = file.name;

      // 2. Clear any active draft cache in storage so previous edits never leak
      try {
        await sessionStorageService.clearActiveDocument();
        await sessionStorageService.clearDraftCache();
      } catch (cacheErr) {
        console.warn('[documentStore] clearDraftCache warning:', cacheErr);
      }

      try {
        const arrayBuffer = await file.arrayBuffer();
        const fileKey = await sessionStorageService.computeFileHash(arrayBuffer, {
          fileName: file.name,
          fileSize: file.size,
          lastModified: file.lastModified
        });

        // 100% Client-Side Extraction via pdfjs-dist
        let model = null;
        try {
          model = await extractPdfClient(arrayBuffer, file.name);
        } catch (clientErr) {
          console.warn('[documentStore] Client extraction failed, trying backend fallback:', clientErr);
          model = await extractPdf(file);
          if (model && !model.name) {
            model.name = file.name;
          }
        }

        // Atomically commit new document state
        this.originalPdfData = arrayBuffer;
        this.originalFile = file;
        this.fileKey = fileKey;
        this.documentModel = model;
        return model;
      } catch (err) {
        this.errorMessage = err.message || 'Failed to extract PDF document';
        throw err;
      } finally {
        this.isExtracting = false;
      }
    },

    /**
     * Loads a sample PDF, purges previous state, and extracts layout.
     *
     * @param {string} sampleFileName - Filename of sample PDF
     * @returns {Promise<Object>} Extracted DocumentModel
     */
    async loadSample(sampleFileName = 'sample.pdf') {
      // 1. Completely purge any previous document state before new load
      this.purgeDocumentState();
      this.isExtracting = true;
      this.fileName = sampleFileName;

      // 2. Clear any active draft cache in storage so previous edits never leak
      try {
        await sessionStorageService.clearActiveDocument();
        await sessionStorageService.clearDraftCache();
      } catch (cacheErr) {
        console.warn('[documentStore] clearDraftCache warning:', cacheErr);
      }

      try {
        let rawBuffer = null;

        // 1. Primary: Load directly from frontend public assets (/samples/...)
        try {
          const resp = await fetch(`/samples/${sampleFileName}`);
          if (resp.ok) {
            rawBuffer = await resp.arrayBuffer();
          }
        } catch (staticErr) {
          console.warn(`[documentStore] Static fetch /samples/${sampleFileName} failed:`, staticErr);
        }

        // 2. Secondary: Fallback to backend API if available
        if (!rawBuffer) {
          try {
            rawBuffer = await fetchRawSamplePdf(sampleFileName);
          } catch (backendRawErr) {
            console.warn('[documentStore] Backend fetchRawSamplePdf failed:', backendRawErr);
          }
        }

        if (!rawBuffer || rawBuffer.byteLength === 0) {
          throw new Error(`Failed to load sample PDF: ${sampleFileName}`);
        }

        const fileKey = await sessionStorageService.computeFileHash(rawBuffer, {
          fileName: sampleFileName,
          fileSize: rawBuffer.byteLength
        });

        // Pure Client-side DocumentModel generation
        let model = null;
        try {
          model = await extractPdfClient(rawBuffer, sampleFileName);
        } catch (clientErr) {
          console.warn('[documentStore] Client sample model extraction failed, trying backend:', clientErr);
          model = await extractSample(sampleFileName);
        }

        // Atomically commit new document state
        this.originalPdfData = rawBuffer;
        this.originalFile = new Blob([rawBuffer.slice(0)], { type: 'application/pdf' });
        this.fileKey = fileKey;
        this.documentModel = model;
        return model;
      } catch (err) {
        this.errorMessage = err.message || `Failed to extract sample PDF: ${sampleFileName}`;
        throw err;
      } finally {
        this.isExtracting = false;
      }
    },

    /**
     * Loads a PDF document from an active backend session.
     *
     * @param {string} sessionId - Backend session ID
     * @returns {Promise<Object>} Extracted DocumentModel
     */
    async loadFromSession(sessionId) {
      if (!sessionId) return null;

      this.purgeDocumentState();
      this.isExtracting = true;
      this.sessionId = sessionId;
      this.fileName = `${sessionId}.pdf`;

      try {
        await sessionStorageService.clearActiveDocument();
        await sessionStorageService.clearDraftCache();
      } catch (cacheErr) {
        console.warn('[documentStore] clearDraftCache warning:', cacheErr);
      }

      try {
        const { arrayBuffer, mode, source } = await fetchAppSessionPdf(sessionId);

        if (mode) this.sessionMode = mode;
        if (source) this.sessionSource = source;

        if (!this.sessionMode || !this.sessionSource) {
          try {
            const meta = await fetchAppSessionInfo(sessionId);
            if (meta?.mode) this.sessionMode = meta.mode;
            if (meta?.source) this.sessionSource = meta.source;
          } catch {
            // Non-critical if headers were missing
          }
        }

        if (!arrayBuffer || arrayBuffer.byteLength === 0) {
          throw new Error('Session PDF content is empty');
        }

        const fileKey = await sessionStorageService.computeFileHash(arrayBuffer, {
          sessionId,
          fileSize: arrayBuffer.byteLength
        });

        let model = null;
        try {
          model = await extractPdfClient(arrayBuffer, this.fileName);
        } catch (clientErr) {
          console.warn('[documentStore] Client extraction failed for session, trying fallback:', clientErr);
          const blob = new Blob([arrayBuffer], { type: 'application/pdf' });
          const file = new File([blob], this.fileName, { type: 'application/pdf' });
          model = await extractPdf(file);
          if (model && !model.name) {
            model.name = this.fileName;
          }
        }

        this.originalPdfData = arrayBuffer;
        this.originalFile = new Blob([arrayBuffer], { type: 'application/pdf' });
        this.fileKey = fileKey;
        this.documentModel = model;
        return model;
      } catch (err) {
        this.errorMessage = err.message || `Failed to load session: ${sessionId}`;
        throw err;
      } finally {
        this.isExtracting = false;
      }
    },

    /**
     * Records or updates a text edit / redaction on a specific bounding box.
     *
     * @param {Object} edit - { id, pageNumber, bbox, originalText, newText, style, backgroundColor }
     */
    recordEdit(edit) {
      if (!edit || !edit.id) return;
      const index = this.edits.findIndex(e => e.id === edit.id);

      // If text matches original, remove edit
      if (edit.newText === edit.originalText && (!edit.style || !edit.isStyleModified)) {
        if (index !== -1) {
          this.edits.splice(index, 1);
        }
        return;
      }

      // Strict Whiteout vs Transparent Logic:
      // Transparent mode is ONLY permitted if target text contains EXCLUSIVELY dots, dashes, or underscores.
      // If original text contains alphanumeric characters, transparent mode is strictly false (Whiteout).
      const trimmedOrig = String(edit.originalText || '').trim();
      const isPureBlank = trimmedOrig.length === 0 || /^[.\s_\-–—]+$/.test(trimmedOrig);
      const transparentBackground = isPureBlank ? Boolean(edit.transparentBackground) : false;

      const payload = {
        id: edit.id,
        pageNumber: edit.pageNumber,
        bbox: edit.bbox,
        originalText: edit.originalText,
        newText: edit.newText,
        style: edit.style,
        backgroundColor: '#FFFFFF',
        transparentBackground: transparentBackground,
        pageRotation: edit.pageRotation || 0
      };

      if (index !== -1) {
        this.edits[index] = payload;
      } else {
        this.edits.push(payload);
      }
    },

    /**
     * Sets or synchronizes the full list of redaction edits.
     *
     * @param {Array<Object>} newEdits - Updated list of edits
     */
    setEdits(newEdits) {
      this.edits = Array.isArray(newEdits) ? [...newEdits] : [];
    },

    /**
     * Removes an existing edit by ID (reverts the element back to original state).
     *
     * @param {string} id - Bounding box or line item ID
     */
    removeEdit(id) {
      const index = this.edits.findIndex(e => e.id === id);
      if (index !== -1) {
        this.edits.splice(index, 1);
      }
    },

    /**
     * Clears all recorded edits and active block selection.
     */
    clearAllEdits() {
      this.edits = [];
      this.activeBlockId = null;
    },

    /**
     * Retrieves an existing edit for a given element ID.
     */
    getEdit(id) {
      return this.edits.find(e => e.id === id);
    },

    /**
     * Sets current zoom level.
     */
    setZoom(newZoom) {
      this.zoom = Math.min(Math.max(newZoom, 0.5), 3.0);
    },

    /**
     * Exports the current document with in-place redactions applied directly on original PDF using pdf-lib.
     *
     * @param {Object} [options] - Optional export settings (e.g. { userRotations })
     * @returns {Promise<Blob>} Exported PDF binary blob
     */
    async downloadPdf(options = {}) {
      if (!this.originalPdfData && !this.originalFile) {
        const err = new Error('No active document available for export.');
        this.errorMessage = err.message;
        throw err;
      }

      this.isExporting = true;
      this.errorMessage = null;

      try {
        let buffer = this.originalPdfData;
        if (!buffer && this.originalFile) {
          buffer = await this.originalFile.arrayBuffer();
        }

        const exportName = (this.fileName || 'document.pdf').replace(/\.pdf$/i, '') + '_edited.pdf';
        const userRotations = options.userRotations || this.restoredSession?.viewSettings?.userRotations || {};
        const fileBlob = this.originalFile || new Blob([buffer.slice(0)], { type: 'application/pdf' });

        // 1. Primary: Route through backend PDFBox true token-stripping endpoint
        const hasEdits = Array.isArray(this.edits) && this.edits.length > 0;
        if (hasEdits) {
          try {
            const redactedBlob = await requestRedactedPdf(fileBlob, this.edits, exportName);
            if (redactedBlob && redactedBlob.size > 0) {
              triggerBrowserDownload(redactedBlob, exportName);
              return redactedBlob;
            }
            throw new Error('Server returned empty response.');
          } catch (backendErr) {
            console.error('[documentStore] Backend /api/export/redact failed:', backendErr);
            this.errorMessage = 'Server unavailable. Export blocked so hidden text is not leaked.';
            throw new Error(this.errorMessage);
          }
        }

        // 2. Client-side export for documents without text edits (e.g. view settings / userRotations)
        const modifiedBytes = await exportModifiedPdf(buffer, this.edits, { userRotations });
        const blob = new Blob([modifiedBytes], { type: 'application/pdf' });
        triggerBrowserDownload(blob, exportName);
        return blob;
      } catch (err) {
        this.errorMessage = err.message || 'Failed to export document to PDF.';
        throw err;
      } finally {
        this.isExporting = false;
      }
    },

    /**
     * Clears error message.
     */
    clearError() {
      this.errorMessage = null;
    },

    /**
     * Restores state from an IndexedDB saved session.
     *
     * @param {Object} session - Session record from IndexedDB
     */
    restoreSession(session) {
      if (!session || !session.pdfBytes) return;
      this.isExtracting = false;
      this.errorMessage = null;
      this.fileName = session.fileName || 'document.pdf';
      this.fileKey = session.fileKey || null;

      let buffer = session.pdfBytes;
      if (buffer instanceof Uint8Array) {
        buffer = buffer.buffer.slice(buffer.byteOffset, buffer.byteOffset + buffer.byteLength);
      } else if (buffer instanceof ArrayBuffer) {
        buffer = buffer.slice(0);
      }

      this.originalPdfData = buffer;
      this.originalFile = new Blob([buffer.slice(0)], { type: 'application/pdf' });
      this.documentModel = session.documentModel || {
        name: session.fileName || 'document.pdf',
        pages: [{ pageNumber: 1, width: 595.28, height: 841.89 }]
      };
      if (session.viewSettings?.zoom) {
        this.zoom = Math.min(Math.max(session.viewSettings.zoom, 0.5), 3.0);
      }
      this.edits = Array.isArray(session.edits) ? [...session.edits] : [];
      if (session.blockPatches) {
        if (session.blockPatches instanceof Map) {
          this.blockPatches = new Map(session.blockPatches);
        } else if (Array.isArray(session.blockPatches)) {
          this.blockPatches = new Map(session.blockPatches);
        } else if (typeof session.blockPatches === 'object') {
          this.blockPatches = new Map(Object.entries(session.blockPatches));
        }
      } else {
        this.blockPatches = new Map();
      }

      // Reconstruct patches from legacy edits if blockPatches was empty
      if (this.blockPatches.size === 0 && Array.isArray(session.edits) && session.edits.length > 0) {
        for (const edit of session.edits) {
          const pageNum = edit.pageNumber || 1;
          const blockId = edit.lineId || edit.id?.replace(/^redact-block-/, '') || `edit-${Date.now()}`;
          this.setPatch(pageNum, blockId, {
            originalText: edit.originalText,
            newText: edit.newText,
            fontSize: edit.style?.fontSize,
            fontFamily: edit.style?.fontFamily,
            bold: edit.style?.bold,
            italic: edit.style?.italic,
            color: edit.style?.color,
            customFontSize: edit.style?.fontSize,
            customColor: edit.style?.color,
            isEdited: true
          });
        }
      }

      this.restoredSession = session;
      this.isRestoredSession = true;
    },

    /**
     * Saves edits directly into the same PDF file:
     * 1. Routes through backend PDFBox true token-stripping endpoint (or fallback to pdf-lib).
     * 2. Overwrites local disk file via FileSystemFileHandle (or showSaveFilePicker / download).
     * 3. Updates active workspace state and IndexedDB with the new baseline pdfBytes (0 pending edits).
     *
     * @param {Object} [options]
     * @param {Array} [options.edits] - Optional explicit edits payload
     * @param {Object} [options.userRotations] - Optional page rotation map
     * @returns {Promise<Object>} { success: true, fileName: string, overwrittenDisk: boolean }
     */
    async saveDocument(options = {}) {
      if (!this.originalPdfData && !this.originalFile) {
        throw new Error('No active document to save.');
      }

      this.isSaving = true;
      this.errorMessage = null;

      try {
        let buffer = this.originalPdfData;
        if (!buffer && this.originalFile) {
          buffer = await this.originalFile.arrayBuffer();
        }

        const editsToApply = Array.isArray(options.edits) ? options.edits : this.edits;
        const userRotations = options.userRotations || this.restoredSession?.viewSettings?.userRotations || {};
        const targetFilename = this.fileName || 'document.pdf';
        const fileBlob = this.originalFile || new Blob([buffer.slice(0)], { type: 'application/pdf' });

        let modifiedBytes = null;
        let warningsCount = 0;

        // 1. Primary: Route through backend PDFBox true token-stripping endpoint
        const hasEdits = Array.isArray(editsToApply) && editsToApply.length > 0;
        if (hasEdits) {
          try {
            const redactedBlob = await requestRedactedPdf(fileBlob, editsToApply, targetFilename);
            if (redactedBlob && redactedBlob.size > 0) {
              const arrayBuf = await redactedBlob.arrayBuffer();
              modifiedBytes = new Uint8Array(arrayBuf);
              warningsCount = redactedBlob.warningsCount || 0;
            } else {
              throw new Error('Server returned empty response.');
            }
          } catch (backendErr) {
            console.error('[documentStore] Backend /api/export/redact failed:', backendErr);
            this.errorMessage = 'Server unavailable. Export blocked so hidden text is not leaked.';
            throw new Error(this.errorMessage);
          }
        } else {
          // Documents without text edits: client-side view modification (e.g. userRotations)
          modifiedBytes = await exportModifiedPdf(buffer, editsToApply, { userRotations });
        }

        // 2. Overwrite file on disk using File System Access API if available
        let overwrittenDisk = false;
        let activeHandle = this.fileHandle;

        if (activeHandle && typeof activeHandle.createWritable === 'function') {
          try {
            const writable = await activeHandle.createWritable();
            await writable.write(modifiedBytes);
            await writable.close();
            overwrittenDisk = true;
          } catch (handleErr) {
            console.warn('[documentStore] Existing fileHandle.createWritable failed:', handleErr);
            activeHandle = null;
          }
        }

        // If no active fileHandle yet, try showSaveFilePicker
        if (!overwrittenDisk && typeof window !== 'undefined' && typeof window.showSaveFilePicker === 'function') {
          try {
            const handle = await window.showSaveFilePicker({
              suggestedName: this.fileName || 'document.pdf',
              types: [{
                description: 'PDF Document',
                accept: { 'application/pdf': ['.pdf'] }
              }]
            });
            if (handle) {
              const writable = await handle.createWritable();
              await writable.write(modifiedBytes);
              await writable.close();
              this.fileHandle = handle;
              if (handle.name) {
                this.fileName = handle.name;
              }
              overwrittenDisk = true;
            }
          } catch (pickerErr) {
            console.warn('[documentStore] showSaveFilePicker failed or cancelled, using download fallback:', pickerErr);
            triggerBrowserDownload(modifiedBytes, this.fileName || 'document.pdf');
            overwrittenDisk = false;
          }
        } else if (!overwrittenDisk) {
          // Fallback download using exact original filename
          triggerBrowserDownload(modifiedBytes, this.fileName || 'document.pdf');
          overwrittenDisk = true;
        }

        // 3. Update in-memory workspace baseline
        const newBuffer = modifiedBytes.buffer.slice(
          modifiedBytes.byteOffset,
          modifiedBytes.byteOffset + modifiedBytes.byteLength
        );
        const newFileKey = await sessionStorageService.computeFileHash(newBuffer, {
          fileName: this.fileName || 'document.pdf'
        });

        this.isCommittedSave = true;
        this.originalPdfData = newBuffer;
        this.originalFile = new Blob([newBuffer], { type: 'application/pdf' });
        this.fileKey = newFileKey;
        this.edits = [];
        this.restoredSession = null;
        this.isRestoredSession = false;
        this.lastSavedTime = Date.now();

        // 4. Update IndexedDB session with the new baseline PDF bytes
        try {
          const sessionData = {
            fileKey: newFileKey,
            pdfBytes: newBuffer,
            fileName: this.fileName || 'document.pdf',
            documentModel: this.documentModel,
            edits: [],
            blockPatches: [],
            editedBlocksState: {},
            textBlocks: [],
            autoRedactedBlockIds: [],
            viewSettings: {
              zoom: this.zoom || 1.25,
              userRotations: {}
            },
            updatedAt: Date.now()
          };
          await sessionStorageService.saveSession(sessionData);
        } catch (dbErr) {
          console.warn('[documentStore] Failed to update IndexedDB session on save:', dbErr);
        }

        return {
          success: true,
          fileName: this.fileName,
          overwrittenDisk,
          pdfBytes: newBuffer,
          warningsCount
        };
      } catch (err) {
        this.errorMessage = err.message || 'Failed to save PDF document.';
        throw err;
      } finally {
        this.isSaving = false;
      }
    },

    /**
     * Resets document state back to empty.
     */
    resetDocument() {
      this.purgeDocumentState();
    },

    /**
     * Parses TipTap document AST updates into DocumentModel state while strictly preserving
     * original bounding box coordinate metadata (x, y, width, height, bbox, originalBbox),
     * and sets dirty/edited flags appropriately so save and export functions reflect changes.
     *
     * @param {Object} tipTapJson - TipTap AST root document node { type: "doc", content: [...] }
     * @returns {Object|null} Reconstructed DocumentModel
     */
    updateFromTipTap(tipTapJson) {
      if (!tipTapJson) return null;

      // 1. Index existing blocks by ID and by pageNumber_index fallback
      const existingBlocksById = new Map();
      const existingBlocksByIndex = new Map();

      if (this.documentModel?.pages) {
        for (const page of this.documentModel.pages) {
          const pageNum = page.pageNumber || 1;
          const blocks = Array.isArray(page.blocks) ? page.blocks : [];
          blocks.forEach((blk, idx) => {
            if (blk.id) {
              existingBlocksById.set(blk.id, { block: blk, pageNumber: pageNum, index: idx });
            }
            existingBlocksByIndex.set(`${pageNum}_${idx}`, { block: blk, pageNumber: pageNum, index: idx });
          });
        }
      }

      // Map raw TipTap nodes by blockId and pageNumber_index for attribute & mark inspection
      const tipTapNodeMap = new Map();
      if (Array.isArray(tipTapJson.content)) {
        let currPg = 1;
        let blkIdx = 0;
        for (const node of tipTapJson.content) {
          if (!node) continue;
          if (node.type === 'pageBreak') {
            currPg = parseInt(node.attrs?.pageNumber, 10) || (currPg + 1);
            blkIdx = 0;
            continue;
          }
          if (node.attrs?.pageNumber) {
            const p = parseInt(node.attrs.pageNumber, 10);
            if (p && p !== currPg) {
              currPg = p;
              blkIdx = 0;
            }
          }
          if (node.attrs?.blockId) {
            tipTapNodeMap.set(node.attrs.blockId, node);
          }
          tipTapNodeMap.set(`${currPg}_${blkIdx}`, node);
          blkIdx++;
        }
      }

      // 2. Parse TipTap AST back into DocumentModel format
      const parsedModel = convertTipTapToDocumentModel(tipTapJson, this.documentModel);
      if (!parsedModel || !Array.isArray(parsedModel.pages)) {
        return null;
      }

      let hasAnyChanges = false;
      const seenBlockIds = new Set();

      // 3. Match blocks against existing blocks to preserve coordinates and detect edits
      for (const page of parsedModel.pages) {
        const pageNum = page.pageNumber || 1;
        const pageBlocks = Array.isArray(page.blocks) ? page.blocks : [];

        for (let idx = 0; idx < pageBlocks.length; idx++) {
          const block = pageBlocks[idx];
          if (!block) continue;

          // Attempt matching by blockId first, then by (pageNumber, index)
          let match = block.id ? existingBlocksById.get(block.id) : null;
          if (!match) {
            match = existingBlocksByIndex.get(`${pageNum}_${idx}`);
            if (match && match.block) {
              // Re-assign original block ID since this block matched by index fallback
              block.id = match.block.id;
            }
          }

          const existingBlock = match ? match.block : null;
          if (existingBlock) {
            block.id = existingBlock.id;
            seenBlockIds.add(block.id);

            // Coordinate metadata preservation:
            // Check if parsed block has non-zero bbox; if not, restore from existingBlock
            const hasParsedBbox = Array.isArray(block.bbox) && block.bbox.length === 4 &&
              (block.bbox[0] !== 0 || block.bbox[1] !== 0 || block.bbox[2] !== 0 || block.bbox[3] !== 0);

            if (!hasParsedBbox && existingBlock.bbox) {
              block.bbox = Array.isArray(existingBlock.bbox) ? [...existingBlock.bbox] : existingBlock.bbox;
            }

            // Preserve originalBbox
            block.originalBbox = existingBlock.originalBbox || (existingBlock.bbox ? [...existingBlock.bbox] : block.bbox);

            // Preserve explicit coordinate properties: x, y, width, height
            if (existingBlock.x !== undefined) block.x = existingBlock.x;
            else if (Array.isArray(block.bbox)) block.x = block.bbox[0];

            if (existingBlock.y !== undefined) block.y = existingBlock.y;
            else if (Array.isArray(block.bbox)) block.y = block.bbox[1];

            if (existingBlock.width !== undefined) block.width = existingBlock.width;
            else if (Array.isArray(block.bbox)) block.width = block.bbox[2];

            if (existingBlock.height !== undefined) block.height = existingBlock.height;
            else if (Array.isArray(block.bbox)) block.height = block.bbox[3];

            // Preserve original coordinate references
            if (existingBlock.originalX !== undefined) block.originalX = existingBlock.originalX;
            if (existingBlock.originalY !== undefined) block.originalY = existingBlock.originalY;
            if (existingBlock.originalWidth !== undefined) block.originalWidth = existingBlock.originalWidth;
            if (existingBlock.originalHeight !== undefined) block.originalHeight = existingBlock.originalHeight;

            if (existingBlock.originalPdfX !== undefined) block.originalPdfX = existingBlock.originalPdfX;
            if (existingBlock.originalPdfY !== undefined) block.originalPdfY = existingBlock.originalPdfY;
            if (existingBlock.originalPdfWidth !== undefined) block.originalPdfWidth = existingBlock.originalPdfWidth;
            if (existingBlock.originalPdfHeight !== undefined) block.originalPdfHeight = existingBlock.originalPdfHeight;

            if (existingBlock.customX !== undefined) block.customX = existingBlock.customX;
            if (existingBlock.customY !== undefined) block.customY = existingBlock.customY;
            if (existingBlock.customWidth !== undefined) block.customWidth = existingBlock.customWidth;
            if (existingBlock.customFontSize !== undefined) block.customFontSize = existingBlock.customFontSize;
            if (existingBlock.customColor !== undefined) block.customColor = existingBlock.customColor;

            if (existingBlock.lines && !block.lines) block.lines = existingBlock.lines;

            // Preserve baseline originalText
            block.originalText = existingBlock.originalText !== undefined
              ? existingBlock.originalText
              : extractBlockText(existingBlock);
          } else {
            // Newly created block in TipTap
            if (block.id) {
              seenBlockIds.add(block.id);
            }
            if (Array.isArray(block.bbox)) {
              if (block.x === undefined) block.x = block.bbox[0];
              if (block.y === undefined) block.y = block.bbox[1];
              if (block.width === undefined) block.width = block.bbox[2];
              if (block.height === undefined) block.height = block.bbox[3];
            }
            block.originalBbox = block.bbox ? [...block.bbox] : [0, 0, 0, 0];
            block.originalText = '';
          }

          // Ensure bbox is always a 4-element array and sync with x, y, width, height
          if (!Array.isArray(block.bbox) || block.bbox.length !== 4) {
            block.bbox = [block.x || 0, block.y || 0, block.width || 0, block.height || 0];
          }
          if (block.x === undefined) block.x = block.bbox[0];
          if (block.y === undefined) block.y = block.bbox[1];
          if (block.width === undefined) block.width = block.bbox[2];
          if (block.height === undefined) block.height = block.bbox[3];

          // Text & style diff detection
          const newText = extractBlockText(block);
          block.text = newText;
          const origText = block.originalText !== undefined ? block.originalText : '';

          const defaultStyle = existingBlock ? extractBlockStyle(existingBlock) : {};
          const rawTipTapNode = (block.id ? tipTapNodeMap.get(block.id) : null) || tipTapNodeMap.get(`${pageNum}_${idx}`);
          const hasMarks = nodeHasMarks(rawTipTapNode);

          let blockStyle;
          let hasStyleDiff = false;
          if (!hasMarks && existingBlock) {
            blockStyle = { ...defaultStyle };
            hasStyleDiff = false;
          } else {
            blockStyle = extractBlockStyle(block, defaultStyle);
            hasStyleDiff = Boolean(
              (blockStyle.fontSize && defaultStyle.fontSize && Math.abs(blockStyle.fontSize - defaultStyle.fontSize) > 0.1) ||
              (blockStyle.fontFamily && defaultStyle.fontFamily && blockStyle.fontFamily !== defaultStyle.fontFamily) ||
              (blockStyle.bold !== undefined && defaultStyle.bold !== undefined && blockStyle.bold !== defaultStyle.bold) ||
              (blockStyle.italic !== undefined && defaultStyle.italic !== undefined && blockStyle.italic !== defaultStyle.italic) ||
              (blockStyle.color && defaultStyle.color && blockStyle.color.toLowerCase() !== defaultStyle.color.toLowerCase())
            );
          }
          const isModified = hasTextDiff || hasStyleDiff;

          if (isModified) {
            block.isEdited = true;
            block.isDirty = true;
            hasAnyChanges = true;

            // 1. Update modular block patch
            this.setPatch(pageNum, block.id, {
              originalText: origText,
              newText: newText,
              fontSize: blockStyle.fontSize,
              fontFamily: blockStyle.fontFamily,
              bold: blockStyle.bold,
              italic: blockStyle.italic,
              color: blockStyle.color,
              customFontSize: blockStyle.fontSize,
              customColor: blockStyle.color,
              isEdited: true,
              isDirty: true
            });

            // 2. Record edit for redaction / export
            const effBbox = Array.isArray(block.bbox) && block.bbox.length === 4
              ? block.bbox
              : [block.x || 0, block.y || 0, block.width || 0, block.height || 0];

            this.recordEdit({
              id: `redact-block-${block.id}`,
              lineId: block.id,
              pageNumber: pageNum,
              bbox: effBbox,
              originalBbox: block.originalBbox || effBbox,
              originalText: origText,
              newText: newText,
              style: {
                fontSize: blockStyle.fontSize || 12,
                fontFamily: blockStyle.fontFamily || 'Helvetica',
                bold: Boolean(blockStyle.bold),
                italic: Boolean(blockStyle.italic),
                color: blockStyle.color || '#000000'
              },
              isStyleModified: Boolean(hasStyleDiff),
              transparentBackground: false
            });
          } else {
            block.isEdited = false;
            block.isDirty = false;
            this.deletePatch(pageNum, block.id);
            this.removeEdit(`redact-block-${block.id}`);
          }
        }
      }

      // 4. Handle any existing blocks that were deleted in TipTap
      for (const [id, info] of existingBlocksById.entries()) {
        if (!seenBlockIds.has(id)) {
          const origBlock = info.block;
          const origText = origBlock.originalText !== undefined ? origBlock.originalText : extractBlockText(origBlock);
          if (origText && origText.trim().length > 0) {
            hasAnyChanges = true;
            const effBbox = Array.isArray(origBlock.bbox) && origBlock.bbox.length === 4
              ? origBlock.bbox
              : [origBlock.x || 0, origBlock.y || 0, origBlock.width || 0, origBlock.height || 0];

            this.recordEdit({
              id: `redact-block-${id}`,
              lineId: id,
              pageNumber: info.pageNumber,
              bbox: effBbox,
              originalBbox: origBlock.originalBbox || effBbox,
              originalText: origText,
              newText: '',
              style: extractBlockStyle(origBlock),
              isStyleModified: false,
              transparentBackground: false
            });

            this.setPatch(info.pageNumber, id, {
              originalText: origText,
              newText: '',
              isEdited: true,
              isDirty: true
            });
          }
        }
      }

      if (hasAnyChanges) {
        this.isCommittedSave = false;
      }

      // 5. Carry over top-level document model properties
      if (this.documentModel) {
        if (this.documentModel.pageCount && !parsedModel.pageCount) {
          parsedModel.pageCount = parsedModel.pages.length;
        }
        if (this.documentModel.fileName && !parsedModel.fileName) {
          parsedModel.fileName = this.documentModel.fileName;
        }
      }

      this.documentModel = parsedModel;
      return parsedModel;
    }
  }
});

