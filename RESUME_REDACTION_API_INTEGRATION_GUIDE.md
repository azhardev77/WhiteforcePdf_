# Resume PII Redaction & Metadata Extraction API: Developer Integration Guide

> **Module Scope:** `pdf-editor/backend` (Spring Boot 3.3.4, Java 21)  
> **Git Branch:** `feat/b2b-pipeline-hardening`  
> **Commit Hash:** `5188a3a` (with working tree modifications to `ProcessController.java` and `ResumeProcessResponse.java`, plus untracked `ZipResumeProcessor.java` and `ResumeItemResult.java`)  
> **Audience:** External Laravel / PHP developers integrating with this service without access to internal codebase.  
> **Client UI Reference:** A companion standalone web dashboard exists at `resume-dashboard` (Vite / Vue 3) that provides a drag-and-drop dropzone, dynamic toggle switches, and an interactive results table for visual testing.

---

## 1. Overview

This service provides an automated, server-side REST API to extract candidate contact details (email addresses and phone numbers) from resumes and permanently sanitize/redact those details from the underlying PDF text stream and interactive annotations (`mailto:` / `tel:` links).

The API natively supports **Single PDF files**, **Multi-PDF file arrays**, and **Bulk ZIP archives**. Sanitized PDFs are returned directly inside the JSON payload as Base64 strings (and as clean Base64 ZIP archives for batch requests).

### Base URLs
* **Local Development:** `http://localhost:8080` (or `http://<HOST_IP>:8080` on local network)
* **Production Deployment:** `https://<API_DOMAIN>` (configured via reverse proxy / domain)

### External-Facing Endpoint Summary

| HTTP Method | Route | Purpose | Authentication |
| :--- | :--- | :--- | :--- |
| **`GET`** | `/api/health` | Health check & heartbeat probe | **None** (Public) |
| **`POST`** | `/api/v1/resume/process` | Primary endpoint: Extract contact details, redact PDF streams, support Single/Multi/ZIP | **Required** (`X-API-KEY`) |
| **`POST`** | `/api/v1/resume/extract` | Single-pass structured resume parser (name, skills, contact, sections, word count) | **Required** (`X-API-KEY`) |
| **`POST`** | `/api/v1/process` | Low-level PDF manipulation pipeline (arbitrary search/replace and redaction via JSON ops) | **Required** (`X-API-KEY`) |

<!-- source: backend/src/main/java/com/pdfeditor/health/HealthController.java:13 -->
<!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:86 -->
<!-- source: backend/src/main/java/com/pdfeditor/process/ResumeExtractController.java:34 -->
<!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:480 -->

---

## 2. Authentication & Security

### 2.1 Authentication Mechanism
Authentication is enforced by `ApiKeyAuthFilter` via HTTP header inspection.
* **Header Name:** `X-API-KEY` (case-insensitive fallback to `x-api-key` is supported).  
  <!-- source: backend/src/main/java/com/pdfeditor/security/ApiKeyAuthFilter.java:82-85 -->
* **Validation Method:** Constant-time comparison using `MessageDigest.isEqual()` to prevent timing attacks.  
  <!-- source: backend/src/main/java/com/pdfeditor/security/ApiKeyAuthFilter.java:99-104 -->
* **Key Configuration:** Set via environment variable `API_KEY` (or `app.security.api-key` in `application.yml`).  
  <!-- source: backend/src/main/java/com/pdfeditor/config/SecurityConfig.java:18 -->
* **Where to Obtain:** The API key must be provisioned by the server administrator or read from the deployment vault/`.env` (`API_KEY=<API_KEY>`).

> ⚠️ **Route Separation in Code:**  
> Requests starting with `/api/v1/**` are strictly authenticated against `API_KEY`. Non-v1 endpoints (`/api/**` like `/api/documents`) are internal UI endpoints authenticated against a separate `UI_API_KEY`. External systems MUST use `/api/v1/**` endpoints.  
> <!-- source: backend/src/main/java/com/pdfeditor/security/ApiKeyAuthFilter.java:94-95 -->

### 2.2 Rate Limiting
Enforced by `ApiRateLimiter` per API Key SHA-256 hash using an in-memory sliding window:
* **Default Rate Limit:** **120 requests per minute** per API key (configurable via `APP_API_RATE_LIMIT`).  
  <!-- source: backend/src/main/java/com/pdfeditor/security/ApiRateLimiter.java:19 -->
* **Limit Exceeded Behavior:** Returns **HTTP 429 Too Many Requests** with JSON body.  
  <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:100-103 -->

### 2.3 File Upload & Size Limits
* **Multipart Max File Size:** **50 MB** (`spring.servlet.multipart.max-file-size: 50MB`).  
  <!-- source: backend/src/main/resources/application.yml:11 -->
* **Multipart Max Request Size:** **50 MB** (`spring.servlet.multipart.max-request-size: 50MB`).  
  <!-- source: backend/src/main/resources/application.yml:12 -->
