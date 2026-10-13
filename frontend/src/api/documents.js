import api from './http';

/**
 * Uploads a PDF file and triggers full layout extraction.
 *
 * @param {File} file - PDF file object
 * @returns {Promise<Object>} DocumentModel JSON
 */
export async function extractPdf(file) {
  const formData = new FormData();
  formData.append('file', file);

  const response = await api.post('/api/documents/extract', formData, {
    headers: {
      'Content-Type': 'multipart/form-data'
    }
  });
  return response.data;
}

/**
 * Extracts a pre-existing sample PDF on the backend.
 *
 * @param {string} filename - Sample filename (e.g. "sample.pdf")
 * @returns {Promise<Object>} DocumentModel JSON
 */
export async function extractSample(filename) {
  const response = await api.post(`/api/documents/extract-sample?filename=${encodeURIComponent(filename)}`);
  return response.data;
}

/**
 * Downloads raw bytes of sample PDF for canvas rendering.
 *
 * @param {string} filename - Sample filename
 * @returns {Promise<ArrayBuffer>}
 */
export async function fetchRawSamplePdf(filename) {
  const response = await api.get(`/api/documents/raw-sample?filename=${encodeURIComponent(filename)}`, {
    responseType: 'arraybuffer'
  });
  return response.data;
}
