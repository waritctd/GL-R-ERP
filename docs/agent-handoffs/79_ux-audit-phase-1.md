# Agent Handoff

## Task
Execute **Phase 1 (Phase A — Blockers & production risk)** of the UX/UI audit remediation roadmap (`docs/ux-ui-audit/UX_UI_AUDIT_REPORT.html`), on a dedicated integration branch, using a Sonnet-implements / Opus-reviews loop. Merge the fixes together; **do not push to main**. Update the audit deliverable to reflect what was fixed.

## Branch
`fix/ux-audit-phase-1` (integration branch, off `main`)
Feeder branch merged in: `fix/ux-02-profile-request-confirm`

## Base Commit
`2371b80` (main tip)

## Current Commits
```
bd8195c fix(tickets): gate Final Payment behind a confirmation stating the amount (UX-34)
8521d72 fix(auth,routing): localize login errors + guard /ceo-settings (UX-06, UX-19)
(merge commit linearised during the Git LFS history rewrite)
0e4d2f5 fix(profile-requests): gate approve/reject behind ConfirmDialog (UX-02)
```
**Not pushed. Not merged to main.**

## Agent / Model Used
Claude Sonnet implemented each fix; Claude Opus scoped, reviewed, and independently verified every claim (re-ran all commands, drove the live app, mutation-tested each new test).

## Scope

### In Scope — delivered
| ID | Sev | Fix |
|---|---|---|
| UX-02 | P1 | Profile-request approve/reject gated behind `ConfirmDialog`; reject requires a reason, persisted via the existing `reviewerNote` contract |
| UX-34 | P1 | Final Payment gated behind a confirmation stating the real outstanding amount and irreversibility |
| UX-19 | P2 | `/ceo-settings` route guard (was loadable by any authenticated user via URL) |
| UX-06 | P2 | Login errors localized to Thai; mock 401s collapsed to match `AuthService` |

### Withdrawn during this phase
- **UX-01 (was P1)** — **false positive, retracted.** Reported as "~1,300px of empty space" on the deal cockpit from `fullPage:true` screenshots. This app scrolls an inner `.content-scroll` container, not the document, so `document.scrollHeight` does not represent content height and full-page capture pads the remainder as blank. Driving the real scroller to its bottom shows content filling it completely (mobile 2,847/778; desktop 2,570/834; no positive gap), confirmed visually. No product defect existed. The report now carries a correction note in §3 and a Withdrawn group in §7.

### Out of Scope
- **UX-03** (P1, effort L — accessible inline validation across the sales stack) stays in Phase B per the documented roadmap.
- `/catalog` and `/attendance` route guards — restricting them **changes who can access them**, a permissions/product decision tracked as UX-20; CLAUDE.md forbids permission changes as a side effect.
- Any Java or SQL. No backend file was touched in this phase.
- Receipt fields on Final Payment — the endpoint takes no request body, so adding them would be an API contract change.

## Key Findings That Shaped Scope
1. **UX-02's reason was already in-contract.** `UpdateProfileRequestRequest` already carried `reviewerNote`, `ProfileRequestService` already persisted it to `reviewer_note` (V2) and audited it — only the frontend and the mock were dropping it. Turned a potential API change into a frontend fix.
2. **UX-34 could not take receipt details.** `TicketService.confirmFinalPayment(long, UserPrincipal)` and `@PostMapping("/{id}/final-payment")` accept no body; the server auto-records a BALANCE receipt for the outstanding amount. So the fix is a confirmation, not a new form.
3. **The mock leaked user-enumeration.** `mockApi` returned distinct 401s ("Invalid email or inactive user" vs "Invalid password") where `AuthService` deliberately collapses to one. Fixed as part of UX-06 to keep the mock a faithful stand-in.

