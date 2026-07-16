# Agent Handoff

## Task
Plan item 5 from the 2026-07-16 sales-ticket-flow audit (user approved "delete module, move
revision"): the backend `document/` package still queried `sales.document`/`sales.document_item`,
renamed to `deposit_notice(_item)` by V29 — every `/api/documents/**` endpoint 500'd with
"relation does not exist". Its only *working* routes were the revision endpoint and the
note-templates endpoint (both work because they never touch the renamed tables).

## Branch
`fix/document-module-removal` (off main `d791725`)

## What changed
1. **Two live routes rehomed to `DepositNoticeController`, byte-compatible:**
   - `POST /api/tickets/{ticketId}/revision` → wires `DepositNoticeService.requestRevision`
     (already written, previously unmapped, verified **identical** to the DocumentService
     twin: sales+owner, APPROVED|DOCUMENT_ISSUED, same events/notifications, `{ticket}` response)
   - `GET /api/document-note-templates` → wires `DepositNoticeService.getNoteTemplates`
     (same `{templates}` response key; `sales.document_note_template` is shared infra V29
     deliberately kept)
2. **Deleted:** the whole `backend/.../document/` package (11 files incl. a stale duplicate
   `DepositNoticeRenderer`), `DocumentControllerTest`, the orphaned
   `frontend/src/features/documents/DocumentPage.jsx`, the `documents` namespace in
   `hrApi.js` + `routes.js`.
3. **Contract test:** the `documents` KNOWN_GAPS exemption is removed — `KNOWN_GAPS` is now
   empty, so mockApi ↔ hrApi surfaces match exactly in both directions with zero exceptions.

No frontend behavior change: `tickets.revision` and `depositNotices.noteTemplates` already
called the preserved routes; `tickets.listDocs`/`createDocDraft` already pointed at the
deposit-notice endpoints.

## Files Changed
- `backend/.../deposit/DepositNoticeController.java` — +2 endpoints (moved verbatim)
- `backend/.../document/**` — deleted (11 main + 1 test file)
- `frontend/src/features/documents/DocumentPage.jsx` — deleted (orphan)
- `frontend/src/api/hrApi.js`, `frontend/src/api/routes.js` — `documents` namespace removed
- `frontend/src/api/contract.test.js` — KNOWN_GAPS emptied

## Commands Run
```bash
cd backend && ./mvnw -B clean verify
cd frontend && npm run lint && npm test && npm run build
```

## Tests / Build Results
- Frontend: lint 0 errors (9 warnings — one fewer than before, the orphan page's went away),
  94/94 tests incl. the tightened contract test, build green.
- Backend: `mvnw -B clean verify` — **411 tests, 0 failures, BUILD SUCCESS** (Testcontainers ran;
  the retired module's controller tests were deleted with it).

## Known Risks
1. Any out-of-band consumer of `/api/documents/**` breaks — but those endpoints have thrown
   500 on every call since V29 landed, so nothing working is lost.
2. `DepositNoticeService` still carries dead constants (`PREPARER`, `CEO_ROLES`,
   `IMPORT_ROLES` partially unused) — cosmetic, untouched.

## Exact Next Prompt
```text
Repo GL-R-ERP, branch fix/document-module-removal. Read CLAUDE.md and
docs/agent-handoffs/54_fix-document-module-removal.md. Review main..HEAD: confirm the two
moved endpoints are byte-compatible (routes, response keys, authz) and that nothing still
references th.co.glr.hr.document or API_ROUTES.documents. Merge on the user's say-so.
```
