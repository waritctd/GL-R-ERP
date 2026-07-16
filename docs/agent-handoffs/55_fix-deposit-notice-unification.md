# Agent Handoff

## Task
Plan item 4 from the 2026-07-16 sales-ticket-flow audit — **user-approved business-logic
change** ("One action: document drives track"): unify the two disconnected deposit-notice
mechanisms. Previously one button flipped `paymentStatus` with no document, the other created
the real document but flipped the ticket to `document_issued` — killing the dual-track UI and
letting unpaid tickets close.

## Branch
`fix/deposit-notice-unification` (stacked on `fix/document-module-removal`, PR #215 — same
files; rebase onto main once #215 merges)

## What changed (business rules — explicitly user-authorized)

### 1. Issuing the deposit-notice DOCUMENT is now the payment-track step
`DepositNoticeService.issue()`:
- requires `quotation_issued` + `paymentStatus=CUSTOMER_CONFIRMED` (was: any of
  APPROVED/QUOTATION_ISSUED/DOCUMENT_ISSUED with no payment check)
- sets `paymentStatus=DEPOSIT_NOTICE_ISSUED`, logs a `DEPOSIT_NOTICE_ISSUED` event carrying
  the doc number, and **leaves the main status at `quotation_issued`** (no more
  `document_issued` flip)
- draft creation (`createDraft`) is unchanged — drafts can still be prepared from `approved`.

### 2. The no-document endpoint is gone
`TicketService.issueDepositNotice` + `POST /tickets/{id}/deposit-notice` removed, along with
the hrApi method, the mock method, and the near-duplicate "ออกใบแจ้งมัดจำ" button.

### 3. Legacy close path gated
`TicketService.close()`: `document_issued` closes only when `paymentStatus` is **null**
(true pre-dual-track tickets) or **FULLY_PAID**. A mid-track ticket stranded at
`document_issued` (old-bug victims in existing data) can no longer close unpaid — recover
via revision or cancel. Frontend `can.close` mirrors this.

### 4. Frontend
- `can.generateDocument` (ออกใบแจ้งยอดมัดจำ) now requires `quotation_issued` +
  `CUSTOMER_CONFIRMED` + owner.
- NEXT_ACTION list reordered: dual-track steps now outrank the always-available
  `generateQuotation` re-issue, which used to mask the real next step in the banner.
- Demo quick-login panel gains the **Account** persona (missed in PR #212 — the user existed
  but had no button).
- mockApi mirrors all of the above (`depositNotices.issue` guard + payment advance + no
  status flip; `close` legacy guard).

## Files Changed
- `backend/.../deposit/DepositNoticeService.java` — issue() rework
- `backend/.../ticket/TicketService.java` — issueDepositNotice removed; close() legacy gate
- `backend/.../ticket/TicketController.java` — route removed
- `backend/src/test/.../DepositNoticeServiceTest.java` — NEW (3 tests: payment advance +
  status untouched + no DOCUMENT_ISSUED event; status guard; payment guard)
- `backend/src/test/.../TicketServiceTest.java` — issueDepositNotice tests replaced by 3
  legacy-close-gate tests (null-ps closes, mid-track refused, FULLY_PAID closes)
- `frontend/src/features/tickets/TicketDetailPage.jsx` — gates, button removal, NEXT_ACTION reorder
- `frontend/src/features/auth/LoginPage.jsx` — Account quick-login
- `frontend/src/api/hrApi.js`, `frontend/src/api/mockApi.js` — endpoint removal + mirrors

## Commands Run
```bash
cd backend && ./mvnw -B clean verify
cd frontend && npm run lint && npm test && npm run build
# mock drive (frontend-mock): sales on ticket 11 (CUSTOMER_CONFIRMED) → banner now points to
# ออกใบแจ้งยอดมัดจำ → DepositNoticePage → ออกเอกสาร (GLRD69001) → back on the ticket:
# paymentStatus=DEPOSIT_NOTICE_ISSUED, status still quotation_issued, stepper intact,
# timeline shows DEPOSIT_NOTICE_ISSUED with the doc number, waiting hint = รอฝ่ายบัญชี.
# Zero console errors.
```

## Tests / Build Results
- Backend `mvnw -B clean verify`: **415 tests, 0 failures, BUILD SUCCESS** (Testcontainers ran).
- Frontend: lint 0 errors (9 pre-existing warnings), 94/94 tests, build green.

## Known Risks
1. **Existing data**: real tickets already sitting at `document_issued` mid-payment-track can
   no longer close until FULLY_PAID (intended). Pure legacy tickets (ps null) unaffected.
   Demo ticket 8 (`document_issued`, ps null) verified still closable.
2. **Drafts created from `approved` can only be ISSUED after quotation + customer confirm** —
   sales may prepare early but must finish the sequence (intended: the document IS the step).
3. Mock authz remains non-authoritative (permission behavior verified in Java tests).

## Exact Next Prompt
```text
Repo GL-R-ERP, branch fix/deposit-notice-unification (stacked on fix/document-module-removal
/ PR #215). Read CLAUDE.md and docs/agent-handoffs/55_fix-deposit-notice-unification.md.
Review the diff vs its base: (1) issue() guard + payment advance vs the removed
TicketService.issueDepositNotice semantics, (2) the legacy close gate (ps null || FULLY_PAID)
against existing production data, (3) frontend can-map parity. After PR #215 merges, rebase
onto main and open this branch's PR; merge on the user's say-so.
```
