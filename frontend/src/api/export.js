import api from './http';

/**
 * Exports DocumentModel as a PDF blob and triggers automatic browser download.
 *
 * @param {Object} documentModel - Current DocumentModel state
 * @param {string} [downloadFilename] - Optional override for file download name
 * @returns {Promise<Blob>} The downloaded PDF blob
 */
export async function exportPdf(documentModel, downloadFilename) {
  const response = await api.post('/api/export/pdf', documentModel, {
    responseType: 'blob',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/pdf'
    }
  });

  const blob = response.data;
  const fileName = downloadFilename || documentModel?.name || 'document_exported.pdf';
  const resolvedFileName = fileName.toLowerCase().endsWith('.pdf') ? fileName : `${fileName}.pdf`;

  // Trigger browser download via temporary link
  const blobUrl = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = blobUrl;
  link.download = resolvedFileName;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(blobUrl);

  return blob;
}

/**
 * Sends original PDF and text redaction edits to PDFBox in-place redaction endpoint and triggers download.
 *
 * @param {Blob|File} fileOrBlob - Original PDF binary data
 * @param {Array<Object>} edits - List of TextRedactionEdit objects
 * @param {string} [downloadFilename] - Download filename
 * @returns {Promise<Blob>} The modified PDF blob
 */
export async function exportRedactedPdf(fileOrBlob, edits, downloadFilename) {
  const formData = new FormData();
  const resolvedFileName = downloadFilename || 'edited_document.pdf';
  formData.append('file', fileOrBlob, resolvedFileName);
  formData.append('edits', JSON.stringify(edits || []));

  const response = await api.post('/api/export/redact', formData, {
    responseType: 'blob',
    headers: {
      'Accept': 'application/pdf'
    }
  });

  const blob = response.data;
  const finalFilename = resolvedFileName.toLowerCase().endsWith('.pdf') ? resolvedFileName : `${resolvedFileName}.pdf`;

  const blobUrl = window.URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = blobUrl;
  link.download = finalFilename;
  document.body.appendChild(link);
  link.click();
  document.body.removeChild(link);
  window.URL.revokeObjectURL(blobUrl);

  return blob;
}

/**
 * Exports DocumentModel as an HTML string for inspection or preview.
 *
 * @param {Object} documentModel - Current DocumentModel state
 * @returns {Promise<string>} Rendered HTML string
 */
export async function exportHtml(documentModel) {
  const response = await api.post('/api/export/html', documentModel, {
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'text/html'
    }
  });
  return response.data;
}
