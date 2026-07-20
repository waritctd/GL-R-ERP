# Agent Handoff

## Task
Implement SLICE 2 of the special-money (welfare) requests feature on top of the already-built,
already-green SLICE 1 (schema V66 + `SpecialMoneyType`/`CapRule`/`ClaimWindow`/`EligibilityRule`/
`EmployeeEligibilitySnapshot`/`UsageSnapshot`/`PolicyAmounts`/`PolicyDecision`/
`SubmitSpecialMoneyRequest`/`SpecialMoneyPolicyEvaluator`, 598 tests green). Slice 2 adds the
repository, service, controller, and full test coverage (unit + real-Postgres repository +
real-Postgres authz-scope evidence + mutation checks) so the feature is submit/review/list/cancel
-able end to end, while staying out of payroll wiring, attachments, and the frontend.

## Branch
`feat/special-money-requests`

## Base Commit
`8ae42e3` (origin/main) — slice 1's files were already present as untracked working-tree changes
when this session started; nothing was committed yet on this branch.

## Current Commit
Not committed (per instructions: do not commit, do not push).

## Agent / Model Used
Claude Sonnet 5

## Scope

### In Scope
- `SpecialMoneyRepository` (NamedParameterJdbcTemplate, no JPA), `SpecialMoneyService`,
  `SpecialMoneyController`, all supporting DTOs/records.
- `AppProperties.SpecialMoney.payrollCutoffDay` (default 25) + `application.yml` wiring.
- Unit tests (Mockito), real-Postgres repository integration test, real-Postgres authz-scope
  integration test (the CLAUDE.md-required evidence), and the three mandatory mutation checks.

### Out of Scope (untouched, per the task's hard limits)
- `backend/src/main/java/th/co/glr/hr/payroll/` — no payroll wiring at all.
- Any frontend file.
- Attachment upload/download endpoints (later slice).

## Files Changed

### New (slice 2)
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyRepository.java` — repository:
  `employeeExists`, `findEmployeeAccess`, `findEligibility`, `findUsage`, `findPolicyAmounts`,
  `create`, `findById`, `findRequests`, `managerApprove`, `ceoApprove`, `reject`, `ceoReject`,
  `cancel`, `payrollMonthProcessed`, plus `findEmployeeOptions`, `findExcludedProvinces`,
  `findCeoApproverEmployeeIds` (needed to fully mirror `OvertimeRepository`'s picker/notification
  fan-out, not explicitly named in the task list but required by the endpoints/notifications it did
  name).
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyService.java` — submit/approve
  (manager→CEO dispatch)/reject/cancel/list/usage/employeeOptions, CEO cap re-check +
  cap-override-reason gate, 25th-cutoff payroll-month assignment with roll-forward past a
  PROCESSED month.
- `backend/src/main/java/th/co/glr/hr/specialmoney/SpecialMoneyController.java` — `/api/special-money`:
  `GET /`, `POST /`, `GET /employees`, `GET /usage`, `POST /{id}/approve`, `POST /{id}/reject`,
  `POST /{id}/cancel`, `GET /types`.
