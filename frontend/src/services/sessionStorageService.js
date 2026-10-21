/**
 * IndexedDB Session Manager for PDF Visual Studio
 *
 * Persists active PDF binary (ArrayBuffer), document metadata, extracted blocks,
 * inline/redaction edits, and view settings scoped strictly by unique file hash
 * (SHA-256 of file buffer or fileName + fileSize + lastModified).
 * Prevents state leakage across different documents while supporting clean reloads.
 */

const DB_NAME = 'PdfVisualStudioDB';
const DB_VERSION = 1;
const STORE_NAME = 'active_session';
const SESSION_KEY = 'current_workspace';
const ACTIVE_DOC_POINTER = '__active_doc_key';

/**
 * Computes a unique deterministic key for a document based on SHA-256 or metadata.
 *
 * @param {ArrayBuffer|Uint8Array} buffer - PDF binary data
 * @param {Object} [meta] - File metadata
 * @param {string} [meta.fileName]
 * @param {number} [meta.fileSize]
 * @param {number} [meta.lastModified]
 * @returns {Promise<string>} Unique document key
 */
export async function computeFileHash(buffer, meta = {}) {
  if (buffer) {
    try {
      let rawBuf = buffer;
      if (rawBuf instanceof Uint8Array) {
        rawBuf = rawBuf.buffer.slice(rawBuf.byteOffset, rawBuf.byteOffset + rawBuf.byteLength);
      }
      const subtleCrypto = (typeof window !== 'undefined' && window.crypto?.subtle) ||
                           (typeof globalThis !== 'undefined' && globalThis.crypto?.subtle);
      if (subtleCrypto && typeof subtleCrypto.digest === 'function') {
        const hashBuf = await subtleCrypto.digest('SHA-256', rawBuf);
        const hashArr = Array.from(new Uint8Array(hashBuf));
        const hashHex = hashArr.map(b => b.toString(16).padStart(2, '0')).join('');
        return `doc_${hashHex}`;
      }
    } catch (e) {
      console.warn('[Session] Crypto subtle digest failed, using metadata fallback:', e);
    }
  }

  const name = meta.fileName || meta.name || 'document.pdf';
  const size = meta.fileSize || meta.size || (buffer ? buffer.byteLength : 0);
  const lastMod = meta.lastModified || 0;
  return `doc_${name}_${size}_${lastMod}`.replace(/[^a-zA-Z0-9_-]/g, '_');
}

/**
 * Opens or initializes the IndexedDB database.
 * @returns {Promise<IDBDatabase>}
 */
function openDatabase() {
  return new Promise((resolve, reject) => {
    if (typeof window === 'undefined' || !window.indexedDB) {
      return reject(new Error('IndexedDB is not supported in this environment.'));
    }

    const request = window.indexedDB.open(DB_NAME, DB_VERSION);

    request.onupgradeneeded = (event) => {
      const db = event.target.result;
      if (!db.objectStoreNames.contains(STORE_NAME)) {
        db.createObjectStore(STORE_NAME);
      }
    };

    request.onsuccess = () => {
      resolve(request.result);
    };

    request.onerror = () => {
      reject(request.error || new Error('Failed to open IndexedDB database.'));
    };
  });
}

function safeClone(obj, fallback = null) {
  if (!obj) return fallback;
  try {
    return JSON.parse(JSON.stringify(obj));
  } catch (e) {
    return fallback;
  }
}

/**
 * Saves the current workspace session snapshot to IndexedDB scoped strictly by unique file key.
 *
 * @param {Object} sessionData - Session data to persist
 * @param {ArrayBuffer|Uint8Array} sessionData.pdfBytes - Original PDF binary
 * @param {string} sessionData.fileName - Document file name
 * @param {string} [sessionData.fileKey] - Precomputed document key/hash
 * @param {Object} [sessionData.documentModel] - Document layout model
 * @param {Array} [sessionData.edits] - Active redaction/text edits payload
 * @param {Object} [sessionData.editedBlocksState] - Internal block edit states
 * @param {Array} [sessionData.textBlocks] - Extracted paragraph/text blocks
 * @param {Object} [sessionData.pageBlocksMap] - Page blocks map
 * @param {Array<string>} [sessionData.autoRedactedBlockIds] - Auto-redacted block IDs
 * @param {Object} [sessionData.viewSettings] - Zoom, rotation, flip settings
 * @returns {Promise<boolean>}
 */
