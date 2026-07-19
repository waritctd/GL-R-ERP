# Agent Handoff

## Task
Execute **Phase B (core workflow usability)** of the UX/UI audit remediation roadmap, on a branch off Phase A, using the Sonnet-implements / Opus-reviews loop. **Do not push to main.** Keep the audit deliverable updated.

## Branch
`fix/ux-audit-phase-2` (off `fix/ux-audit-phase-1`, which is off `main`)

## Base Commit
`44abb98` (tip of `fix/ux-audit-phase-1`)

## Agent / Model Used
Claude Sonnet implemented each slice; Claude Opus scoped every slice, reviewed every claim, re-ran all commands, drove each fix in the live mock, and mutation-tested every new guard.

## Status
**Phase B complete. Not pushed. `main` untouched at `2371b80`.**

## Delivered
| ID | Sev | Fix | Commit |
|---|---|---|---|
| UX-09 | P2 | `canViewSensitiveEmployeeData` was defined nowhere, hiding salary + the PDPA tab from everyone incl. HR | `2495c40` |
| UX-07 | P2 | `window.confirm` → branded ConfirmDialog; zero native dialogs remain | `2495c40` |
| UX-27 | P3 | `FormField` auto-injects `aria-invalid` / `aria-describedby` / `aria-required` | `7faf31c` |
| UX-12 | P3 | Required markers render before submit, with `aria-required` | `7faf31c` |
| UX-08 | P2 | CEO price-config editor rebuilt on the shared `Modal` (was a bespoke overlay with no dialog semantics) | `12833f7` |
| **UX-38** | P2 | **Found during remediation** — clearing a CEO pricing field silently persisted `0` | `12833f7` |
| **UX-03** | **P1** | **All 5 sales-stack surfaces migrated** — see below | 5 commits |
| UX-04 | P2 | Dashboard no longer offers stats/actions a role cannot reach | `a50b266` |

### UX-03 slices (the P1)
| Surface | Commit |
|---|---|
| ProductFormModal → react-hook-form + zod + FormField | `16c1cc4` |
| TicketCreateModal (headline case) — keyed per-field zod errors, scroll+focus | `4585aee` |
| CeoSettings FX override + 6 pricing fields | `12833f7` |
| DepositNoticePage — validated at **issue**, not save | `8fcb0d0` |
| TicketDetailPage — 4 modal validations | `e741db5` |
| TicketDetailPage — 4 inline page forms | `1406b63` |

**Zero toast-only client validations remain in the sales stack.**

## Key Decisions (and why)
1. **UX-27 was sequenced before UX-03 deliberately.** With `FormField` auto-associating aria first, every surface migrated onto it inherits correct accessibility instead of hand-wiring aria then refactoring.
2. **react-hook-form was NOT forced onto TicketCreateModal.** ~15 `useState` hooks, custom async `SearchSelect` controls with inline create-new sub-forms, and dynamic rows with derived qty↔sqm; a `Controller`+`useFieldArray` rewrite would be high-risk on the app's most valuable form. UX-03 is about error presentation and accessibility, not the form library. zod supplies the rules; existing state was left intact.
3. **DepositNoticePage validates at ISSUE, not SAVE.** It is a draft editor — blocking save would break incremental drafting. Issue is the irreversible step that advances the ticket's payment track. Validation also runs *before* the confirm dialog opens, so the user is never asked to confirm something that will fail.
4. **TicketDetailPage was split in two slices** (modals, then inline forms). 8 validation points in 2,396 lines is too much for one pass.
5. **UX-04 distinguishes pending-work from informational.** Pending counts a role cannot reach are hidden (false calls to action); headcount/attendance/unread stay as read-only context. Quick actions now filter through `canAccessPath` — the same predicate `RequireAccess` uses — so the dashboard structurally cannot offer a destination the router rejects.
6. **`FormField` was not usable on three surfaces** (TicketCreateModal, CeoSettings, DepositNoticePage): controls are custom selectors, pill-button groups, grid cells, or already inside an existing `<label>` where nesting FormField's own label would be invalid HTML. The same aria contract was applied by hand via `fieldErrorId()`. Documented deviation, not oversight.

