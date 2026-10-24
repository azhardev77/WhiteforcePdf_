---
description: Master plan with Phases 0-7: sessions, modes (resume/tender/edit/mark), PII hiding, mark and apply, cleanup. Read by phase-gate.
---

ROLE
You are working on the existing project "PDF Visual Studio" (Spring Boot 3.3.4 + PDFBox 3.0.2 + Playwright backend, Vue 3 + pdfjs + pdf-lib frontend) at C:\Users\white\Desktop\pdf-editor on Windows/PowerShell. Docker is NOT available; do not add Docker steps. Use mvnw.cmd or mvn, whichever is present.

GOAL
Add a session-based system with these modes (one shared engine, not separate engines):
RESUME - auto-hides email + phone numbers
TENDER - rule set TBD (behaves like EDIT with empty rules until I supply a sample)
EDIT - no auto rules, manual editing only
MARK - user marks text/areas in the editor, then "Apply" permanently removes them
Every PDF has a source: UPLOAD (user uploads in the UI) or API (an external system sends it with an API key). The system must always know which one. PDFs can arrive via API for ALL modes, including RESUME (server-side hiding must happen before any human opens it). Sessions auto-delete after a TTL.

GOLDEN RULES (non-negotiable - nothing that works today may break)

1. Work on branch `feature/sessions-modes`. Commit after every phase with a clear message. Never commit secrets, target/, dist/, node_modules/, \*.rar.
2. ADDITIVE ONLY. Do not change behavior, signatures, or response shapes of existing endpoints (/api/documents/**, /api/export/**) or existing services unless a phase explicitly says so. Add new classes/files instead.
3. Do not refactor PdfCanvasEditor.vue (4900 lines), EditorView.vue, or document.js beyond the minimal lines a phase requires. No renames, no reformatting, no drive-by fixes.
4. Do not delete any file. Do not touch the 0-byte skeleton files unless a phase needs that exact file.
5. Before each phase: run the full backend test suite and the frontend build. After each phase: run them again. Test count must not go down and no previously-passing test may fail. If something breaks, revert that phase's changes and report - do not patch around it.
6. Do not guess. If a requirement is unclear or a file doesn't behave as expected, STOP and report instead of inventing behavior.
7. Execute ONE phase at a time. After each phase, STOP and send the report. Do not start the next phase until I say "go phase N".

PHASE 0 - BASELINE (read-only, change nothing)
a) Make sure the branch exists. Run backend tests and frontend build; record exact pass/fail counts and the 42 IDE "Problems" (group by file/severity).
b) Run verify-e2e.cjs against the running app and record whether it passes.
c) Investigate this log line and tell me the root cause: "WARN ApiKeyAuthFilter - Unauthorized API access attempt to [/api/export/redact] from IP 0:0:0:0:0:0:0:1: missing or invalid 'X-API-KEY' header". Find which caller sent it (browser via frontend/src/api/http.js, verify-e2e.cjs, curl, other) and whether the key actually sent matches the backend key. Do NOT fix it.
d) In frontend/src/stores/document.js (around lines 510 and 638) the code falls back to pdf-lib client export when backend /api/export/redact fails. Confirm whether that fallback can leave the original text inside the PDF file. Report only.
e) Run `git ls-files` and tell me whether target/, dist/, node_modules/, src.rar are tracked.
f) Real test: take sample-pdfs/resume.pdf, run it through the existing redaction path (PdfRedactionService.redactAndReplace with a box over the email), extract text from the OUTPUT with PDFBox PDFTextStripper, and tell me whether the removed text is truly gone from the file or still extractable. Also check images and vector graphics under a redaction box. Also tell me if sample-pdfs/resume.pdf already contains hidden/redacted contact info.

PHASE 1 - SECURITY (keep everything working)
a) Remove the hardcoded default "my_secure_secret_key_12345" from SecurityConfig, application.yml, and frontend/src/api/http.js. Backend reads API_KEY from env; if missing, the app must FAIL TO START with a clear message. Frontend reads VITE_API_KEY from a git-ignored .env; update .env.example with placeholders. With both set, behavior must be identical to today.
b) Compare API keys with MessageDigest.isEqual (constant time).
c) Fix path traversal in /api/documents/extract-sample and /raw-sample: resolve against the sample-pdfs directory, normalize, reject anything that escapes it. Add tests (../, absolute paths, encoded variants).
d) Update existing tests that depended on the default key. Do not weaken them.
Note: the browser still needs a key for legacy endpoints during this migration; it is replaced by session-scoped access in Phase 4.

PHASE 2 - SESSIONS (backend)
a) New package session: enum Mode {RESUME,TENDER,EDIT,MARK}, enum Source {UPLOAD,API}, PdfSession {id, mode, source, clientId, filePath, createdAt, expiresAt, status}. Storage: ConcurrentHashMap + files in a configurable directory (app.sessions.dir, ttl via app.sessions.ttl-minutes). No DB. Session id = 128-bit SecureRandom, URL-safe, unguessable.
b) New additive endpoints:
POST /api/v1/sessions [X-API-KEY] multipart file + mode -> {id, editUrl, expiresAt}
GET /api/v1/sessions/{id}/result [X-API-KEY] -> processed PDF bytes
POST /app/sessions [no key, rate/size limited] multipart file + mode -> {id}
GET /app/sessions/{id}/pdf -> PDF bytes (404 if expired)
Source = API for /api/v1, UPLOAD for /app. An API client may only read sessions it created.
c) Validate input: magic bytes %PDF, loadable by PDFBox, max size (configurable), reject password-protected PDFs with a clear 4xx. Never trust the filename.
d) Tests for: create/read, wrong key, expired session, non-PDF, oversized, cross-client access.

PHASE 3 - RULES ENGINE + RESUME HIDING (backend)
a) interface RuleSet { List<Match> find(DocumentModel doc); } with ResumeRules, TenderRules (empty for now), EditRules (empty).
b) Port frontend/src/utils/piiRedactor.js to Java as PiiDetector. Read that file and replicate its regexes AND false-positive filters exactly (date ranges like "Feb 2026 - Present", "2020 - 2024", years, percentages, icon prefixes). Write parity tests using the same inputs plus +91 numbers, numbers split with spaces/dashes, emails with icons.
c) Matches can span multiple text runs/lines: map character offsets to run bboxes and emit one box per run/line. Reuse how the frontend computes PII boxes if it already does.
d) Pipeline for RESUME: extract -> RuleSet -> TextRedactionEdit (empty replacement) -> PdfRedactionService.redactAndReplace -> save result. Applied automatically at session creation for both UPLOAD and API sources.
e) If the PDF has no extractable text (scanned/image-only), RESUME must return a clear 422, never a silent "success".
f) MANDATORY verification test: process sample-pdfs/resume.pdf, then (1) extract text from the output with PDFTextStripper and assert no email/phone remains, (2) scan raw content streams for the strings, (3) assert page count/size unchanged, (4) assert all non-PII text is unchanged. If the text is still recoverable anywhere, the phase FAILS - fix the redaction, do not paint over it.
g) Document honestly any case that cannot be handled (e.g. PII inside images).

PHASE 4 - FRONTEND HOME + ROUTES
a) New HomeView at "/" with three main buttons: Resume, Tender, Edit PDF, plus a secondary "API access" card (docs + sample curl). Routes: /resume, /tender, /edit, /edit/:sessionId. Keep the current editor reachable at /editor and update verify-e2e.cjs to use /editor ONLY if needed; it must still pass unchanged otherwise.
b) Upload -> POST /app/sessions with the mode -> navigate to /edit/:sessionId -> editor loads PDF from GET /app/sessions/{id}/pdf. Show a small badge with mode and source (UPLOAD/API).
c) Editor must keep all current features. Make minimal changes to EditorView/document.js: only add the "load from session" entry point.

PHASE 5 - MARK AND APPLY
a) "Mark" tool in the editor: user selects text or draws an area; marks are kept in a list (page, bbox, kind DELETE|REPLACE, newText).
b) "Apply" sends marks to POST /app/sessions/{id}/apply, executed server-side via PdfRedactionService. Never use client-side pdf-lib for removal.
c) Fix the silent fallback from Phase 0(d): for RESUME/MARK flows, backend failure must show an error and must NOT fall back to pdf-lib. Legacy flows keep today's behavior.
d) Test text, images and vector graphics under a mark. If any content under a mark cannot be truly removed, return it in a `warnings` field and show it in the UI. Do not claim removal that did not happen.

PHASE 6 - AUTO CLEANUP
@Scheduled job (every few minutes) deletes expired sessions and their files; on startup also remove orphan files older than TTL. Path-safe deletes only inside app.sessions.dir. Tests with a short TTL.

PHASE 7 - TENDER RULES
Do not start. Wait for me to provide a sample tender PDF and the list of fields to hide.
