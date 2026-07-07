# Agent Handoff

## Task
Extend the existing `AuditService` (already wired into employee/profile) to cover the mutating methods
in FOUR domains: leave, overtime, commission, payroll. Pure observability — no business logic,
control-flow, validation, or return-value changes. Inject `AuditService` into each service and add
`auditService.record(...)` calls after each mutation, reusing the already-fetched before/after DTOs.

## Branch
`backend/audit-log-coverage` (off `main`; already created and checked out)

## Base Commit
`30aa5a6` (main tip — includes P2-1 OpenAPI docs + P2-5 docs cleanup)

## Current Commit
Uncommitted (working tree on `backend/audit-log-coverage`, base `30aa5a6`) — left uncommitted per task rules.

## Agent / Model Used
Implementer: Claude Sonnet 5

## Scope

### In Scope
- Inject `AuditService` into `LeaveService`, `OvertimeService`, `CommissionService`, `PayrollService` constructors.
- Add `auditService.record(actor, ACTION, entity, entityId, before, after)` calls in each mutating method,
  reusing already-fetched/returned DTOs (no extra DB reads).
- Fix the 3 existing service tests broken by the new constructor parameter (`LeaveServiceTest`,
  `OvertimeServiceTest`, `PayrollServiceTest`) by passing `mock(AuditService.class)`.
- Add `verify(auditService).record(...)` assertions to at least one passing mutating-path test per
  domain, and create a new `CommissionServiceTest` (none existed before).

### Out of Scope
- No business logic / validation / control-flow / return-value changes anywhere.
- No changes to `AuditService` itself, to `EmployeeService`/`ProfileRequestService` (already wired), or
  to any controller.
- No changes to the existing `auditPayrollAccess(...)` read-audit logger calls in `PayrollService`.

## Files Changed
- `backend/src/main/java/th/co/glr/hr/leave/LeaveService.java` — added `AuditService` ctor param/field;
  added audit calls in `submit` (`SUBMIT_LEAVE_REQUEST`), `approve` (`APPROVE_LEAVE_REQUEST`), `reject`
  (`REJECT_LEAVE_REQUEST`), `cancel` (`CANCEL_LEAVE_REQUEST`), entity `"leave_request"`.
- `backend/src/main/java/th/co/glr/hr/overtime/OvertimeService.java` — added `AuditService` ctor
  param/field; added audit calls in `submit` (`SUBMIT_OVERTIME_REQUEST`), `approve`
  (`APPROVE_OVERTIME_REQUEST`), `reject` (`REJECT_OVERTIME_REQUEST`), `cancel`
  (`CANCEL_OVERTIME_REQUEST`), entity `"overtime_request"`.