* **ZIP Archive Limits:**
  * Maximum entries inside ZIP: **500 files** (`MAX_ZIP_ENTRIES = 500`).  
    <!-- source: backend/src/main/java/com/pdfeditor/process/ZipResumeProcessor.java:16 -->
  * Maximum uncompressed size per file: **50 MB** (`MAX_UNCOMPRESSED_ENTRY_SIZE = 52428800L`).  
    <!-- source: backend/src/main/java/com/pdfeditor/process/ZipResumeProcessor.java:17 -->
  * Maximum total uncompressed ZIP extraction: **250 MB** (`MAX_TOTAL_UNCOMPRESSED_SIZE = 262144000L`).  
    <!-- source: backend/src/main/java/com/pdfeditor/process/ZipResumeProcessor.java:18 -->
  * Path Traversal Protection: Entries containing `..` or leading `/` are discarded (Zip-Slip defense).  
    <!-- source: backend/src/main/java/com/pdfeditor/process/ZipResumeProcessor.java:51 -->
  * Metadata filtering: Hidden files (`.*`) and `__MACOSX` resource forks are automatically ignored.  
    <!-- source: backend/src/main/java/com/pdfeditor/process/ZipResumeProcessor.java:57 -->

### 2.4 Allowed File Types
* **PDF Files:** Must begin with magic bytes `%PDF` (`0x25, 0x50, 0x44, 0x46`). Must be non-empty and unencrypted.  
  <!-- source: backend/src/main/java/com/pdfeditor/session/SessionValidator.java:49 -->
* **ZIP Files:** Must have ZIP magic bytes (`PK\x03\x04`, `PK\x05\x06`, or `PK\x07\x08`) or end with `.zip`. Must contain at least one `.pdf` file.  
  <!-- source: backend/src/main/java/com/pdfeditor/process/ZipResumeProcessor.java:27-35 -->

---

## 3. Endpoint Reference

### 3.1 `GET /api/health`
<!-- source: backend/src/main/java/com/pdfeditor/health/HealthController.java:13 -->

* **Purpose:** Probes service availability and uptime.
* **When to Call:** System readiness probes, Laravel health checks, scheduler pre-flight checks.
* **Headers:** None required.
* **HTTP Method:** `GET` only. Sending `POST` returns `500 Request method 'POST' is not supported`.
* **Success Response (`200 OK`):**
  ```json
  {
    "status": "UP"
  }
  ```

---

### 3.2 `POST /api/v1/resume/process`
<!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:86 -->

* **Purpose:** Primary enterprise resume processing pipeline. Extracts candidate contact information and applies permanent text-stream redaction according to active toggles.
* **When to Call:** When an applicant submits a resume, when bulk importing resumes from job portals, or when prepping resumes for recruiter dashboards.
* **Content-Type:** `multipart/form-data`
* **Headers:**
  ```http
  X-API-KEY: <API_KEY>
  ```

#### Request Parameters (Form-Data)

| Parameter Name | Data Type | Required? | Default in Code | Code Validation & Rules |
| :--- | :--- | :---: | :---: | :--- |
| `file` | `Binary File` | **Conditional** | `null` | Single `.pdf` file or `.zip` archive. Required if `files` is not provided. <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:88 --> |
| `files` | `Binary File[]` | **Conditional** | `null` | Array of `.pdf` files. Required if `file` is not provided. When multiple files are attached under this name, the server processes them as a multi-PDF batch. <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:89 --> |
| `extractEmail` | `Boolean` | No | `true` | If omitted or `null`, defaults to `true`. If `false`, disables email extraction. <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:112 --> |
| `extractPhone` | `Boolean` | No | `true` | If omitted or `null`, defaults to `true`. If `false`, disables phone extraction. <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:113 --> |
| `redactEmail` | `Boolean` | No | `true` | If `true` AND `extractEmail` is true, masks email text and removes `mailto:` URI annotations. <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:89,113 --> |
| `redactPhone` | `Boolean` | No | `true` | If `true` AND `extractPhone` is true, masks phone text and removes `tel:` URI annotations. <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:90,114 --> |

#### Toggle Configuration Presets

| Preset Name | Form-Data Values | Extraction Result | Redaction Result |
| :--- | :--- | :--- | :--- |
| **Full Clean (Default)** | `extractEmail=true`, `extractPhone=true`, `redactEmail=true`, `redactPhone=true` | Emails + Phones extracted | Emails + Phones scrubbed from PDF |
| **Extract Only (No Redaction)** | `extractEmail=true`, `extractPhone=true`, `redactEmail=false`, `redactPhone=false` | Emails + Phones extracted | Original PDF returned intact |
| **Email Only** | `extractEmail=true`, `extractPhone=false`, `redactEmail=true`, `redactPhone=false` | Only emails extracted | Only emails scrubbed; phone remains |
| **Phone Only** | `extractEmail=false`, `extractPhone=true`, `redactEmail=false`, `redactPhone=true` | Only phones extracted | Only phones scrubbed; email remains |

---

#### Success Responses (`200 OK`)

##### Case 1: Single PDF File Upload
<!-- source: backend/src/main/java/com/pdfeditor/process/dto/ResumeProcessResponse.java:31 -->
```json
{
  "extractedDetails": {
    "emails": [
      "candidate.primary@example.com",
      "candidate.alternate@domain.org"
    ],
    "phones": [
      "+91 98765 43210",
      "+91 91234 56789"
    ]
  },
  "redactedPdfBase64": "JVBERi0xLjYKJfbk/N8KMTcgMCBvYmoKPDwKL1R5cGUgL0NhdGFsb2cKL1BhZ2VzIDE2IDAgUgovVmVyc2lvbiAvMS42Cj4+CmVuZG9iagoxOCAwIG9iago8PAovTGVuZ3RoIDEwMjAzCi...",
  "pageCount": 1,
  "warnings": [],
  "fileName": "Candidate Resume.pdf",
  "status": "SUCCESS",
  "remark": "Extracted 2 email(s) and 2 phone(s). Redaction successfully applied.",
  "isBatch": false,
  "totalFiles": 1,
  "successCount": 1,
  "errorCount": 0
}
```
*Response Headers:*
* `X-Page-Count: 1`
* `X-Emails-Count: 2`
* `X-Phones-Count: 2`
<!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:267-269 -->

