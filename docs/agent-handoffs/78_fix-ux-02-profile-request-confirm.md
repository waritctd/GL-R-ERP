# Agent Handoff

## Task
Fix audit finding **UX-02 (P1)** from `docs/ux-ui-audit/`: HR approves/rejects profile-change requests with **no confirmation step**. A single mis-click on `/requests` irreversibly mutated the employee master record — no dialog, no reason, no undo — while every other destructive action in the app (OT, leave, payroll, commission) goes through the shared `ConfirmDialog`.

## Branch
`fix/ux-02-profile-request-confirm` (branched off `main`)

## Base Commit
`2371b80` (main tip at branch creation)

## Current Commit
**Not committed.** Working tree only — awaiting explicit instruction to commit/push/PR.

## Agent / Model Used
Claude Sonnet implemented (2 rounds); Claude Opus specified, reviewed and verified each round.

## Scope

### In Scope
- Gate approve/reject behind the shared `ConfirmDialog`.
- Require a non-empty reason on reject, and actually persist it.
- Tests covering the dialog gating, the reason, the mock persistence, and the failure path.

### Out of Scope / deliberately NOT touched
- **Any Java or SQL.** The backend was already correct (see Key Finding below).
- Business logic, auth, permissions, routes, DB schema.
- The other 35 audit findings.
- Extraction of the shared confirm-message markup (see Follow-ups).

## Key Finding — the reason was already in-contract
Investigation before implementation showed this was **not** an API change:
- `UpdateProfileRequestRequest` is already `record(@Pattern("approved|rejected") String status, @Size(max=2000) String reviewerNote)`.
- `ProfileRequestService.update()` already calls `updatePendingStatus(id, status, reviewer, request.reviewerNote())`.
- `ProfileRequestRepository` already persists to `hr.profile_change_request.reviewer_note` (TEXT, exists since **V2**), and the action is audited (`APPROVE_PROFILE_REQUEST` / `REJECT_PROFILE_REQUEST`).

So `PATCH /api/profile-requests/{id}` already accepted `{ status, reviewerNote }` — **the frontend simply never sent it, and `mockApi` silently dropped it.** This fix is frontend-only and stays inside the existing contract, satisfying CLAUDE.md's "do not change API contracts as a side effect of UI work".

## Files Changed
- `frontend/src/features/profileRequests/ProfileRequestsPage.jsx` — approve/reject now set a `confirmState` and open a `ConfirmDialog` instead of calling `onReview` directly. Approve dialog (`tone="default"`) names the employee, the field, and `old → new`, and states the change is written to the employee register and is irreversible. Reject dialog (`tone="danger"`, `requireReason`, `reasonLabel="เหตุผลการปฏิเสธ"`) blocks confirm until a reason is typed. `busy` prevents double-submit; on failure the dialog stays open, the typed reason is preserved, and an error toast is shown.
- `frontend/src/hooks/useHrData.js` — `reviewProfileRequest(id, status, reviewerNote)` forwards the note; payload is `{ status, reviewerNote }` on reject and bare `{ status }` on approve (no `undefined` noise). Existing toast/invalidation behaviour unchanged.
- `frontend/src/api/mockApi.js` — `profileRequests.update` now persists `payload.reviewerNote` (only when present), keeping the mock a faithful stand-in for `ProfileRequestService.update`.
- `frontend/src/App.jsx` — passes `showToast` to `ProfileRequestsPage` (one line).
- `frontend/src/features/profileRequests/ProfileRequestsPage.test.jsx` **(new)** — 6 tests: reject opens dialog without mutating; confirm blocked until reason entered; confirm passes the **trimmed** reason; approve opens dialog and confirms; cancel aborts; **failure path** keeps dialog open, toasts the error, and preserves the typed reason.
- `frontend/src/api/mockApi.profileRequests.test.js` **(new)** — asserts `reviewerNote` round-trips on reject and is absent on approve, guarding the mock-mirrors-Java rule.

## Commands Run
```bash
cd frontend && npm run lint    # ✅ 0 errors, 4 pre-existing warnings (Attendance/Commission/Payroll — untouched files)
cd frontend && npm test        # ✅ 29 files, 130 tests passed (was 123 before this branch)
cd frontend && npm run build   # ✅ built in 152ms
```
Backend not built — **no Java/SQL was touched**, so `./mvnw verify` was not run.

## Verification (beyond tests)
Driven against the running mock (`VITE_USE_MOCKS=true`, port 5200) with Playwright as the `hr` persona — **13/13 checks passed**: reject opens a dialog without mutating; confirm disabled while the reason is empty and enabled once typed; cancel closes without mutating; confirming reject applies the mutation and the row flips to rejected; the approve dialog renders the real consequence (`อีเมล · weerapong.s@glr.co.th → w.new@glr.co.th`); approve applies.

**Mutation-tested** to prove the tests have teeth:
- Removing the `reviewerNote` persistence line → mock test fails (`expected undefined to be 'ข้อมูลไม่ตรง'`).
- Reverting the reject button to call `onReview` directly → 5 of 6 page tests fail.

## Known Risks
- **Mock authz is not authoritative** (CLAUDE.md). The dialog gating is pure frontend; the real 409 "Profile request has already been reviewed" path was exercised only via a mocked rejection, not against the live Spring service. Worth one manual check against a real backend before release.
- `ProfileRequestsPage` gained 6 inline `style={{}}` blocks in the confirm messages. This **matches the existing sibling pattern exactly** (OvertimePage has 8, LeavePage 8) and uses design tokens (`var(--color-border)`), not the hardcoded hex that finding UX-18 criticises — deviating would have created a third pattern. Flagged as a follow-up, not a defect.
- No visual regression snapshot exists for this page.

## Follow-ups (not in this branch)
- Extract the repeated confirm-message "label → value" markup from OvertimePage / LeavePage / ProfileRequestsPage into a shared Tailwind component, retiring ~22 inline styles at once.
- Surface `reviewerNote` in the UI — it is now persisted but never displayed back to the employee whose request was rejected, so the reason is currently write-only.
- **UX-34 (P1)** is the next-most-severe audit finding: "ยืนยันชำระครบ (Final Payment)" settles a deal (฿132,500 → ฿0 outstanding) on one click with no confirmation, no receipt fields and no undo. Same class of defect as UX-02, higher blast radius.

## Exact Next Prompt
> Read `docs/agent-handoffs/78_fix-ux-02-profile-request-confirm.md` and `docs/ux-ui-audit/UX_UI_AUDIT_REPORT.html` (finding UX-34). On a new branch off `main` named `fix/ux-34-final-payment-confirm`, gate the "ยืนยันชำระครบ (Final Payment)" action in `frontend/src/features/tickets/TicketDetailPage.jsx` behind the shared `ConfirmDialog`, stating the amount being settled and the consequence — mirroring the OT approve dialog and the UX-02 fix. First verify whether the backend `TicketService` already accepts receipt details for this transition (as `reviewerNote` already existed for profile requests) before changing any payload shape; do not change the API contract as a side effect. Add tests, run `npm run lint && npm test && npm run build`, drive the flow as the `account` persona on a deal with an outstanding balance, and update this handoff folder.