- `SpecialMoneyEmployeeAccess.java`, `SpecialMoneyEmployeeOption.java`, `SpecialMoneyFilter.java`,
  `SpecialMoneyRequestDto.java`, `SpecialMoneyResponses.java`, `SpecialMoneyStatus.java`,
  `SpecialMoneyTypeOption.java`, `SpecialMoneyUsageDto.java`, `ReviewSpecialMoneyRequest.java`,
  `SubmitSpecialMoneyHttpRequest.java` (the HTTP-layer wrapper that carries `requestType` alongside
  slice 1's `SubmitSpecialMoneyRequest`, which deliberately does not carry it — see Decisions Made).
- `backend/src/test/java/th/co/glr/hr/specialmoney/SpecialMoneyServiceTest.java` (19 tests, Mockito).
- `backend/src/test/java/th/co/glr/hr/specialmoney/SpecialMoneyRepositoryIntegrationTest.java`
  (4 tests, real Postgres via Testcontainers).
- `backend/src/test/java/th/co/glr/hr/specialmoney/SpecialMoneyScopeIntegrationTest.java`
  (10 tests, real Postgres, real service+repository+evaluator — THE AUTHZ EVIDENCE).

### Modified
- `backend/src/main/java/th/co/glr/hr/config/AppProperties.java` — added `SpecialMoney` inner
  class + `getSpecialMoney()`.
- `backend/src/main/resources/application.yml` — added
  `app.special-money.payroll-cutoff-day: ${APP_SPECIAL_MONEY_PAYROLL_CUTOFF_DAY:25}`.

### Untouched (slice 1, already present, reused as-is)
- `V66__special_money_request_schema.sql`, `SpecialMoneyType`, `SpecialMoneyBucket`, `CapRule`,
  `ClaimWindow`, `EligibilityRule`, `EmployeeEligibilitySnapshot`, `UsageSnapshot`, `PolicyAmounts`,
  `PolicyDecision`, `SubmitSpecialMoneyRequest`, `SpecialMoneyPolicyEvaluator`,
  `SpecialMoneyPolicyEvaluatorTest`.

## Commands Run
```bash
cd backend && ./mvnw -q -o compile
cd backend && ./mvnw -o test -Dtest=SpecialMoneyServiceTest
cd backend && ./mvnw -o test -Dtest=SpecialMoneyRepositoryIntegrationTest
cd backend && ./mvnw -o test -Dtest=SpecialMoneyScopeIntegrationTest
cd backend && ./mvnw -o test -Dtest=SpecialMoneyServiceTest,SpecialMoneyScopeIntegrationTest,SpecialMoneyRepositoryIntegrationTest   # x3, for each mutation check
cd backend && ./mvnw -B clean verify
```

## Test / Build Results
- Frontend build: not run (out of scope — no frontend file touched).
- Backend tests: **PASS — 631 tests, 0 failures, 0 errors, 0 skipped** (`./mvnw -B clean verify`).
  Baseline was 598; this branch adds 33 (19 `SpecialMoneyServiceTest` + 4
  `SpecialMoneyRepositoryIntegrationTest` + 10 `SpecialMoneyScopeIntegrationTest`).
  **Integration tests RAN** (Docker was available; Testcontainers Postgres started, Flyway
  migrated to V66, `AbstractPostgresIntegrationTest`-based classes executed for real — confirmed by
  the `Successfully applied 54 migrations ... now at version v66` log line on every run).
- Lint: not applicable (backend-only change; no `checkstyle`/`spotless` step beyond what `verify`
  already runs, which passed as part of the build).

## Authz Evidence
**Verified against the real Java service:**
`backend/src/test/java/th/co/glr/hr/specialmoney/SpecialMoneyScopeIntegrationTest.java` — real
Postgres (Testcontainers), real `SpecialMoneyService` + real `SpecialMoneyRepository` + real
`SpecialMoneyPolicyEvaluator`; only `AuditService` and `NotificationService` are stubbed (they do
not participate in the authorization decision). 10 wrong-way-round cases, each asserting the
database itself (a re-read of `status`/`approved_amount`, or a row count) rather than only the
thrown status code:

1. `managerCannotListRequestsOutsideOwnDivision` — asserts zero rows returned, not "contains own".
2. `managerCannotApproveRequestOfOutOfDivisionEmployee` — 403 AND row still `SUBMITTED`,
   `approved_amount` still `NULL`.
3. `employeeCannotSeeAnotherEmployeesRequest`.
4. `employeeCannotSubmitOnBehalfOfNonReport` — 403 AND row count for the target employee unchanged.
5. `hrCannotApprove` — pins issue #199's exact shape; 403 AND status unchanged. (This test caught a
   real fixture bug during development: the first draft gave the `hr` principal the sales manager's
   own `employeeId`, so `managesEmployee` accidentally succeeded via the *manager* relation, not an
   HR carve-out — fixed by giving HR its own unrelated employee record with no reports and no
   division-manager flag, so the test cannot pass by accident.)