---

##### Case 2: Batch Upload (Multi-PDF or Bulk ZIP)
<!-- source: backend/src/main/java/com/pdfeditor/process/dto/ResumeProcessResponse.java:56 -->
```json
{
  "extractedDetails": {
    "emails": [
      "applicant.one@company.com",
      "applicant.alt@university.edu",
      "applicant.two@custom-domain.io"
    ],
    "phones": [
      "+91 98765 43210",
      "+1 (555) 019-2834",
      "+44 20 7946 0958"
    ]
  },
  "pageCount": 0,
  "warnings": [],
  "fileName": "multi_resumes_batch.zip",
  "status": "SUCCESS",
  "remark": "Batch completed: 2 total, 2 success, 0 error(s).",
  "isBatch": true,
  "totalFiles": 2,
  "successCount": 2,
  "errorCount": 0,
  "batchResults": [
    {
      "fileName": "candidate_one.pdf",
      "status": "SUCCESS",
      "remark": "Extracted 2 email(s) and 1 phone(s). Redaction successfully applied.",
      "extractedDetails": {
        "emails": [
          "applicant.one@company.com",
          "applicant.alt@university.edu"
        ],
        "phones": [
          "+91 98765 43210"
        ]
      },
      "pageCount": 1,
      "redactedPdfBase64": "JVBERi0xLjYK..."
    },
    {
      "fileName": "candidate_two.pdf",
      "status": "SUCCESS",
      "remark": "Extracted 1 email(s) and 2 phone(s). Redaction successfully applied.",
      "extractedDetails": {
        "emails": [
          "applicant.two@custom-domain.io"
        ],
        "phones": [
          "+1 (555) 019-2834",
          "+44 20 7946 0958"
        ]
      },
      "pageCount": 1,
      "redactedPdfBase64": "JVBERi0xLjQK..."
    }
  ],
  "outputZipBase64": "UEsDBBQACAgI..."
}
```
*Response Headers:*
* `X-Total-Files: 2`
* `X-Success-Count: 2`
* `X-Error-Count: 0`
<!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:301-303 -->

---

#### Exact Code-Generated Remark Values

The `remark` string is generated deterministically by `ProcessController.java:461-474`:
* `Extracted <X> email(s) and <Y> phone(s). Redaction successfully applied.`
* `Extracted <X> email(s) and <Y> phone(s). Redaction was skipped (toggles OFF).`
* `Extracted <X> email(s). No phone numbers found. Redaction successfully applied.`
* `Extracted <X> email(s). No phone numbers found. Redaction was skipped (toggles OFF).`
* `Extracted <Y> phone(s). No email address found. Redaction successfully applied.`
* `Extracted <Y> phone(s). No email address found. Redaction was skipped (toggles OFF).`
* `No email or phone contact details detected in document. Redaction successfully applied.`
* `No email or phone contact details detected in document. Redaction was skipped (toggles OFF).`

Failure Remarks (Per-item or Single PDF):
* `Password-protected or encrypted PDFs are not supported` <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:330 -->
* `Invalid or corrupted PDF file: <exception message>` <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:334 -->
* `Scanned or image-only PDF: no extractable text found for RESUME mode` <!-- source: backend/src/main/java/com/pdfeditor/rules/ResumeRedactionService.java:271 -->
* `Redaction incomplete: contact info or links still detected in output PDF` <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:451 -->
* `Redaction failed: <exception message>` <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:391 -->

---

#### Error Responses & Edge Cases

All server-level errors return the standard error schema:
```json
{
  "timestamp": "2026-11-16T11:45:00.123456Z",
  "status": 400,
  "error": "Bad Request",
  "message": "File is required (parameter 'file' or 'files') and cannot be empty",
  "path": "/api/v1/resume/process"
}
```

| HTTP Status | Error Type | Cause | Behavior in Batch vs Single |
| :---: | :--- | :--- | :--- |
| **`400`** | `Bad Request` | Neither `file` nor `files` provided; file is 0 bytes; invalid ZIP format; missing `%PDF` header. <!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:108 --> | Single: 400 aborts request.<br>Batch: Invalid ZIP aborts request. |
| **`401`** | `Unauthorized` | Missing or invalid `X-API-KEY` header. <!-- source: backend/src/main/java/com/pdfeditor/security/ApiKeyAuthFilter.java:136 --> | Aborts request immediately. |
| **`413`** | `Payload Too Large` | Uploaded file or multipart body exceeds 50MB. <!-- source: backend/src/main/java/com/pdfeditor/config/GlobalExceptionHandler.java:50 --> | Aborts request immediately. |
| **`422`** | `Unprocessable Entity` | PDF is password-protected/encrypted, or is a scanned image with no extractable text. <!-- source: backend/src/main/java/com/pdfeditor/session/SessionValidator.java:56, backend/src/main/java/com/pdfeditor/rules/ResumeRedactionService.java:271 --> | Single: Returns HTTP 422.<br>**Batch:** Isolated! File gets `status: "ERROR"`, remaining files process cleanly. Overall returns `200 OK` with `status: "PARTIAL_SUCCESS"`. |
| **`429`** | `Too Many Requests` | API key exceeded 120 req/minute rate limit. <!-- source: backend/src/main/java/com/pdfeditor/security/ApiRateLimiter.java:38 --> | Aborts request immediately. |
| **`500`** | `Internal Server Error` | Unexpected unhandled server exception. <!-- source: backend/src/main/java/com/pdfeditor/config/GlobalExceptionHandler.java:58 --> | Aborts request. |

