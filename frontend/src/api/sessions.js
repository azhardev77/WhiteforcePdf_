import api from './http';

/**
 * Fetches available redaction categories from GET /app/redaction-categories.
 *
 * @returns {Promise<Array<{ id: string, label: string, default: boolean }>>}
 */
export async function fetchRedactionCategories() {
  const response = await api.get('/app/redaction-categories');
  return response.data;
}

/**
 * Creates an app session via POST /app/sessions.
 *
 * @param {File|Blob} file - PDF file to upload
 * @param {string} mode - Processing mode (RESUME, TENDER, EDIT, MARK)
 * @param {Array<string>|null} [redactCategories=null] - Optional list of category IDs to redact
 * @returns {Promise<{ id: string }>}
 */
export async function createAppSession(file, mode = 'EDIT', redactCategories = null) {
  if (!file) {
    throw new Error('A PDF file is required to create a session.');
  }

  const formData = new FormData();
  formData.append('file', file);
  formData.append('mode', mode);
  if (redactCategories !== null && Array.isArray(redactCategories)) {
    if (redactCategories.length === 0) {
      formData.append('redact', '');
    } else {
      for (const cat of redactCategories) {
        formData.append('redact', cat);
      }
    }
  }

  const response = await api.post('/app/sessions', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  });

  if (!response?.data || !response.data.id) {
    throw new Error(response?.data?.message || 'Server failed to return a valid session ID.');
  }

  return response.data;
}

/**
 * Fetches the session PDF as an ArrayBuffer from GET /app/sessions/{id}/pdf.
 *
 * @param {string} sessionId - Session ID
 * @returns {Promise<{ arrayBuffer: ArrayBuffer, mode: string|null, source: string|null }>}
 */
export async function fetchAppSessionPdf(sessionId) {
  const response = await api.get(`/app/sessions/${encodeURIComponent(sessionId)}/pdf`, {
    responseType: 'arraybuffer',
    headers: {
      'Accept': 'application/pdf, application/json, */*'
    }
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
  const response = await api.get(`/app/sessions/${encodeURIComponent(sessionId)}`);
  return response.data;
}