6. `hrCannotSubmitOnBehalfOfArbitraryEmployee` — 403 AND row count unchanged.
7. `managerCannotCeoApproveOwnManagerApprovedRequest` — 403 AND status still `MANAGER_APPROVED`.
8. `ceoCannotApproveRequestStillInSubmittedState` — the CEO must not skip the manager stage.
9. `employeeCannotReadAnotherEmployeesUsageQuota`.
10. `employeeCannotCancelAnotherEmployeesRequest`.

**Mutation checks — all three run, all three caught by the intended tests, all reverted to an empty
diff (confirmed via `git diff` + `grep -n "MUTATION-CHECK"`, both clean):**

1. **`requireManager` disabled** (made a no-op): 4 tests went red —
   `SpecialMoneyServiceTest.hrCannotApproveSubmittedRequest`,
   `SpecialMoneyServiceTest.nonManagerCannotApproveSubmittedRequest`,
   `SpecialMoneyScopeIntegrationTest.hrCannotApprove`,
   `SpecialMoneyScopeIntegrationTest.managerCannotApproveRequestOfOutOfDivisionEmployee`.
   Nothing else failed.
2. **`requireCeo` disabled** (made a no-op): 3 tests went red —
   `SpecialMoneyServiceTest.managerCannotCeoApproveOwnManagerApprovedRequest`,
   `SpecialMoneyServiceTest.managerCannotCeoRejectManagerApprovedRequest`,
   `SpecialMoneyScopeIntegrationTest.managerCannotCeoApproveOwnManagerApprovedRequest`.
   (`ceoCannotApproveRequestStillInSubmittedState` did NOT fail — correctly so: that case routes
   through `requireManager` on the SUBMITTED→managerApprove branch, not `requireCeo`, since a CEO is
   not the employee's manager either. This was checked, not assumed.)
3. **`list()` division filter disabled** (the `managerEmployeeId`-scoping `AND (...)` clause in
   `SpecialMoneyRepository.findRequests` short-circuited off): 2 tests went red —
   `SpecialMoneyScopeIntegrationTest.managerCannotListRequestsOutsideOwnDivision`,
   `SpecialMoneyScopeIntegrationTest.employeeCannotSeeAnotherEmployeesRequest`.
   Nothing else failed.

No mutation left a test suite fully green — every guard is proven testable.

## Decisions Made
- **`SubmitSpecialMoneyRequest` (slice 1) does not carry a `requestType` field** — by design,
  `SpecialMoneyPolicyEvaluator.evaluate(SpecialMoneyType type, ...)` takes the type as a separate
  argument. Rather than editing the slice-1 record, slice 2 adds
  `SubmitSpecialMoneyHttpRequest(String requestType, ...)` at the HTTP layer, which converts to the
  domain record via `.toDomain()`. `SpecialMoneyService.submit(String requestTypeRaw, ...)` and
  `SpecialMoneyController` both carry the type alongside the request rather than folding it in.
- **CEO cap re-check does not gate on the re-run evaluator's `violations()`, only on
  `eligibleAmount()`.** The MANAGER_APPROVED request being approved is itself counted in
  `findUsage`'s lifetime-count (SUBMITTED/MANAGER_APPROVED/APPROVED all count), so gating on
  violations would make the once-per-lifetime AID guard trip against itself on every CEO approval.
  Comparing only `approvedAmount` vs. `eligibleAmount` (the policy cap) avoids that self-reference
  and matches the task's literal wording ("If it exceeds the policy cap...").
- **`findUsage`'s lifetime count is intentionally NOT year-scoped** — it backs the once-per-lifetime
  AID_WEDDING/AID_ORDINATION gate, which must see every prior claim regardless of year. Confirmed by
  `SpecialMoneyRepositoryIntegrationTest.findUsageSumsOnlyApprovedRowsWithinTheRequestedCalendarYear`
  (3 rows counted lifetime vs. 1 in the calendar-year amount sum).
- **`cancel()` has no manager gate** — per the task spec ("the requester or the employee may cancel,
  only while SUBMITTED"), unlike `OvertimeService.cancel` which also lets a manager cancel through
  MANAGER_APPROVED/APPROVED. Confirmed by `employeeCannotCancelAnotherEmployeesRequest`.
- **`GET /employees` is a real employee picker**, not a placeholder — mirrors
  `OvertimeController`/`OvertimeRepository`'s `findEmployeeOptions`/`OvertimeEmployeeOption` with
  local, decoupled `SpecialMoneyEmployeeOption`/`findEmployeeOptions`. The task listed the endpoint
  without specifying its shape; a first draft stubbed it via `list()`, which was replaced with a
  proper implementation before finishing.
- **`SpecialMoneyEmployeeAccess`, not a reused `OvertimeEmployeeAccess`** — per the task's explicit
  instruction to keep the two modules decoupled even though the shape is identical.
- **Repository additions beyond the task's literal method list**: `findEmployeeOptions`,
  `findExcludedProvinces`, `findCeoApproverEmployeeIds`. All three are load-bearing for endpoints
  and notification fan-out the task DID name (`GET /employees`, the submit-time evaluator call, and
  `notifyManagerApproved`'s CEO fan-out mirroring `OvertimeService`) — flagged here rather than
  silently added.

## Assumptions
- `hr.payroll_period.status = 'PROCESSED'` is the correct marker for "this month's payroll has
  already been written" — confirmed by grepping `PayrollRepository.java`, which sets exactly that
  status string on the same table/column the task named.
- Policy-amount lookups (`findPolicyAmounts`) assume the business invariant that at most one
  `special_money_policy` row per `(request_type, policy_key)` is effective on any given date — the
  schema does not enforce non-overlapping `effective_from`/`effective_to` ranges, only uniqueness of
  `(request_type, policy_key, effective_from)`. If two effective rows for the same key ever
  overlap, `findPolicyAmounts` picks whichever Postgres returns last (no `ORDER BY`); this matches
  how the V66 seed data is actually shaped (one live row per key at a time) but is worth a note for
  whoever builds the CEO's policy-editing UI in a later slice.

## Known Risks
- No payroll integration exists yet (explicitly out of scope) — `approved_amount`/`payroll_month`
  are written but nothing reads them into a payslip. This mirrors the slice-1 migration's own
  comment.
- No attachment upload wiring (explicitly out of scope) — `evidenceRequired()` types can be
  submitted today without any evidence actually being attached; `hr.special_money_request_attachment`
  exists but nothing writes to it yet.
- `UNIFORM_PREPROBATION_KIT` stays effectively disabled until a human fills in the real
  `sales_support_department_code` (slice-1 concern, unchanged here).
- No frontend exists yet for this feature; only the API surface is implemented and tested.

## Things Not Finished
- Frontend UI (explicitly out of scope for this slice).
- Attachment upload/download endpoints (explicitly out of scope for this slice — later slice).
- Payroll wiring that consumes `approved_amount`/`payroll_bucket`/`payroll_month` (gated on an
  external sign-off per the V66 migration's own comment).

## Recommended Next Agent
Claude Opus review, then (once approved) a slice-3 implementation agent for either the frontend
submission/review UI or the attachment upload endpoints — whichever the product owner prioritizes
next.

## Exact Next Prompt
```
Review the special-money-requests slice 2 backend implementation in
.claude/worktrees/special-money (branch feat/special-money-requests) against the handoff at
docs/agent-handoffs/90_feat-special-money-requests.md. Confirm: (1) the CEO cap re-check logic in
SpecialMoneyService.ceoApprove is sound (it deliberately checks eligibleAmount() rather than
violations() — verify that reasoning holds), (2) SpecialMoneyScopeIntegrationTest's 10 cases give
real coverage of every gate SpecialMoneyService exposes, (3) nothing in
SpecialMoneyRepository/SpecialMoneyService reaches into th.co.glr.hr.payroll or any frontend file.
If clean, hand off to implement slice 3 (attachment upload/download endpoints OR the frontend
submission/review UI, product owner's call) on a fresh branch off main.
```