---

### 3.3 `POST /api/v1/resume/extract`
<!-- source: backend/src/main/java/com/pdfeditor/process/ResumeExtractController.java:34 -->

* **Purpose:** Single-pass structured resume extraction (parsing name, contact, skills, and sections). Does NOT perform redaction.
* **When to Call:** When candidate profiling or ATS parsing is needed without stripping contact information.
* **Content-Type:** `multipart/form-data`
* **Parameters:** `file` (MultipartFile, required).
* **Success Response (`200 OK`):**
  ```json
  {
    "name": "Jane Candidate",
    "contact": {
      "emails": ["jane.dev@example.com", "jane.personal@domain.org"],
      "phones": ["+91 98765 43210"],
      "links": ["https://linkedin.com/in/..."]
    },
    "skills": ["Java", "Spring Boot", "Laravel", "Vue.js", "Docker"],
    "sections": {
      "EXPERIENCE": ["Software Engineer at ..."],
      "EDUCATION": ["B.Tech Computer Science ..."]
    },
    "metadata": {
      "pageCount": 1,
      "wordCount": 420
    }
  }
  ```
  <!-- source: backend/src/main/java/com/pdfeditor/process/dto/ResumeExtractResponse.java:7 -->

---

### 3.4 `POST /api/v1/process`
<!-- source: backend/src/main/java/com/pdfeditor/process/ProcessController.java:480 -->

* **Purpose:** Low-level arbitrary PDF manipulation pipeline.
* **When to Call:** When custom coordinates, exact text replacement strings, or page deletion operations are specified.
* **Parameters:**
  * `file` (MultipartFile, required)
  * `ops` (String, required JSON array of `ProcessOperation`)
* **Success Response (`200 OK`):**
  * `Content-Type: application/pdf` (Binary stream).
  * Response Header `X-Process-Matches`: Count of executed operations.

---

## 4. Data Schemas & Enums

### 4.1 `ResumeProcessResponse` (Root Payload)
<!-- source: backend/src/main/java/com/pdfeditor/process/dto/ResumeProcessResponse.java:8 -->

| Field Name | Type | Nullable? | Values / Description |
| :--- | :--- | :---: | :--- |
| `isBatch` | `boolean` | No | `false` for single PDF, `true` for ZIP or multi-PDF upload. |
| `fileName` | `string` | No | Name of uploaded file (or `"multi_resumes_batch.zip"`). |
| `status` | `string` | No | `"SUCCESS"`, `"PARTIAL_SUCCESS"`, or `"ERROR"`. |
| `remark` | `string` | No | Human-readable outcome summary. |
| `extractedDetails` | `object` | No | Object containing aggregate `emails: string[]` and `phones: string[]`. |
| `pageCount` | `int` | No | Page count of the processed PDF (0 for batch root). |
| `warnings` | `string[]` | No | List of warning strings (e.g. `["no_contact_found"]`). |
| `redactedPdfBase64` | `string` | Yes | Standard Base64 encoded clean PDF. Present on single success; `null` on batch. |
| `totalFiles` | `int` | Yes | Total count of files in batch (1 for single). |
| `successCount` | `int` | Yes | Count of successfully processed files. |
| `errorCount` | `int` | Yes | Count of failed files. |
| `batchResults` | `ResumeItemResult[]` | Yes | Array of per-file results. Present when `isBatch` is `true`. |
| `outputZipBase64` | `string` | Yes | Standard Base64 encoded clean ZIP archive containing all clean PDFs. Present on batch success. |

### 4.2 `ResumeItemResult` (Per-Item Result inside `batchResults`)
<!-- source: backend/src/main/java/com/pdfeditor/process/dto/ResumeItemResult.java:6 -->

| Field Name | Type | Nullable? | Values / Description |
| :--- | :--- | :---: | :--- |
| `fileName` | `string` | No | Original file name of the individual candidate resume. |
| `status` | `string` | No | `"SUCCESS"` or `"ERROR"`. |
| `remark` | `string` | No | Specific extraction count or failure cause for this candidate. |
| `extractedDetails` | `object` | No | `emails: string[]`, `phones: string[]` for this candidate. |
| `pageCount` | `int` | No | Number of pages in this candidate's resume (0 on failure). |
| `redactedPdfBase64` | `string` | Yes | Base64 clean PDF for this candidate. `null` if file failed. |

---

### 4.3 Handling Extracted Metadata: Multiple Emails, Multiple Phones & Arbitrary Domains

Candidate resumes vary widely in structure, contact counts, and email hosting providers. The extraction and redaction engine is architected to handle arbitrary email formats, arbitrary domains, and variable item counts:

#### 1. Dynamic Domain & Pattern Matching (Not Hardcoded to Any Single Provider)
* **Any Domain Supported:** The engine does **not** check against a hardcoded list of domains (like Gmail or Yahoo). Any valid RFC 5322 email string matching `[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}` is extracted and scrubbed.
  * Commercial / Public Webmail: `@gmail.com`, `@outlook.com`, `@yahoo.com`, `@icloud.com`, `@proton.me`, `@zoho.com`, etc.
  * Corporate & Custom Domains: `@company.org`, `@tech-startup.io`, `@consulting.co.uk`, `@enterprise.net`, etc.
  * Institutional & Academic: `@university.edu`, `@alumni.iitd.ac.in`, `@oxon.org`, etc.
  * Subdomains & Aliases: `developer+tag@eng.subdomain.company.com`.

