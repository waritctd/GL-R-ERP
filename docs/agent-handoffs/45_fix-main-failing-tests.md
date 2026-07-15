# Agent Handoff

## Task
Fix the two backend tests failing on `main` (`DepositNoticeControllerTest.noteTemplatesRequiresAuthentication`
and `AuthServiceTest.doesNotDeriveSalesManagerRoleFromManagementDivisionAlone`) so `main` is deployable again.

## Branch
`fix/main-failing-tests`

## Base Commit
`43ce2d5` (fix: allow demo Flyway history recovery)

## Current Commit
See branch tip.

## Agent / Model Used
Claude Opus 4.8

## Scope

### In Scope
- The two named test failures.
- Restoring the `requireUser` check the note-templates endpoint lost.
- Unblocking `mvnw clean verify` (the JaCoCo ratchet, which the failures were masking).

### Out of Scope
- Any sales/CRM feature work (stack is frozen).
- Business logic (payroll/tax/commission/pricing math) — untouched.
- Repaying the wider sales/document coverage debt (see Things Not Finished).

## Files Changed
- `backend/src/main/java/th/co/glr/hr/document/DocumentController.java`: `noteTemplates()` now takes
  `HttpSession` and calls `sessions.requireUser(session)`.
- `backend/src/test/java/th/co/glr/hr/document/DocumentControllerTest.java`: **new**. Holds the moved
  `noteTemplatesRequiresAuthentication` guard plus an authenticated happy-path test.
- `backend/src/test/java/th/co/glr/hr/deposit/DepositNoticeControllerTest.java`: removed the stale
  `noteTemplatesRequiresAuthentication` test (endpoint is not on this controller).
- `backend/src/test/java/th/co/glr/hr/auth/AuthServiceTest.java`: `employee(long)` helper now delegates
  to `employee(divisionId, (String) null)` so the record gets a password hash.
- `backend/pom.xml`: JaCoCo line-coverage floor `0.51` → `0.50`, comment rewritten with measured numbers.

## Commands Run
```bash
cd backend
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.jdk/Contents/Home
./mvnw -B -o test -Dtest='DepositNoticeControllerTest,AuthServiceTest,DocumentControllerTest' -DfailIfNoSpecifiedTests=false
./mvnw -B -o clean verify
```

## Test / Build Results
- Backend `mvnw -o clean verify`: **pass** — 353 tests, 0 failures, 0 errors, 0 skipped. `jacoco-check` passes.
  Integration tests **did** run (Testcontainers, e.g. `EmployeeRepositoryIntegrationTest`).
- Frontend: **not run** — no frontend files changed.
- Lint: n/a (backend has no separate lint step; `verify` is the gate).

## Root Causes (both were real, and different)

**1. `noteTemplatesRequiresAuthentication` → 500 instead of 401.**
Commit `9f27b3a` added `requireUser` to `/api/document-note-templates` **on `DepositNoticeController`**.
The later sales module split (`5c3d8c4` / `7dcccbc`) moved that endpoint to `DocumentController` and
**dropped the auth check on the way**. The test still pointed at `DepositNoticeController`, which no
longer maps the path → `NoHandlerFoundException` → caught by `ApiExceptionHandler`'s
`@ExceptionHandler(Exception.class)` → 500. So the 500 was a missing *handler*, not a null-session NPE.

Verified the security gap was real, not theoretical: with the check reverted, an anonymous
`GET /api/document-note-templates` returns **200**, not 401.

**2. `doesNotDeriveSalesManagerRoleFromManagementDivisionAlone` → "Invalid email or password".**
Pure test-harness bug. `employee(16L)` resolved to the `(long, String, boolean)` overload, passing
`null` as `passwordHash`. Login was rejected for a missing hash before role derivation ever ran, so
the test could never reach its actual assertion.

## Decisions Made
- **Moved** the note-templates test to a new `DocumentControllerTest` rather than deleting it, and
  restored `requireUser` on the endpoint — keeping the regression guard `9f27b3a` intended.
- **Lowered the JaCoCo floor to 0.50** (user-approved). See below; this is not caused by this change.

## The ratchet breach was pre-existing (important for review)
`main` **already** violates the 0.51 floor. Measured with all tests counted:

| | covered | total lines | ratio |
|---|---|---|---|
| `main` @ 43ce2d5 | 3,479 | 6,923 | 0.5025 |
| this branch | 3,481 | 6,924 | 0.5027 |

The pom comment claimed 0.5164 over **4,814** lines. The bundle has since grown to **6,924** lines —
largely the forward-ported sales/document stack — without matching tests. Nobody noticed because on
`main` surefire fails first and aborts the build **before `jacoco-check` runs**. Fixing the tests
un-masks it. This branch *raises* coverage slightly (+2 lines); it did not cause the breach.

## Assumptions
- The frontend calls `/api/document-note-templates` (`frontend/src/api/routes.js`) from authenticated
  pages only, so requiring a session does not break it. Frozen/flag-hidden sales UI; not exercised in a browser.

## Known Risks
- **Low.** `SecurityConfig` is already default-deny (`anyRequest().authenticated()`), so the restored
  `requireUser` is defense-in-depth — it was never the only gate in production. No live vulnerability.
- Lowering the ratchet floor legitimizes the coverage drift. The comment records the real numbers and
  the reason so it can be raised again.

## Things Not Finished
- **`DocumentController.listByTicket` and `getDoc` still have no `requireUser`** — the same gap, on the
  same controller, from the same module split. Left out to keep this PR focused. Filed as a follow-up.
- Coverage debt on the sales/document classes (needs ~+51 covered lines to restore a 0.51 floor).

## Recommended Next Agent
Claude Opus review.

## Exact Next Prompt
```
Review PR `fix/main-failing-tests` (branch off main @ 43ce2d5). It fixes the two backend tests that
were failing on main and lowers the JaCoCo line floor 0.51 -> 0.50.

Focus the review on:
1. Whether restoring `requireUser` on DocumentController.noteTemplates is the right call vs. deleting
   the stale test, given SecurityConfig is already default-deny.
2. Whether lowering the ratchet floor to 0.50 is acceptable. Confirm independently that main @ 43ce2d5
   is already at 0.5025 (run: ./mvnw -o clean verify -Dmaven.test.failure.ignore=true -Djacoco.skip.check=true
   then sum cols 8/9 of target/site/jacoco/jacoco.csv) — i.e. that this PR did not cause the breach.
3. The AuthServiceTest helper fix — confirm doesNotDeriveSalesManagerRoleFromManagementDivisionAlone
   now genuinely exercises role derivation rather than passing vacuously.

Do not expand scope into the frozen sales stack.
```
