# PDF Editor API Documentation

## Headless Process Endpoint

### `POST /api/v1/process`
Headless in-memory PDF processing endpoint. Requires API Key authentication via `X-API-KEY`.
No data or files are persisted to disk or cloud storage.

#### Authentication
- Header `X-API-KEY`: Required API Key. Requests with missing or invalid keys return `401 Unauthorized`.

#### Request
- **Content-Type**: `multipart/form-data`
- **Parameters**:
  - `file`: PDF file to process (`MultipartFile`, max 10MB, unencrypted).
  - `ops`: JSON array of operations executed sequentially in the specified order. Supported operations:
    - `{"type":"REDACT_PII"}`: Automatically identifies and redacts all PII (emails, phone numbers, mailto/tel annotations, and metadata) using the RESUME redaction engine.
    - `{"type":"REPLACE_TEXT","find":"...","replace":"..."}`: Finds all occurrences of the `find` string, whites out their bounding boxes, strips underlying content stream tokens, and renders `replace` with matching font family, size, weight, and color.

#### Response
- **Status**: `200 OK`
- **Content-Type**: `application/pdf`
- **Headers**:
  - `Content-Disposition`: `attachment; filename="<filename>"`
  - `X-Process-Matches`: Total number of text matches found and replaced across `REPLACE_TEXT` operations.
  - `X-Export-Warnings`: Count of unrenderable characters replaced with fallback glyphs (if any).

#### Error Responses
- `400 Bad Request`: Invalid JSON in `ops`, unknown operation type, empty `find` string, or non-PDF file.
- `401 Unauthorized`: Missing or invalid `X-API-KEY`.
- `413 Payload Too Large`: Uploaded file exceeds maximum allowed size (10MB).
- `422 Unprocessable Entity`: Password-protected PDF or scanned/image-only PDF with no extractable text.
- `429 Too Many Requests`: Client IP exceeded rate limits.

---

### Examples using cURL

#### 1. Redact PII (Emails and Phone Numbers)
```bash
curl -X POST "http://localhost:8080/api/v1/process" \
  -H "X-API-KEY: your_api_key_here" \
  -F "file=@resume.pdf" \
  -F 'ops=[{"type":"REDACT_PII"}]' \
  --output "redacted_resume.pdf"
```

#### 2. Find and Replace Text
```bash
curl -X POST "http://localhost:8080/api/v1/process" \
  -H "X-API-KEY: your_api_key_here" \
  -F "file=@contract.pdf" \
  -F 'ops=[{"type":"REPLACE_TEXT","find":"Confidential","replace":"Public"}]' \
  --output "updated_contract.pdf"
```

#### 3. Chained Operations (Redact PII + Replace Text)
```bash
curl -X POST "http://localhost:8080/api/v1/process" \
  -H "X-API-KEY: your_api_key_here" \
  -F "file=@document.pdf" \
  -F 'ops=[{"type":"REDACT_PII"},{"type":"REPLACE_TEXT","find":"Software Developer","replace":"Lead Architect"}]' \
  --output "processed_document.pdf"
```