## Files Changed
- `frontend/src/features/profileRequests/ProfileRequestsPage.jsx` — ConfirmDialog gating for approve/reject
- `frontend/src/features/tickets/TicketDetailPage.jsx` — ConfirmDialog for Final Payment, stating `summary.amountOutstanding`
- `frontend/src/app/permissions.js` — `PATH_GUARDS` entry for `/ceo-settings` (`role === 'ceo'`)
- `frontend/src/App.jsx` — Thai login-error mapping by HTTP status; `/ceo-settings` moved inside `RequireAccess`; `showToast` passed to ProfileRequestsPage
- `frontend/src/hooks/useHrData.js` — `reviewProfileRequest(id, status, reviewerNote)`
- `frontend/src/api/mockApi.js` — persist `reviewerNote`; collapse the two 401 auth messages
- Tests: `ProfileRequestsPage.test.jsx` (new, 6), `mockApi.profileRequests.test.js` (new), `TicketDetailPage.test.jsx` (+1), `permissions.test.js` (+1), `App.test.jsx` (+2)
- `docs/ux-ui-audit/**` — findings data + regenerated HTML (remediation section, FIXED chips, UX-01 withdrawal, correction note)

## Commands Run
```bash
cd frontend && npm run lint    # ✅ 0 errors, 4 pre-existing warnings (Attendance/Commission/Payroll — untouched)
cd frontend && npm test        # ✅ 29 files, 134 tests (baseline before this branch: 123)
cd frontend && npm run build   # ✅ ~150ms
```
Backend not built — no Java/SQL touched.

## Verification (beyond unit tests)
Each fix was driven in the running mock app with Playwright, not just unit-tested:
- **UX-02** — 13/13 checks: dialog intercepts, confirm disabled until reason typed, cancel aborts without mutating, reject applies, approve dialog renders the real `old → new` value.
- **UX-06 / UX-19** — 12/12 checks: bad login shows `อีเมลหรือรหัสผ่านไม่ถูกต้อง` with no English leak; all **6 non-CEO roles redirect off `/ceo-settings`**; CEO retains access; `/catalog` + `/attendance` confirmed unchanged.
- **UX-34** — 9/9 checks: click opens dialog instead of firing; dialog shows the real ฿66,250 outstanding; cancel provably does not settle; confirm settles to ฿0.

**Mutation-tested** (implementation reverted to confirm tests fail):
- Drop `reviewerNote` persistence → mock test fails.
- Revert profile-request buttons to direct calls → 5 of 6 tests fail.
- Revert Final Payment button to direct call → its test fails.

## Known Risks
- **Mock authz is not authoritative** (CLAUDE.md). UX-19's guard is a front-end redirect; the Spring endpoint's own authorization for CEO pricing config must be confirmed separately before treating this as a security fix.
- UX-02's 409 race ("Profile request has already been reviewed") was exercised only via a mocked rejection, never against the live service.
- `docs/ux-ui-audit/` is **18 MB / 188 PNGs**. Committing it puts that in history permanently. Consider stripping screenshots, using Git LFS, or keeping the audit out of the repo before merging to main.
- `reviewerNote` is now persisted but never displayed back to the employee — currently write-only data.

## Follow-ups
- **UX-03** (P1, L) — Phase B: migrate sales-stack forms to FormField + zod for accessible inline errors.
- **UX-35** (P2, S) — deposit-notice CTA enabled on a dormant deal, fails only after click with internal jargon. Pairs naturally with UX-34.
- **UX-36** (P2, M) — Thai type metrics (14px floor, ~1.75 leading, rem units).
- Surface `reviewerNote` in the UI.
- Extract the repeated confirm-message markup from OvertimePage / LeavePage / ProfileRequestsPage / TicketDetailPage into a shared Tailwind component (~22 inline styles).

## Exact Next Prompt
> Read `docs/agent-handoffs/79_ux-audit-phase-1.md` and `docs/ux-ui-audit/UX_UI_AUDIT_REPORT.html` (§12 roadmap, Phase B). On branch `fix/ux-audit-phase-1` (or a new branch off it), execute **Phase B — core workflow usability**, starting with **UX-03**: migrate the sales-stack forms (TicketCreateModal, TicketDetailPage inline forms, DepositNoticePage, ProductFormModal, CeoSettings editor) to `FormField` + zod so each invalid field shows an inline error wired to `aria-invalid`/`aria-describedby`, with the first error scrolled into view — matching the HR create-employee modal. This is effort L: do it in reviewable slices, one surface per commit, not one blind rewrite. Use the Sonnet-implements / Opus-reviews loop, drive each surface in the live mock, mutation-test the new tests, run `npm run lint && npm test && npm run build`, and update this handoff folder plus the audit HTML remediation section. Do not push to main.