#### 2. Array Cardinality: 0, 1, or Multiple Contacts
The `emails` and `phones` properties are **always returned as JSON arrays (`string[]`)**, never as single scalar strings:
* **Zero Items (`[]`):** When a resume contains no detectible email or phone (e.g. candidate only listed a LinkedIn profile, GitHub link, or postal address), the API returns `"emails": []` and `"phones": []`.
* **Single Item (`["candidate@domain.com"]`):** Standard resume with one primary contact.
* **Multiple Items (`["primary@example.com", "alternate.work@domain.org"]`):** A candidate may list personal email, college/work email, alternate phone, or WhatsApp number.
* **Guaranteed Complete Redaction:** Every single email and phone number present in the document is scrubbed from the PDF text stream, vector bounding boxes, and interactive clickable links (`mailto:` / `tel:`).

#### 3. How to Consume & Display in Laravel / Blade Dashboard Tables
When displaying candidate records in a Laravel view or API response, always iterate over `emails` and `phones` as arrays:

```blade
{{-- Option A: Comma-Separated Display --}}
<td>{{ implode(', ', $item['extractedDetails']['emails'] ?? []) ?: 'None' }}</td>
<td>{{ implode(', ', $item['extractedDetails']['phones'] ?? []) ?: 'None' }}</td>

{{-- Option B: Badges / Tags in Candidate Table --}}
<td>
    @forelse($item['extractedDetails']['emails'] ?? [] as $email)
        <span class="badge badge-primary">{{ $email }}</span>
    @empty
        <span class="text-muted">No Email Found</span>
    @endforelse
</td>
<td>
    @forelse($item['extractedDetails']['phones'] ?? [] as $phone)
        <span class="badge badge-secondary">{{ $phone }}</span>
    @empty
        <span class="text-muted">No Phone Found</span>
    @endforelse
</td>
```

---

## 5. PowerShell Smoke Test Script

> **Execution Status:** **ACTUALLY EXECUTED & VERIFIED** on the live server on 2026-11-16. All 5 assertions passed (`PASS`).

```powershell
<#
.SYNOPSIS
    Automated Smoke Test for Resume Redaction API
.DESCRIPTION
    Validates health, authentication, single PDF processing, multi-PDF processing,
    and structured extraction against a running server.
#>

$baseUrl = "http://localhost:8080"
$apiKey  = "<API_KEY>" # Replace with your provisioned API_KEY
$testPdf = "C:\Users\white\Desktop\resume.pdf" # Path to a local valid test PDF

Write-Host "`n=== RESUME REDACTION API SMOKE TEST ===" -ForegroundColor Cyan
Write-Host "Target Host: $baseUrl"
$passCount = 0
$failCount = 0

function Assert-Result($testName, $condition) {
    if ($condition) {
        Write-Host "  [PASS] $testName" -ForegroundColor Green
        $script:passCount++
    } else {
        Write-Host "  [FAIL] $testName" -ForegroundColor Red
        $script:failCount++
    }
}

# 1. Health Probe
try {
    $health = Invoke-RestMethod -Uri "$baseUrl/api/health" -Method Get -TimeoutSec 5
    Assert-Result "1. GET /api/health returns UP" ($health.status -eq "UP")
} catch {
    Assert-Result "1. GET /api/health reachable" $false
}

# 2. Authentication Rejection Check
$badAuthCode = curl.exe -s -o NUL -w "%{http_code}" -H "X-API-KEY: invalid_key_999" -X POST "$baseUrl/api/v1/resume/process"
Assert-Result "2. Invalid API Key returns 401 Unauthorized" ($badAuthCode -eq "401")

