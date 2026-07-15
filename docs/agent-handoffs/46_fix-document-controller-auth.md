# Agent Handoff

## Task
Restore the missing `sessions.requireUser(session)` check on the two `DocumentController` read endpoints
that the sales module split dropped — `listByTicket` (`GET /api/tickets/{ticketId}/documents`) and
`getDoc` (`GET /api/documents/{docId}`). This is the follow-up filed under "Things Not Finished" in
`45_fix-main-failing-tests.md`.

## Branch
`claude/mystifying-northcutt-7e76f1` (worktree, branched off `main`)

## Base Commit
`0c2ec57` (fix: repair two failing backend tests and unblock verify (#181))

## Current Commit
Uncommitted — working tree only. Not committed or pushed (no instruction to do so).

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- Adding `HttpSession` + `sessions.requireUser(session)` to `listByTicket` and `getDoc`.
- Regression tests for both, following the `noteTemplatesRequiresAuthentication` pattern.

### Out of Scope
- Any sales/CRM feature work (stack is frozen). No behaviour change beyond the auth gate.
- Business logic — untouched.
- The remaining sales/document coverage debt (see Things Not Finished).

## Files Changed
- `backend/src/main/java/th/co/glr/hr/document/DocumentController.java`: `listByTicket()` and `getDoc()`
  each now take an `HttpSession` and call `sessions.requireUser(session)`. Matches the sibling methods on
  the same controller and the equivalent endpoints on `DepositNoticeController`.
- `backend/src/test/java/th/co/glr/hr/document/DocumentControllerTest.java`: added four tests —
  `listByTicketRequiresAuthentication`, `listByTicketReturnsDocumentsForAuthenticatedUser`,
  `getDocRequiresAuthentication`, `getDocReturnsDocumentForAuthenticatedUser` — plus a `doc(id, ticketId)`
  `DocumentDto` fixture helper.

## Commands Run
```bash
cd backend
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./mvnw -B -o test -Dtest='DocumentControllerTest' -DfailIfNoSpecifiedTests=false
# guard verification against the unpatched controller (see below)
./mvnw -B -o test -Dtest='DocumentControllerTest#listByTicketRequiresAuthentication+getDocRequiresAuthentication' -DfailIfNoSpecifiedTests=false
./mvnw -B -o clean verify
```

## Test / Build Results
- Backend `mvnw -B -o clean verify`: **pass** — 357 tests, 0 failures, 0 errors, 0 skipped (up from 353
  on `main`; +4 new). `jacoco-check` passes — the new tests raise coverage, so the 0.50 floor holds.
  Integration tests **did** run (Testcontainers, e.g. `EmployeeRepositoryIntegrationTest`).
- Frontend: **not run** — no frontend files changed.
- Lint: n/a (backend has no separate lint step; `verify` is the gate).

## The guard tests are not vacuous (verified)
Ran both new `RequiresAuthentication` tests against the **unpatched** controller to confirm they fail
without the fix:

| endpoint | unpatched | patched |
|---|---|---|
| `GET /api/tickets/1/documents` | **200** | 401 |
| `GET /api/documents/10` | 500 | 401 |

`listByTicket` genuinely served the document list to an anonymous caller at the controller layer.
`getDoc`'s 500 is an artifact of the mock returning `null` into `Map.of` (NPE) rather than a real gate —
with a real service it would have returned the document. Both are 401 now.

## Decisions Made
- Added happy-path tests alongside each guard, mirroring how `45` paired
  `noteTemplatesRequiresAuthentication` with `noteTemplatesReturnsTemplatesForAuthenticatedUser`.
  This keeps the guard honest: a 401 test alone passes even if the endpoint is broken outright.
- Kept the `doc()` fixture minimal (nulls for the money fields) — it exists to prove the auth gate and
  serialization, not to assert pricing. No business logic is exercised.

## Assumptions
- The frontend calls these endpoints from authenticated pages only, so requiring a session does not
  break it. Same assumption `45` made for note-templates; the sales UI is frozen/flag-hidden and was not
  exercised in a browser.

## Known Risks
- **Low.** `SecurityConfig` is already default-deny (`anyRequest().authenticated()`), so this is
  defense-in-depth and consistency, not a live vulnerability fix. No production behaviour change.

## Incident during this session (worth knowing)
A `git stash pop` fired unintentionally: a `cd backend && git stash push … && ./mvnw …` chain
short-circuited (the shell was already in `backend`, so `cd` failed), but the trailing `; git stash pop`
still ran and popped an **unrelated pre-existing stash** into the working tree — three untracked
`tools/uat-tests/reports/*` files (a real UAT run from 2026-07-09, 79 tests). The stash was dropped by
the pop. Content was **not lost**: the files were re-stashed as
`stash@{0}: recovered: uat-tests reports (accidentally popped by Claude 2026-07-15)`.
**Those reports are still only in that stash — they have never been committed and are not gitignored.**
Someone should decide whether to commit or discard them.

## Things Not Finished
- Coverage debt on the sales/document classes remains (the 0.51 → 0.50 floor drop from `45` stands).
- Whole-controller sweep: only the endpoints named in the follow-up were checked. Other sales
  controllers were not audited for the same module-split gap.

## Recommended Next Agent
Claude Opus review.

## Exact Next Prompt
```
Review branch `claude/mystifying-northcutt-7e76f1` (off main @ 0c2ec57). It restores
`sessions.requireUser(session)` on DocumentController.listByTicket and getDoc, and adds four tests to
DocumentControllerTest. See docs/agent-handoffs/46_fix-document-controller-auth.md.

Focus the review on:
1. Whether the two endpoints now match DepositNoticeController's equivalents exactly (no drift).
2. Whether the new tests are real guards — confirm independently that reverting the controller change
   makes listByTicketRequiresAuthentication fail with 200.
3. Whether any OTHER sales controller lost a requireUser in the same module split (5c3d8c4 / 7dcccbc).
   Report findings only; do not fix — the sales stack is frozen and that would be a separate branch.

Do not expand scope into the frozen sales stack.
```
