import { defineStore } from 'pinia';
import { extractPdfClient } from '@/services/clientPdfExtractor';
import { exportModifiedPdf, triggerBrowserDownload } from '@/services/pdfLibExportService';
import sessionStorageService from '@/services/sessionStorageService';
import { extractPdf, extractSample, fetchRawSamplePdf } from '@/api/documents';
import { exportPdf, exportRedactedPdf, requestRedactedPdf, exportHtml } from '@/api/export';

export const useDocumentStore = defineStore('document', {
  state: () => ({
    documentModel: null,
    originalPdfData: null, // ArrayBuffer or Uint8Array
    originalFile: null,    // File or Blob
    fileHandle: null,      // FileSystemFileHandle when opened via File System Access API
    fileKey: null,         // Unique document hash/identifier for scoped storage
    edits: [],             // Array of TextRedactionEdit
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
     * Completely purges all document-related states, edit arrays, and session markers.
     */
    purgeDocumentState() {
      this.documentModel = null;
      this.originalPdfData = null;
      this.originalFile = null;
      this.fileHandle = null;
      this.fileKey = null;
      this.edits = [];
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
      this.zoom = 1.25;
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
        try {
          const redactedBlob = await requestRedactedPdf(fileBlob, this.edits, exportName);
          if (redactedBlob && redactedBlob.size > 0) {
            triggerBrowserDownload(redactedBlob, exportName);
            return redactedBlob;
          }
        } catch (backendErr) {
          console.warn('[documentStore] Backend /api/export/redact failed or offline, attempting client fallback:', backendErr);
        }

        // 2. Fallback: Pure client-side in-browser PDF modification using pdf-lib
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

        // 1. Primary: Route through backend PDFBox true token-stripping endpoint
        try {
          const redactedBlob = await requestRedactedPdf(fileBlob, editsToApply, targetFilename);
          if (redactedBlob && redactedBlob.size > 0) {
            const arrayBuf = await redactedBlob.arrayBuffer();
            modifiedBytes = new Uint8Array(arrayBuf);
          }
        } catch (backendErr) {
          console.warn('[documentStore] Backend /api/export/redact failed or offline, falling back to pdf-lib:', backendErr);
        }

        // 2. Fallback: Bake modifications into PDF binary via pdf-lib if backend unavailable
        if (!modifiedBytes) {
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
          pdfBytes: newBuffer
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
    }
  }
});

