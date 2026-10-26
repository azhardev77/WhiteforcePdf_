import axios from 'axios';

const BASE_URL = import.meta.env.VITE_API_BASE_URL || 'http://localhost:8080';

/**
 * Creates an app session via POST /app/sessions (no API key required).
 *
 * @param {File|Blob} file - PDF file to upload
 * @param {string} mode - Processing mode (RESUME, TENDER, EDIT, MARK)
 * @returns {Promise<{ id: string }>}
 */
export async function createAppSession(file, mode = 'EDIT') {
  const formData = new FormData();
  formData.append('file', file);
  formData.append('mode', mode);

  const response = await axios.post(`${BASE_URL}/app/sessions`, formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  });

  return response.data;
}

/**
 * Fetches the session PDF as an ArrayBuffer from GET /app/sessions/{id}/pdf.
 *
 * @param {string} sessionId - Session ID
 * @returns {Promise<{ arrayBuffer: ArrayBuffer, mode: string|null, source: string|null }>}
 */
export async function fetchAppSessionPdf(sessionId) {
  const response = await axios.get(`${BASE_URL}/app/sessions/${encodeURIComponent(sessionId)}/pdf`, {
    responseType: 'arraybuffer'
  });

  const headers = response.headers || {};
  const mode = headers['x-session-mode'] || headers['X-Session-Mode'] || null;
  const source = headers['x-session-source'] || headers['X-Session-Source'] || null;

  return {
    arrayBuffer: response.data,
    mode,
    source
  };
}

/**
 * Fetches session metadata from GET /app/sessions/{id}.
 *
 * @param {string} sessionId - Session ID
 * @returns {Promise<{ id: string, mode: string, source: string, status: string, expiresAt: string }>}
 */
export async function fetchAppSessionInfo(sessionId) {
  const response = await axios.get(`${BASE_URL}/app/sessions/${encodeURIComponent(sessionId)}`);
  return response.data;
}
