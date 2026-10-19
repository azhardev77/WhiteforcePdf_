/**
 * IndexedDB Session Manager for PDF Visual Studio
 *
 * Persists active PDF binary (ArrayBuffer), document metadata, extracted blocks,
 * inline/redaction edits, and view settings across page refreshes (F5/Reload).
 * Avoids localStorage 5MB quota errors by utilizing native browser IndexedDB.
 */

const DB_NAME = 'PdfVisualStudioDB';
const DB_VERSION = 1;
const STORE_NAME = 'active_session';
const SESSION_KEY = 'current_workspace';

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
 * Saves the current workspace session snapshot to IndexedDB.
 *
 * @param {Object} sessionData - Session data to persist
 * @param {ArrayBuffer|Uint8Array} sessionData.pdfBytes - Original PDF binary
 * @param {string} sessionData.fileName - Document file name
 * @param {Object} [sessionData.documentModel] - Document layout model
 * @param {Array} [sessionData.edits] - Active redaction/text edits payload
 * @param {Object} [sessionData.editedBlocksState] - Internal block edit states
 * @param {Array} [sessionData.textBlocks] - Extracted paragraph/text blocks
 * @param {Array<string>} [sessionData.autoRedactedBlockIds] - Auto-redacted block IDs
 * @param {Object} [sessionData.viewSettings] - Zoom, rotation, flip settings
 * @returns {Promise<boolean>}
 */
export async function saveSession(sessionData) {
  if (!sessionData) return false;

  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);

      // Safe clone of binary buffer
      let pdfBytes = sessionData.pdfBytes;
      if (pdfBytes instanceof Uint8Array) {
        pdfBytes = pdfBytes.buffer.slice(pdfBytes.byteOffset, pdfBytes.byteOffset + pdfBytes.byteLength);
      } else if (pdfBytes && typeof pdfBytes.slice === 'function') {
        pdfBytes = pdfBytes.slice(0);
      }

      const record = {
        pdfBytes,
        fileName: String(sessionData.fileName || 'document.pdf'),
        documentModel: safeClone(sessionData.documentModel, null),
        edits: safeClone(sessionData.edits, []),
        editedBlocksState: safeClone(sessionData.editedBlocksState, {}),
        textBlocks: safeClone(sessionData.textBlocks, []),
        autoRedactedBlockIds: safeClone(sessionData.autoRedactedBlockIds, []),
        viewSettings: safeClone(sessionData.viewSettings, { zoom: 1.25, userRotations: {} }),
        updatedAt: Date.now()
      };

      const putReq = store.put(record, SESSION_KEY);

      putReq.onsuccess = () => resolve(true);
      putReq.onerror = () => reject(putReq.error || new Error('Failed to put session into IndexedDB.'));

      tx.oncomplete = () => db.close();
      tx.onerror = () => db.close();
    });
  } catch (err) {
    console.warn('[IndexedDB] Failed to save session:', err);
    return false;
  }
}

/**
 * Loads the active workspace session from IndexedDB.
 *
 * @returns {Promise<Object|null>} Restored session object or null
 */
export async function loadSession() {
  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readonly');
      const store = tx.objectStore(STORE_NAME);
      const getReq = store.get(SESSION_KEY);

      getReq.onsuccess = () => {
        resolve(getReq.result || null);
      };

      getReq.onerror = () => {
        reject(getReq.error || new Error('Failed to get session from IndexedDB.'));
      };

      tx.oncomplete = () => db.close();
      tx.onerror = () => db.close();
    });
  } catch (err) {
    console.warn('[IndexedDB] Failed to load session:', err);
    return null;
  }
}

/**
 * Clears the active workspace session from IndexedDB.
 *
 * @returns {Promise<boolean>}
 */
export async function clearSession() {
  try {
    const db = await openDatabase();
    return new Promise((resolve, reject) => {
      const tx = db.transaction(STORE_NAME, 'readwrite');
      const store = tx.objectStore(STORE_NAME);
      const delReq = store.delete(SESSION_KEY);

      delReq.onsuccess = () => resolve(true);
      delReq.onerror = () => reject(delReq.error || new Error('Failed to delete session from IndexedDB.'));

      tx.oncomplete = () => db.close();
      tx.onerror = () => db.close();
    });
  } catch (err) {
    console.warn('[IndexedDB] Failed to clear session:', err);
    return false;
  }
}

/**
 * Checks whether an active session with valid PDF bytes exists.
 *
 * @returns {Promise<boolean>}
 */
export async function hasSession() {
  try {
    const session = await loadSession();
    return Boolean(session && session.pdfBytes && session.pdfBytes.byteLength > 0);
  } catch {
    return false;
  }
}

export default {
  saveSession,
  loadSession,
  clearSession,
  hasSession
};