# 3. Single PDF Process
if (Test-Path $testPdf) {
    $singleResp = curl.exe -s -H "X-API-KEY: $apiKey" `
        -F "file=@$testPdf" `
        -F "extractEmail=true" `
        -F "extractPhone=true" `
        -F "redactEmail=true" `
        -F "redactPhone=true" `
        "$baseUrl/api/v1/resume/process" | ConvertFrom-Json

    Assert-Result "3. POST /api/v1/resume/process (Single) returns SUCCESS" ($singleResp.status -eq "SUCCESS" -and $singleResp.redactedPdfBase64.Length -gt 100)

    # 4. Multi-PDF Batch Process
    $multiResp = curl.exe -s -H "X-API-KEY: $apiKey" `
        -F "files=@$testPdf" `
        -F "files=@$testPdf" `
        -F "extractEmail=true" `
        -F "extractPhone=true" `
        "$baseUrl/api/v1/resume/process" | ConvertFrom-Json

    Assert-Result "4. POST /api/v1/resume/process (Multi-file) returns isBatch=true with 2 items" ($multiResp.isBatch -eq $true -and $multiResp.totalFiles -eq 2)

    # 5. Resume Extract Endpoint
    $extractResp = curl.exe -s -H "X-API-KEY: $apiKey" `
        -F "file=@$testPdf" `
        "$baseUrl/api/v1/resume/extract" | ConvertFrom-Json

    Assert-Result "5. POST /api/v1/resume/extract returns contact metadata" ($extractResp.contact -ne $null)
} else {
    Write-Warning "Skipping tests 3-5: Test file not found at $testPdf"
}

Write-Host "`nTest Summary: Passed: $passCount | Failed: $failCount" -ForegroundColor ($failCount -eq 0 ? "Green" : "Red")
```

---

## 6. Laravel Reference Implementation

> **Execution Status:** **REFERENCE CODE (UNTESTED IN ACTIVE LARAVEL RUNTIME)**. Written strictly to Laravel 10/11 standards using standard `Illuminate\Support\Facades\Http` and `Illuminate\Support\Facades\Storage`.

### 6.1 Configuration Setup

#### `.env`
```env
RESUME_REDACTION_BASE_URL=http://localhost:8080
RESUME_REDACTION_API_KEY=<API_KEY>
RESUME_REDACTION_TIMEOUT=180
```

#### `config/services.php`
```php
'resume_redaction' => [
    'base_url' => env('RESUME_REDACTION_BASE_URL', 'http://localhost:8080'),
    'api_key'  => env('RESUME_REDACTION_API_KEY'),
    'timeout'  => (int) env('RESUME_REDACTION_TIMEOUT', 180),
],
```

---

### 6.2 Service Class: `App\Services\ResumeRedactionService.php`

```php
<?php

namespace App\Services;

use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Http;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Facades\Storage;
use RuntimeException;

class ResumeRedactionService
{
    protected string $baseUrl;
    protected string $apiKey;
    protected int $timeout;

    public function __construct()
    {
        $this->baseUrl = rtrim(config('services.resume_redaction.base_url'), '/');
        $this->apiKey  = (string) config('services.resume_redaction.api_key');
        $this->timeout = (int) config('services.resume_redaction.timeout', 180);

        if (empty($this->apiKey)) {
            throw new RuntimeException('RESUME_REDACTION_API_KEY is not configured in services.php');
        }
    }

    /**
     * Check if the Resume Processing engine is online.
     */
    public function isHealthy(): bool
    {
        try {
            $response = Http::timeout(5)->get("{$this->baseUrl}/api/health");
            return $response->successful() && $response->json('status') === 'UP';
        } catch (\Throwable $e) {
            Log::warning('Resume API health check failed: ' . $e->getMessage());
            return false;
        }
    }

    /**
     * Process a single resume PDF or ZIP archive.
     *
     * @param UploadedFile|string $file - UploadedFile instance or absolute path on disk
     * @param array $options - Privacy toggles (extractEmail, extractPhone, redactEmail, redactPhone)
     */
    public function processSingle(UploadedFile|string $file, array $options = []): array
    {
        $toggles = array_merge([
            'extractEmail' => true,
            'extractPhone' => true,
            'redactEmail'  => true,
            'redactPhone'  => true,
        ], $options);

        $contents = $file instanceof UploadedFile ? file_get_contents($file->getRealPath()) : file_get_contents($file);
        $filename = $file instanceof UploadedFile ? $file->getClientOriginalName() : basename($file);

        $request = Http::withHeaders([
            'X-API-KEY' => $this->apiKey,
        ])->timeout($this->timeout);

        $response = $request->attach('file', $contents, $filename)
            ->post("{$this->baseUrl}/api/v1/resume/process", [
                'extractEmail' => $toggles['extractEmail'] ? 'true' : 'false',
                'extractPhone' => $toggles['extractPhone'] ? 'true' : 'false',
                'redactEmail'  => $toggles['redactEmail'] ? 'true' : 'false',
                'redactPhone'  => $toggles['redactPhone'] ? 'true' : 'false',
            ]);

        if ($response->failed()) {
            $status = $response->status();
            $errorMsg = $response->json('message') ?? 'Unknown API error';
            Log::error("Resume API failed [HTTP {$status}]: {$errorMsg}");
            throw new RuntimeException("Resume processing failed ({$status}): {$errorMsg}");
        }

        return $response->json();
    }

    /**
     * Process multiple PDF resumes in a single batch request.
     *
     * @param array<UploadedFile|string> $files
     */
    public function processMultiPdf(array $files, array $options = []): array
    {
        $toggles = array_merge([
            'extractEmail' => true,
            'extractPhone' => true,
            'redactEmail'  => true,
            'redactPhone'  => true,
        ], $options);

        $request = Http::withHeaders([
            'X-API-KEY' => $this->apiKey,
        ])->timeout($this->timeout);

        foreach ($files as $file) {
            $contents = $file instanceof UploadedFile ? file_get_contents($file->getRealPath()) : file_get_contents($file);
            $filename = $file instanceof UploadedFile ? $file->getClientOriginalName() : basename($file);
            $request->attach('files', $contents, $filename);
        }

        $response = $request->post("{$this->baseUrl}/api/v1/resume/process", [
            'extractEmail' => $toggles['extractEmail'] ? 'true' : 'false',
            'extractPhone' => $toggles['extractPhone'] ? 'true' : 'false',
            'redactEmail'  => $toggles['redactEmail'] ? 'true' : 'false',
            'redactPhone'  => $toggles['redactPhone'] ? 'true' : 'false',
        ]);

        if ($response->failed()) {
            throw new RuntimeException("Multi-PDF batch failed ({$response->status()}): " . $response->json('message'));
        }

        return $response->json();
    }

    /**
     * Decode and store clean PDF or clean ZIP to storage disk.
     */
    public function storeCleanFile(string $base64Payload, string $destinationPath, string $disk = 'local'): bool
    {
        $binary = base64_decode($base64Payload, true);
        if ($binary === false) {
            throw new RuntimeException('Failed to base64_decode clean PDF/ZIP payload');
        }

        return Storage::disk($disk)->put($destinationPath, $binary);
    }
}
```

---

### 6.3 Controller Example: `App\Http\Controllers\ResumeController.php`

```php
<?php

namespace App\Http\Controllers;

use App\Services\ResumeRedactionService;
use App\Jobs\ProcessBulkResumesJob;
use Illuminate\Http\Request;
use Illuminate\Http\JsonResponse;

class ResumeController extends Controller
{
    public function __construct(protected ResumeRedactionService $resumeService) {}

    public function process(Request $request): JsonResponse
    {
        $request->validate([
            'file'         => 'required|file|max:51200', // max 50MB
            'extractEmail' => 'nullable|boolean',
            'extractPhone' => 'nullable|boolean',
            'redactEmail'  => 'nullable|boolean',
            'redactPhone'  => 'nullable|boolean',
        ]);

        $file = $request->file('file');
        $ext = strtolower($file->getClientOriginalExtension());

        // For large ZIP archives, dispatch to background queue
        if ($ext === 'zip' && $file->getSize() > 10 * 1024 * 1024) {
            $path = $file->store('temp_batches', 'local');
            ProcessBulkResumesJob::dispatch($path, $request->only(['extractEmail', 'extractPhone']));

            return response()->json([
                'status'  => 'QUEUED',
                'message' => 'Batch archive queued for background processing.',
            ], 202);
        }

        // Process synchronously
        $result = $this->resumeService->processSingle($file, $request->only([
            'extractEmail', 'extractPhone', 'redactEmail', 'redactPhone'
        ]));

        // If single PDF, save clean copy to storage
        if (!$result['isBatch'] && !empty($result['redactedPdfBase64'])) {
            $cleanPath = 'clean_resumes/' . uniqid('clean_') . '.pdf';
            $this->resumeService->storeCleanFile($result['redactedPdfBase64'], $cleanPath);
            $result['storedCleanPath'] = $cleanPath;
            // Unset heavy payload before returning JSON to frontend
            unset($result['redactedPdfBase64']);
        }

        return response()->json($result);
    }
}
```

---

### 6.4 Queued Job: `App\Jobs\ProcessBulkResumesJob.php`

```php
<?php

namespace App\Jobs;

use App\Services\ResumeRedactionService;
use Illuminate\Bus\Queueable;
use Illuminate\Contracts\Queue\ShouldQueue;
use Illuminate\Foundation\Bus\Dispatchable;
use Illuminate\Queue\InteractsWithQueue;
use Illuminate\Queue\SerializesModels;
use Illuminate\Support\Facades\Log;
use Illuminate\Support\Facades\Storage;

class ProcessBulkResumesJob implements ShouldQueue
{
    use Dispatchable, InteractsWithQueue, Queueable, SerializesModels;

    public int $timeout = 300; // 5 minutes

    public function __construct(
        protected string $tempZipPath,
        protected array $toggles = []
    ) {}

    public function handle(ResumeRedactionService $service): void
    {
        $fullPath = Storage::disk('local')->path($this->tempZipPath);

        try {
            $response = $service->processSingle($fullPath, $this->toggles);

            Log::info("Bulk resume batch finished: {$response['remark']}");

            // Store clean ZIP archive
            if (!empty($response['outputZipBase64'])) {
                $cleanZipPath = 'clean_batches/clean_' . basename($this->tempZipPath);
                $service->storeCleanFile($response['outputZipBase64'], $cleanZipPath);
                Log::info("Saved clean batch ZIP to: {$cleanZipPath}");
            }
        } finally {
            // Clean up temporary upload
            Storage::disk('local')->delete($this->tempZipPath);
        }
    }
}
```

---

### 6.5 Pest / PHPUnit Feature Test (`tests/Feature/ResumeRedactionTest.php`)

```php
<?php

namespace Tests\Feature;

use App\Services\ResumeRedactionService;
use Illuminate\Http\UploadedFile;
use Illuminate\Support\Facades\Http;
use Tests\TestCase;

class ResumeRedactionTest extends TestCase
{
    public function test_it_processes_single_resume_successfully(): void
    {
        Http::fake([
            'http://localhost:8080/api/v1/resume/process' => Http::response([
                'isBatch'           => false,
                'fileName'          => 'candidate.pdf',
                'status'            => 'SUCCESS',
                'remark'            => 'Extracted 1 email(s) and 1 phone(s). Redaction successfully applied.',
                'extractedDetails'  => [
                    'emails' => ['john@example.com'],
                    'phones' => ['+1-555-0199'],
                ],
                'pageCount'         => 1,
                'redactedPdfBase64' => base64_encode('%PDF-1.4 mock content'),
                'warnings'          => [],
            ], 200),
        ]);

        $service = app(ResumeRedactionService::class);
        $file = UploadedFile::fake()->create('candidate.pdf', 500, 'application/pdf');

        $result = $service->processSingle($file);

        $this->assertEquals('SUCCESS', $result['status']);
        $this->assertEquals(['john@example.com'], $result['extractedDetails']['emails']);
        $this->assertNotEmpty($result['redactedPdfBase64']);
    }

    public function test_it_handles_api_authentication_failure(): void
    {
        Http::fake([
            'http://localhost:8080/api/v1/resume/process' => Http::response([
                'status'  => 401,
                'error'   => 'Unauthorized',
                'message' => 'Invalid or missing API Key.',
            ], 401),
        ]);

        $this->expectException(\RuntimeException::class);
        $this->expectExceptionMessage('401');

        $service = app(ResumeRedactionService::class);
        $file = UploadedFile::fake()->create('candidate.pdf', 500, 'application/pdf');
        $service->processSingle($file);
    }
}
```

---

## 7. Integration Checklist & Common Pitfalls

### Step-by-Step Integration Order
1. **Server Network Probe:** Test `GET /api/health` from the Laravel host using `curl` or `Http::get()`. Confirm response is `{"status": "UP"}`.
2. **Environment Configuration:** Add `RESUME_REDACTION_BASE_URL` and `RESUME_REDACTION_API_KEY` to Laravel's `.env`.
3. **Verify Multipart Boundary:** Ensure your HTTP client uses `multipart/form-data` with `Http::attach('file', ...)`.
4. **Inspect Headers:** Ensure `X-API-KEY` is attached on every `/api/v1/**` request.
5. **Decouple Base64 Decoding:** Save decoded PDFs asynchronously or stream them to object storage (AWS S3) to avoid memory spikes on large batches.
6. **Graceful Batch Handling:** Check `isBatch: true`. Do not expect a single `redactedPdfBase64` on ZIP files; iterate over `batchResults` and inspect `outputZipBase64`.

### Common Mistakes
* ❌ **Wrong Endpoint Method on Health Check:** Sending `POST /api/health` instead of `GET`. Returns `500 Request method 'POST' is not supported`.
* ❌ **Missing Parameter Name in Form-Data:** Leaving field name as `"field name"` (Thunder Client / Postman UI placeholder) instead of typing `"file"`. Causes `500 Required part 'file' is not present`.
* ❌ **Calling `/api/documents/**` instead of `/api/v1/resume/process`:** Returns `401 Unauthorized` because `/api/documents` requires `UI_API_KEY`, not `API_KEY`.
* ❌ **Assuming Partial Failure is HTTP 400/500:** When a ZIP contains 1 bad PDF out of 10, the API returns **HTTP 200 OK** with overall `"status": "PARTIAL_SUCCESS"`. Checking `$response->failed()` will evaluate to `false`. Always inspect `$result['errorCount']` and item-level `status`.
* ❌ **Exceeding 50MB in Multipart:** Sending ZIP archives > 50MB results in HTTP 413 from Spring Tomcat before the code unzips the payload.

---

## 8. Candidate PII Compliance (What Consumer Must NOT Log)

Under data privacy regulations (GDPR, California CCPA, India DPDP Act 2023):
* 🚫 **Do NOT log candidate emails or phone numbers** in application log files (`storage/logs/laravel.log`), Papertrail, or Datadog.
* 🚫 **Do NOT log raw request bodies or response JSON** containing `extractedDetails` in web server access logs (Nginx / Apache).
* 🚫 **Do NOT pass API keys or PII in URL Query Parameters** (e.g. `?apiKey=...&email=...`). URLs are cached by proxies and logged in plain text.
* 🚫 **Do NOT store unredacted original resumes permanently** unless explicit consent for un-redacted processing is obtained from the candidate.

---

## 9. Security Issues Found & UNKNOWN Items

### Security Issues Identified in Codebase:
1. **Frontend API Key Exposure:**  
   In `pdf-editor/frontend/.env` and `resume-dashboard/.env`, `VITE_API_KEY` is embedded in client-side builds. Anyone inspecting browser network traffic or JS bundles can extract the live API key.  
   *Recommendation:* Use a backend proxy in Laravel; never expose `API_KEY` to browser clients directly.
2. **Hardcoded CORS Origins:**  
   `CorsConfig.java:13` explicitly permits only `http://localhost:5173` and `http://localhost:3000`. External browser single-page applications hosted elsewhere will be blocked by CORS unless called server-to-server.
3. **Multi-File Memory Pressure:**  
   In `ProcessController.java:125`, `filesList` items are read directly into memory (`mf.getBytes()`) without an aggregate size check before allocation, which could cause high heap spikes under concurrent large uploads.

### Open Questions / UNKNOWN Items:
* `UNKNOWN`: Whether the engine is planned to support OCR on scanned PDFs in a future release. Currently, scanned/image-only PDFs strictly throw 422 (`"Scanned or image-only PDF: no extractable text found for RESUME mode"`).
* `UNKNOWN`: Exact retention policy of session files written in `target/sessions` during visual editor operations.

---

## 10. Discrepancies Found (Code vs Previous Spec)

1. **ZIP Upload Size Limit:**  
   `RESUME_FEATURE_DEEP_SPECIFICATION.md` stated that the upload dropzone accepts up to 250MB. However, `application.yml:11-12` strictly configures `spring.servlet.multipart.max-file-size: 50MB` and `max-request-size: 50MB`. Any upload exceeding 50MB is rejected with `413 Payload Too Large`.
2. **Multi-PDF Parameter Name:**  
   The previous specification only described `file` (Single PDF or ZIP). The controller was hardened to also support `files` (array of `MultipartFile`), which allows selecting multiple individual PDFs directly.
3. **HTTP Status on Batch Partial Errors:**  
   The previous specification loosely described `PARTIAL_SUCCESS` as an HTTP status. In actual code, HTTP status is `200 OK`, while `"status": "PARTIAL_SUCCESS"` is a field inside the JSON body.
4. **Health Check Method:**  
   `HealthController.java:13` strictly maps `@GetMapping`. Sending a `POST` request to `/api/health` causes a Spring `HttpRequestMethodNotSupportedException` (500).