export async function saveSession(sessionData) {
  if (!sessionData) return false;

  try {
    const db = await openDatabase();
    return new Promise(async (resolve, reject) => {
      try {
        // Safe clone of binary buffer
        let pdfBytes = sessionData.pdfBytes;
        if (pdfBytes instanceof Uint8Array) {
          pdfBytes = pdfBytes.buffer.slice(pdfBytes.byteOffset, pdfBytes.byteOffset + pdfBytes.byteLength);
        } else if (pdfBytes && typeof pdfBytes.slice === 'function') {
          pdfBytes = pdfBytes.slice(0);
        }

        const fileKey = sessionData.fileKey || await computeFileHash(pdfBytes, {
          fileName: sessionData.fileName,
          fileSize: pdfBytes?.byteLength
        });

        const tx = db.transaction(STORE_NAME, 'readwrite');
        const store = tx.objectStore(STORE_NAME);

        const record = {
          fileKey,
          pdfBytes,
          fileName: String(sessionData.fileName || 'document.pdf'),
          documentModel: safeClone(sessionData.documentModel, null),
          edits: safeClone(sessionData.edits, []),
          editedBlocksState: safeClone(sessionData.editedBlocksState, {}),
          textBlocks: safeClone(sessionData.textBlocks, []),
          pageBlocksMap: safeClone(sessionData.pageBlocksMap, null),
          autoRedactedBlockIds: safeClone(sessionData.autoRedactedBlockIds, []),
          viewSettings: safeClone(sessionData.viewSettings, { zoom: 1.25, userRotations: {} }),
          updatedAt: Date.now()
        };

        // 1. Save strictly scoped to file key
        store.put(record, fileKey);
        // 2. Track current active document key
        store.put(fileKey, ACTIVE_DOC_POINTER);

        tx.oncomplete = () => {
          db.close();
          resolve(true);
        };
        tx.onerror = () => {
          db.close();
          reject(tx.error || new Error('Failed to put session into IndexedDB.'));
        };
      } catch (innerErr) {
        db.close();
        reject(innerErr);
      }
    });
  } catch (err) {
    console.warn('[IndexedDB] Failed to save session:', err);
    return false;
  }
}

/**
 * Loads a workspace session from IndexedDB.
 * If expectedFileKey is provided, returns the session strictly for that document.
 * Otherwise, loads the active document pointed to by ACTIVE_DOC_POINTER.
 *
 * @param {string} [expectedFileKey] - Optional document hash/key
 * @returns {Promise<Object|null>} Restored session object or null
 */
export async function loadSession(expectedFileKey = null) {
  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);

      if (expectedFileKey) {
        const getReq = store.get(expectedFileKey);
        getReq.onsuccess = () => resolve(getReq.result || null);
        getReq.onerror = () => reject(getReq.error || new Error('Failed to get session from IndexedDB.'));
      } else {
        const pointerReq = store.get(ACTIVE_DOC_POINTER);
        pointerReq.onsuccess = () => {
          const activeKey = pointerReq.result;
          if (!activeKey) {
            // Check legacy key for backward compatibility
            const legacyReq = store.get(SESSION_KEY);
            legacyReq.onsuccess = () => resolve(legacyReq.result || null);
            legacyReq.onerror = () => resolve(null);
            return;
          }
          const getReq = store.get(activeKey);
          getReq.onsuccess = () => resolve(getReq.result || null);
          getReq.onerror = () => reject(getReq.error || new Error('Failed to get active session.'));
        };
        pointerReq.onerror = () => reject(pointerReq.error || new Error('Failed to read active session pointer.'));
      }

      tx.oncomplete = () => db.close();
      tx.onerror = () => db.close();
    });
  } catch (err) {
    console.warn('[IndexedDB] Failed to load session:', err);
    return null;
  }
}

/**
 * Clears the draft cache upon loading a new document or resetting workspace.
 * Purges the active document pointer and any draft record so previous edits
 * can never leak into a newly selected document.
 *
 * @returns {Promise<boolean>}
 */
export async function clearDraftCache() {
  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);

      const pointerReq = store.get(ACTIVE_DOC_POINTER);
      pointerReq.onsuccess = () => {
        const activeKey = pointerReq.result;
        if (activeKey) {
          store.delete(activeKey);
        }
        store.delete(ACTIVE_DOC_POINTER);
        store.delete(SESSION_KEY);
      };

      tx.oncomplete = () => {
        db.close();
        resolve(true);
      };
      tx.onerror = () => {
        db.close();
        reject(tx.error || new Error('Failed to clear draft cache.'));
      };
    });
  } catch (err) {
    console.warn('[IndexedDB] Failed to clear draft cache:', err);
    return false;
  }
}

/**
 * Clears a specific document session or the active workspace session from IndexedDB.
 *
 * @param {string} [fileKey] - Specific file key to clear
 * @returns {Promise<boolean>}
 */
export async function clearSession(fileKey = null) {
  if (!fileKey) {
    return clearDraftCache();
  }
  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);

      store.delete(fileKey);
      const pointerReq = store.get(ACTIVE_DOC_POINTER);
      pointerReq.onsuccess = () => {
        if (pointerReq.result === fileKey) {
          store.delete(ACTIVE_DOC_POINTER);
        }
      };

      tx.oncomplete = () => {
        db.close();
        resolve(true);
      };
      tx.onerror = () => {
        db.close();
        reject(tx.error || new Error('Failed to delete session from IndexedDB.'));
      };
    });
  } catch (err) {
    console.warn('[IndexedDB] Failed to clear session:', err);
    return false;
  }
}

/**
 * Checks whether an active session with valid PDF bytes exists.
 *
 * @param {string} [fileKey]
 * @returns {Promise<boolean>}
 */
export async function hasSession(fileKey = null) {
  try {
    const session = await loadSession(fileKey);
    return Boolean(session && session.pdfBytes && session.pdfBytes.byteLength > 0);
  } catch {
    return false;
  }
}

export default {
  computeFileHash,
  saveSession,
  loadSession,
  clearDraftCache,
  clearSession,
  hasSession
};