- `backend/src/main/java/th/co/glr/hr/commission/CommissionService.java` — added `AuditService` ctor
  param/field; added audit calls in `submit` (`SUBMIT_COMMISSION`), `updateDeductions`
  (`UPDATE_COMMISSION_DEDUCTIONS`), `approve` (`APPROVE_COMMISSION`), `createClawback`
  (`CREATE_CLAWBACK`, entityId = the new clawback record's id, not the original), entity
  `"commission_record"`.
- `backend/src/main/java/th/co/glr/hr/payroll/PayrollService.java` — added `AuditService` ctor
  param/field; added one audit call in `process` (`PROCESS_PAYROLL`, entity `"payroll_period"`,
  entityId = saved period id, before = null, after = saved period dto). Existing
  `auditPayrollAccess(...)` logger calls (including the one already inside `process`) left untouched.
- `backend/src/test/java/th/co/glr/hr/leave/LeaveServiceTest.java` — added `mock(AuditService.class)`
  field + passed to constructor; added a `verify(auditService).record(...)` assertion to
  `hrCanApproveLeave`.
- `backend/src/test/java/th/co/glr/hr/overtime/OvertimeServiceTest.java` — added
  `mock(AuditService.class)` field + passed to constructor; added `verify(auditService).record(...)`
  assertions to `employeesCanSubmitOwnOvertime` (submit) and
  `approveCalculatesPayableMinutesFromAttendanceOverlap` (approve).
- `backend/src/test/java/th/co/glr/hr/payroll/PayrollServiceTest.java` — added `mock(AuditService.class)`
  field + passed to constructor; added new test `hrProcessingPayrollRecordsAuditTrail` that drives
  `process(...)` with an empty employee snapshot list end-to-end and verifies the `PROCESS_PAYROLL`
  audit call.
- `backend/src/test/java/th/co/glr/hr/commission/CommissionServiceTest.java` (new) — constructs
  `CommissionService` with mocked `CommissionRepository`/`CommissionCalculator`/`AuditService`; covers
  `submit` (`SUBMIT_COMMISSION`, before null) and `approve` (`APPROVE_COMMISSION`, before/after) with
  audit-call verification. `updateDeductions` and `createClawback` were NOT given dedicated tests (see
  Things Not Finished) — their production audit calls are still implemented.
- `docs/agent-handoffs/14_backend-audit-log-coverage.md` (new) — this handoff.

## Commands Run
```bash
git status                             # confirmed branch backend/audit-log-coverage, clean tree
cd backend && ./mvnw -B clean verify   # full build + test + jacoco check
```

## Test / Build Results
- `./mvnw -B clean verify`: **BUILD SUCCESS**. `Tests run: 287, Failures: 0, Errors: 0, Skipped: 0`
  (284 baseline + 1 new PayrollServiceTest case + 2 new CommissionServiceTest cases; the leave/overtime
  audit assertions were added to existing tests rather than new ones, net +3 tests). Docker was
  available in this environment, so the Testcontainers-backed integration tests (`*IntegrationTest`)
  ran for real rather than skipping.
- Jacoco `check`: **passed**. Measured line coverage = 2,629 / 4,878 covered lines ≈ **53.9%**
  (comfortably above the 0.51 ratchet floor; ratchet left unchanged in `pom.xml`).

## Production Audit Calls Added (13 total)
1. `LeaveService.submit` → `SUBMIT_LEAVE_REQUEST`
2. `LeaveService.approve` → `APPROVE_LEAVE_REQUEST`
3. `LeaveService.reject` → `REJECT_LEAVE_REQUEST`
4. `LeaveService.cancel` → `CANCEL_LEAVE_REQUEST`
5. `OvertimeService.submit` → `SUBMIT_OVERTIME_REQUEST`
6. `OvertimeService.approve` → `APPROVE_OVERTIME_REQUEST`
7. `OvertimeService.reject` → `REJECT_OVERTIME_REQUEST`
8. `OvertimeService.cancel` → `CANCEL_OVERTIME_REQUEST`
9. `CommissionService.submit` → `SUBMIT_COMMISSION`
10. `CommissionService.updateDeductions` → `UPDATE_COMMISSION_DEDUCTIONS`
11. `CommissionService.approve` → `APPROVE_COMMISSION`
12. `CommissionService.createClawback` → `CREATE_CLAWBACK`
13. `PayrollService.process` → `PROCESS_PAYROLL`

## Decisions Made
- In `PayrollService.process`, kept the audit call as a second, separate line right after the existing
  `auditPayrollAccess(...)` logger call rather than merging them — they serve different purposes (one is
  an append-only DB audit trail via `AuditService`, the other is a structured log line for sensitive-data
  access) and the task explicitly said not to touch the existing logger calls.
- `CommissionService.createClawback`: used the newly created clawback record's id (`clawbackId`) as
  `entityId`, with `before = null` and `after` = the new clawback `CommissionRecord`, since a clawback is
  a brand-new row, not a mutation of the original commission record (matches the brief exactly).
- Test for `PayrollService.process` drives the real `preview(...)` codepath with an empty active-employee
  list (`findActiveEmployees()` → `List.of()`) rather than mocking `PayrollCalculator` output — this
  keeps the test simple while still exercising the full `process` method (including the new audit call)
  without touching payroll calculation logic.

## Assumptions
- `CommissionRecord.status()`/`.kind()` are plain `String`s compared against `CommissionStatus`/
  `CommissionKind` constants (not enums) — matched existing code style, no changes needed there.
- Mockito's default answers return empty collections (not null) for unstubbed `List`-returning mock
  methods (e.g. `CommissionRepository.findApprovedRecordsByMonth`), so `PayrollService`'s
  `commissionPayByEmployee` internal call didn't need explicit stubbing in the new payroll test.

## Known Risks
- None identified beyond the inherent scope of this change — it is additive-only (constructor params +
  audit calls), no existing behavior branches were touched, and the full suite is green.

## Things Not Finished
- `CommissionServiceTest` covers `submit` and `approve` only. `updateDeductions` and `createClawback`
  were not given dedicated unit tests — `createClawback` in particular requires mocking a realistic
  "existing approved SALE record with no active clawback" `CommissionRecord` plus the repository's
  `createClawback(...)` key-generation flow, which is disproportionately complex to build from a
  from-scratch mock-only test relative to the marginal audit-coverage value, given the production audit
  call is already wired into all four commission methods and verified end-to-end by
  `CommissionRepositoryIntegrationTest`-style coverage is out of this task's scope. Flagging for a future
  test-coverage pass if desired.

## Recommended Next Agent
Claude Opus review — confirm all 13 audit calls fire with correct action/entity/before/after semantics,
confirm no business-logic drift, and decide whether `updateDeductions`/`createClawback` unit test
coverage in `CommissionServiceTest` is worth adding now or can stay deferred.

## Exact Next Prompt

```
You are the reviewer agent (Claude Opus) for branch `backend/audit-log-coverage` in GL-R-ERP at
/Users/ploy_warit/Desktop/GL-R-ERP. Do NOT implement beyond tiny, safe fixes (typos/obvious
one-liners) — anything larger goes back to an implementation agent.

Read first: docs/agent-handoffs/00_MASTER_CONTEXT.md, then this file
(docs/agent-handoffs/14_backend-audit-log-coverage.md) in full.

Review focus:
1. Confirm all 13 audit calls listed in this handoff are present, use the correct action string,
   entity string ("leave_request"/"overtime_request"/"commission_record"/"payroll_period"), correct
   entityId, and correct before/after DTOs (especially CommissionService.createClawback's entityId,
   which must be the NEW clawback id, not the original commission id).
2. Confirm zero business-logic/validation/control-flow/return-value changes — diff each of the 4
   service files against main and verify every non-audit line is unchanged.
3. Run `cd backend && ./mvnw -B clean verify` and confirm BUILD SUCCESS, 287 tests passing, Jacoco
   check passing at ~53.9% (ratchet floor 0.51).
4. Decide whether CommissionServiceTest needs updateDeductions/createClawback coverage added now, or
   whether to leave it deferred as noted in "Things Not Finished".
```
