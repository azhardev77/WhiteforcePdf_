import { defineStore } from 'pinia';
import { extractPdf, extractSample, fetchRawSamplePdf } from '@/api/documents';
import { exportPdf, exportRedactedPdf, exportHtml } from '@/api/export';

export const useDocumentStore = defineStore('document', {
  state: () => ({
    documentModel: null,
    originalPdfData: null, // ArrayBuffer or Uint8Array
    originalFile: null,    // File or Blob
    edits: [],             // Array of TextRedactionEdit
    activeBlockId: null,
    zoom: 1.25,
    isExtracting: false,
    isExporting: false,
    errorMessage: null,
    fileName: '',
    restoredSession: null,
    isRestoredSession: false
  }),

  getters: {
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
    editsCount: (state) => {
      return state.edits.length;
    }
  },

  actions: {
    /**
     * Uploads a user PDF, stores raw binary in state, and extracts semantic layout.
     *
     * @param {File} file - PDF file to upload
     * @returns {Promise<Object>} Extracted DocumentModel
     */
    async uploadAndExtract(file) {
      if (!file) return null;
      this.isExtracting = true;
      this.errorMessage = null;
      this.fileName = file.name;
      this.edits = [];
      this.activeBlockId = null;

      try {
        const arrayBuffer = await file.arrayBuffer();
        this.originalPdfData = arrayBuffer;
        this.originalFile = file;

        const model = await extractPdf(file);
        if (model && !model.name) {
          model.name = file.name;
        }
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
     * Extracts a sample PDF hosted on the backend and downloads raw binary for canvas.
     *
     * @param {string} sampleFileName - Filename of sample PDF
     * @returns {Promise<Object>} Extracted DocumentModel
     */
    async loadSample(sampleFileName = 'sample.pdf') {
      this.isExtracting = true;
      this.errorMessage = null;
      this.fileName = sampleFileName;
      this.edits = [];
      this.activeBlockId = null;

      try {
        const [model, rawBuffer] = await Promise.all([
          extractSample(sampleFileName),
          fetchRawSamplePdf(sampleFileName)
        ]);

        this.originalPdfData = rawBuffer;
        this.originalFile = new Blob([rawBuffer], { type: 'application/pdf' });
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
     * Exports the current document with in-place redactions applied directly on original PDF.
     *
     * @returns {Promise<Blob>} Exported PDF binary blob
     */
    async downloadPdf() {
      if (!this.originalPdfData && !this.documentModel) {
        const err = new Error('No active document available for export.');
        this.errorMessage = err.message;
        throw err;
      }

      this.isExporting = true;
      this.errorMessage = null;

      try {
        if (this.originalFile || this.originalPdfData) {
          // Always slice buffer to prevent detached ArrayBuffer issues
          const fileBlob = this.originalFile || new Blob([this.originalPdfData.slice(0)], { type: 'application/pdf' });
          const blob = await exportRedactedPdf(fileBlob, this.edits, this.fileName || 'edited_document.pdf');
          return blob;
        } else {
          // Fallback to model-based export if raw PDF is unavailable
          const blob = await exportPdf(this.documentModel, this.fileName || this.documentModel.name);
          return blob;
        }
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
      this.restoredSession = session;
      this.isRestoredSession = true;
    },

    /**
     * Resets document state back to empty.
     */
    resetDocument() {
      this.documentModel = null;
      this.originalPdfData = null;
      this.originalFile = null;
      this.edits = [];
      this.activeBlockId = null;
      this.fileName = '';
      this.errorMessage = null;
      this.isExtracting = false;
      this.isExporting = false;
      this.restoredSession = null;
      this.isRestoredSession = false;
    }
  }
});