## Bugs Found During Remediation
- **UX-38 (P2, fixed)** — `saveConfig` did `Number(field)` and `Number('') === 0`, so clearing a CEO pricing input persisted `0` with a success toast. A zeroed margin or freight would have under-priced every deal for that country, invisibly.
- **UX-37 (P3, still open, XS)** — the change-password schema has `newPassword: z.string()` with no `.min(1)` (unlike its two siblings) and every `superRefine` rule is guarded on `length > 0`, so an **empty** new password passes client validation. Proven by parsing the schema. The server rejects it, so no weak password is set, but the user hits a raw server error at the forced password gate.
- **A regression I shipped and then caught** — `fe8ede7`. Marking fields required (UX-12) exposed that the global `label { display: grid; gap: 7px }` makes the label text and the `*` span separate grid rows, so the asterisk rendered on its own line across 15 fields in 5 forms. Fixed by wrapping both in one span. Measured: label height 41px → 17px, asterisk offset 24px → 0px.

## Commands Run
```bash
cd frontend && npm run lint    # ✅ 0 errors, 4 pre-existing warnings (Attendance/Commission/Payroll — untouched)
cd frontend && npm test        # ✅ 34 files, 190 tests (was 123 before this work began)
cd frontend && npm run build   # ✅ ~170ms
```
Backend not built — **no Java or SQL was touched in either phase**.

## Verification Standard
Every slice was (a) code-reviewed against the finding's acceptance criteria, (b) re-run through lint/test/build by the reviewer, (c) **driven in the live mock app**, and (d) **mutation-tested** — the implementation reverted to confirm the new tests actually fail. That last step caught a test of mine that appeared to pass with its guard removed because the mutation pattern silently didn't match the restructured code.

## Known Risks
- **Mock authz is not authoritative** (CLAUDE.md). Everything was driven under `VITE_USE_MOCKS=true`. Phase A's UX-19 route guard in particular is a front-end redirect only — the Spring endpoint's own authorization for CEO pricing config still needs independent confirmation.
- **UX-38's fix is an intentional behaviour change**: a blank pricing field that previously saved as `0` is now rejected.
- **UX-03 changed validation timing** on TicketCreateModal (collect-all instead of first-failure) and added `noValidate` there — without it, native constraint validation intercepts empty required fields and the submit handler never runs.
- `docs/ux-ui-audit/` carries **~18 MB / 188 screenshots**. Decide on Git LFS or stripping before merging to main.
- Error markup placement is inconsistent between slices (DepositNotice puts errors after `</label>`, TicketDetail inside). Both verified accessible — the computed name is unaffected because `role="alert"` nodes are excluded from name computation — but it costs the `getByLabelText` idiom in tests.

## Follow-ups
- **UX-37** (XS) — add `.min(1)` to `newPassword` and re-check the `superRefine` guards.
- **UX-36** (M) — Thai type metrics: 14px floor, ~1.75 leading, rem units.
- Remaining open: UX-05, UX-10, UX-11, UX-16, UX-31, UX-32, UX-35 (all P2) plus P3/P4 polish — Phases C and D.
- Extract the repeated confirm-message markup across OvertimePage / LeavePage / ProfileRequestsPage / TicketDetailPage into a shared component.
- Normalise error-markup placement relative to `<label>` across the migrated surfaces.

## Exact Next Prompt
> Read `docs/agent-handoffs/80_ux-audit-phase-2.md` and `docs/ux-ui-audit/UX_UI_AUDIT_REPORT.html` (§12 roadmap, Phase C). On a new branch off `fix/ux-audit-phase-2`, execute **Phase C — mobile & accessibility readiness**: UX-05 (wide config/data tables clip their action column at ≤390px), UX-22 (pipeline phase labels truncate mid-word on mobile), UX-16 (timeline event dots and the current-step border convey status by colour alone) and UX-25 (collapsible section titles are not headings, breaking the document outline). Use the Sonnet-implements / Opus-reviews loop. For each fix: drive it in the live mock at 360/390/430px, mutation-test every new guard, run `npm run lint && npm test && npm run build`, and update the audit HTML remediation section plus this handoff folder. Do not push to main. Note that UX-01 was withdrawn as a false positive — do not attempt to "fix" the empty-scroll-region defect; read the correction note in §3 first.
