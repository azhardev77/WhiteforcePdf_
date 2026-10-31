# PRD — Three-Mode PDF Platform Upgrade

- [x] Task 1: Audit and enhance `PdfRedactionService.java` to support auto-font shrinking down to a 6.0pt floor when replacement text overflows the target bbox, and enforce Page Count Invariant verification (input == output pages). Verify with `mvn test`.
- [ ] Task 2: Implement the missing `updateFromTipTap(tipTapJson)` method in `frontend/src/stores/document.js` to parse TipTap block updates into store state without wiping existing coordinate metadata.
- [ ] Task 3: In `frontend/src/views/EditorView.vue`, mount `TipTapEditor.vue` behind a clean Mode Switcher toggle ("Direct Canvas In-Place" vs "Section Reflow"), preserving layout state when switching.
- [ ] Task 4: Add explicit 15-second timeouts and a semaphore concurrency limit (max 2 instances) in `backend/src/main/java/com/pdfeditor/export/PlaywrightManager.java` to eliminate OOM container crash risks.
- [ ] Task 5: Clean up 0-byte Java stub files in `backend/src/main/java/com/pdfeditor/` or link `FontMatcher.java` to the existing `resolveFontFilename` logic in `PdfRedactionService.java`.
