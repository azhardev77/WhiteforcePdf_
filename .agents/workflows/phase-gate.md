---
description: Runs one named phase of the master plan with before/after tests, branch check, commit, and a fixed report. Usage: /phase-gate go phase N
---

Run the phase I name after this command, using this exact procedure:

1. Confirm you are on branch feature/sessions-modes. If not, stop.
2. Run backend tests and frontend build. Record exact pass/fail counts as BEFORE.
3. Re-read the phase text in the master-plan workflow. Do only that phase, nothing from other phases.
4. Run backend tests and frontend build again. Record AFTER. If any previously-passing test fails, revert this phase's changes and report instead of patching around it.
5. Run the phase's own verification steps and paste the real command output.
6. Commit with message "phase N: <short summary>".
7. Send the report: files changed, tests before/after, verification output, known gaps, things noticed but not changed.
8. STOP. Do not start the next phase.
