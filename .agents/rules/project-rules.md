---
trigger: always_on
---

PROJECT RULES - pdf-editor (always apply)

SAFETY

- Never break existing behavior. Add new files/classes instead of changing existing ones. Do not change existing endpoint signatures or response shapes unless I explicitly ask.
- Do not refactor, rename, reformat, or "clean up" code I did not ask about. Never touch PdfCanvasEditor.vue, EditorView.vue or stores/document.js beyond the minimal lines the current task needs.
- Never delete files. Never run destructive git commands (reset --hard, clean -fd, push --force, branch -D).
- Work only on a feature branch, never directly on main. Commit after each finished phase.
- Never commit secrets, .env, target/, dist/, node_modules/, \*.rar.

HONESTY

- Do not guess. If something is unclear, stop and ask.
- Never claim something works unless you ran it and saw the output. Paste real output.
- If a test fails, report it. Never weaken, skip, or delete tests to make them pass.
- Never fake PDF redaction. A white box is NOT redaction. Text must be gone from the file: verify by extracting text from the output PDF.
- A cause you did not prove must be labeled HYPOTHESIS, never stated as fact.
- If you cannot fully do something, say so explicitly.

PROCESS

- ONE phase at a time, then stop and report. No next phase without my "go phase N".
- Stay inside the project folder. Never read or write IDE internals, AppData, .gemini, or transcripts.
- Any file you create, even temporarily, must be listed in the report (created and deleted).
- Do not run git checkout/restore/reset on tracked files without asking me. ONE exception: after running mvn test you may restore sample-pdfs/, backend/sample-pdfs/ and frontend/public/samples/ only.
- Ignore IDE "Problems" warnings unless a phase says otherwise.

STACK

- Java 21, Spring Boot 3.3.4, PDFBox 3.0.2, Vue 3, pdfjs, pdf-lib. Windows/PowerShell. No Docker.
- Secrets come from environment variables only. No hardcoded keys or default secrets.
