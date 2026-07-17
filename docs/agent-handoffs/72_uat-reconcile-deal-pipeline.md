# Agent Handoff — Reconcile `uat` with `main` after the deal-pipeline merge (FOR CODEX)

> **Context.** The 5-phase branching-workflow program (deal sales pipeline, V50–V54) is now
> **merged to `main`** (PRs #226→#232, all sequential, `main` == the verified P5 tip, 507
> backend / 123 frontend green). The `uat` branch must now take this program. `uat` is **not**
> just old sales code — it carries its own infrastructure that `main` lacks, and `main`
> refactored a shared layer, so `main→uat` is a **real two-way reconciliation**, not a
> fast-forward. This handoff is the execution spec. **Do NOT push `uat`** — Opus runs the review
> gate and pushes (the push triggers the Render `gl-r-erp-uat` auto-deploy for testers).

## Task
On branch **`uat`** (from `origin/uat`), merge `origin/main` and reconcile so uat runs **main's
canonical pipeline code** while **preserving uat's own infrastructure** (mail abstraction,
profile, seed data), then **rework the uat sales seed migrations** for the new 14-stage schema,
then **verify the full migration chain + tests**. Land it as a single merge commit on `uat`
(plus follow-up commits for the seed rework if clearer).

## The divergence you must reconcile (verified — do not re-litigate)

### 1. Notification / mail architecture (the hard part)
- **`main` refactored the notification core:** `notifyByRole(String role, long ticketId, String
  type, String message)` now lives on **`NotificationRepository`** (it resolves *who* + drives
  the notify). `main`'s `NotificationService` depends on `NotificationRepository` +
  `NotificationEmailService`. `main`'s `NotificationEmailService` sends via Spring
  **`JavaMailSender`** directly.
- **`uat` kept the older split** (`notifyByRole` on `NotificationService`) **and** built a
  **`Mailer` abstraction** (`th.co.glr.hr.mail`: `Mailer`, `LogMailer`, `ResendMailer`,
  `SmtpMailer`, `MailSendException`) with **override-to + subject-prefix + appBaseUrl** — the
  UAT email-redirect infra. uat's `NotificationEmailService(Mailer)` and **`FactoryEmailService`**
  both deliver through `Mailer`. uat also has `notification/EmployeeContact.java`.

**Resolution principle:** `main` owns *"who + when to notify"* (its `NotificationRepository` +
`NotificationService`). **uat owns *"how to deliver email"*** (`Mailer` + `NotificationEmailService`
+ `FactoryEmailService`). Keep uat's Mailer delivery; adopt main's notification-driving core;
make the seam compatible. Concretely:

| File | Take from | Why |
|------|-----------|-----|
| `notification/NotificationRepository.java` | **main** | has pipeline's `notifyByRole` |
| `notification/NotificationService.java` | **main** | pipeline's notify-driving |
| `notification/NotificationController.java`, `NotificationDto.java` | **main** | pipeline-aligned |
| `notification/NotificationEmailService.java` | **uat** | Mailer-based delivery (override-to) |
| `notification/EmployeeContact.java` | **uat** (keep) | used by uat email/tests |
| `mail/*` (whole package) | **uat** (keep) | UAT mailer abstraction |
| `factory/FactoryEmailService.java` | **uat** (keep) | Mailer-based |
| `ticket/TicketService.java`, `deposit/DepositNoticeService.java`, `profile/ProfileRequestService.java` | **main** | pipeline + call `NotificationRepository.notifyByRole` |

**The seam to make compile:** `main`'s `NotificationService` (constructor
`NotificationService(NotificationRepository, NotificationEmailService)`) calls methods on
`emailService`. uat's `NotificationEmailService(Mailer)` exposes `send(recipient, subject, body)`
+ `sendWithAttachment(...)`. You must ensure **the method(s) main's `NotificationService`/core
invoke on `NotificationEmailService` exist on uat's version** — adapt uat's `NotificationEmailService`
to expose whatever main's core calls (keeping its Mailer-based body), OR add a thin adapter.
Both are Spring `@Service`/`@Component` beans wired by constructor injection: uat provides a
`Mailer` bean (via the `mail/` package + `application-uat.yml` config); keep that wiring. Do NOT
introduce a `JavaMailSender` dependency into uat's path.

> Guardrail: after resolving, **grep for BOTH `JavaMailSender` and `Mailer`** in the merged tree
> — uat's runtime path must use `Mailer` only; `JavaMailSender` should not leak in from main's
> `NotificationEmailService`. Remove main's `NotificationEmailService` version entirely.

### 2. Tests
Take **main's** notification/ticket/deposit test files where they test main's refactored core;
**keep uat's** `mail/*Test`, `notification/NotificationEmailServiceTest`, and uat-only tests
(`payroll/PayrollProcessConcurrencyIntegrationTest`). The merged test suite must compile and
pass. Where a uat test asserts old notification behavior that main changed, update it to main's
behavior (do not delete coverage silently; note any test whose intent changed).

### 3. Files to PRESERVE as-is (uat-only, main lacks them)
`mail/*`, `notification/EmployeeContact.java`, `application-uat.yml`, all
`db/migration-uat/V900..V908`, uat-only tests (`mail/*Test`, `PayrollProcessConcurrencyIntegrationTest`).
Everything else under `backend/src/main/java` + `backend/src/test/java`: **main is canonical**
(the pipeline + all HR/payroll evolution). When a non-uat-specific file conflicts or auto-merged
into an inconsistent hybrid, take **main's** version.

## Sales seed rework (`db/migration-uat`) — the second half

uat's `V903__uat_sales.sql`, `V905__uat_dual_track_sales.sql`,
`V906__uat_deposit_notice_revision_doc_number.sql` seed sales data written for the **pre-pipeline**
model. They run **after** V54 on a fresh uat DB (V900+ > V54), so they must satisfy the **new**
schema and produce **coherent demo data for the 14-stage model**. New schema they must honor:

- **`sales.ticket`** (V50/V51/V53): `sales_stage` (CHECK — one of the 14 codes; default
  `LEAD_APPROACH`), `lost_reason`/`lost_at` (paired: both null or both set), `lifecycle`
  (`ACTIVE/ON_HOLD/DORMANT/CLOSED_LOST/CANCELLED/COMPLETED`), `tender_requirement`,
  `deposit_policy` (`REQUIRED/NOT_REQUIRED/WAIVED/CREDIT_CUSTOMER`), `entry_channel`,
  `billing_date`/`due_date`/`credit_term_days`. Defaults exist, but seeded deals should set
  **`sales_stage` + `lifecycle` explicitly** to reflect the intended demo state (don't rely on
  the default lead stage for a deal that's meant to be mid-pipeline).
- **`sales.quotation`** (V52): `recipient_type` (CHECK — `DESIGNER/OWNER/BUYER/UNSPECIFIED`),
  and the **widened `doc_status` CHECK** (`DRAFT/ISSUED/SENT/ACCEPTED/REJECTED/EXPIRED/
  CANCELLED/SUPERSEDED` — verify exact list in V52). Seeded quotations must use valid
  `recipient_type` + `doc_status`.
- **`sales.payment_receipt`** (V53) — **NEW ledger.** The pipeline derives paid/outstanding from
  `payment_receipt`, NOT from `payment_status` alone. A seed that sets
  `payment_status='DEPOSIT_PAID'` but inserts **no receipt row** will render as *fully
  outstanding* in the new UI. So for any deal meant to show deposit/partial/full payment,
  **insert matching `payment_receipt` rows** (`kind` `DEPOSIT/BALANCE/ADJUSTMENT`, `amount>0`,
  `recorded_by` a valid uat employee).
- **`sales.ticket_item`** (V54): `qty_delivered` (CHECK `0 ≤ qty_delivered ≤ qty`). For any deal
  meant to show partial/full delivery, set `qty_delivered` and add
  `sales.delivery_record` + `delivery_record_item` rows (`source` `WAREHOUSE|STOCK`).
- **`sales.ticket_event`** `chk_event_kind` was re-declared several times (V50/V51/V53/V54) —
  any event kinds seeds insert must be in the final CHECK list.

Goal: after V908 on a fresh uat DB, the seeded deals should render coherently across the 14-stage
pipeline, payment ledger, and delivery views — e.g., a couple of deals mid-stage, one credit
customer with a past `due_date` (overdue), one partially delivered, one fully closed. Keep the
existing uat personas/customers/projects; only fix the **sales** seed rows to the new model. If a
seeded state can't be made coherent, prefer fewer-but-correct demo deals over many broken ones,
and **`log`/comment what changed**.

## Verification (must all pass before passing back)
- `cd backend && ./mvnw -B clean verify` — compile + unit tests + **Flyway migration
  integration test (Testcontainers)**. The migration test must exercise the **full uat chain**
  (V1..V54 **+** `db/migration-uat` V900..V908) so the seed rework is actually validated against
  a real Postgres. If the existing `FlywayMigrationTest` does not include the `migration-uat`
  location (it historically used a limited `.schemas(...)`/locations list — see the uat-seed
  ordering trap), **add/adjust a test (or a uat-profile migration test) that applies the uat
  locations on a fresh DB** and asserts it reaches V908 cleanly. Do not claim the seeds are
  verified unless a test actually ran them.
- `cd frontend && npm run lint && npm test && npm run build` — main's frontend came in via the
  merge; confirm still green (baseline lint 0 / 123 tests / build).
- Grep guardrail: no `JavaMailSender` on uat's runtime path; no lingering conflict markers; no
  skipped tests / lint suppressions.

## Non-negotiable invariants (carried)
- Owner scoping, CEO price gate, money=account/ceo, sales_manager stage-only — all already in
  main's code you're adopting; don't weaken them.
- Do NOT change the pipeline business logic (you're integrating it, not editing it).
- Do NOT push `uat`. Do NOT touch `main`. Land work on `uat` only.
- Keep `// Mirrors <JavaClass>` headers and the uat migration comments accurate.

## Definition of done (fill before passing back)
- Merge resolved per the file-disposition table; notification seam compiles with uat's Mailer.
- Sales seeds reworked for V50–V54 (+ ledger + delivery); fresh-DB uat chain reaches V908.
- `./mvnw -B clean verify` green (note the migration test that covers the uat chain);
  frontend green.
- Summary of: what was taken from each side, the seam adaptation, per-seed changes, and full
  verification output.

## Files changed / Commands run / Tests / Known risks / Next prompt
Implemented on `uat` without pushing.

Files changed:
- Merged `origin/main` into `uat`; kept main-owned deal pipeline services/controllers/tests and
  UAT-owned mail delivery (`mail/*`, `NotificationEmailService`, `FactoryEmailService`,
  `application-uat.yml`, `migration-uat`).
- Adapted `NotificationEmailService` to keep UAT's `Mailer` runtime while exposing the
  main-compatible `send(employeeId, to, subject, body)` overload.
- Restored main-owned notification/profile/ticket/deposit service/test shape where UAT had stale
  notification behavior.
- Reworked `V903__uat_sales.sql` and `V905__uat_dual_track_sales.sql` for explicit
  `sales_stage`/`lifecycle`, recipient-scoped quotations, payment receipts, delivery records, one
  overdue credit customer, one partially delivered ticket, and one closed-paid ticket.
- Added UAT seed assertions to `FlywayMigrationTest`: fresh UAT locations reach V908 and assert
  14 distinct UAT stages, 5 payment receipts, 3 delivery records, overdue credit, partial delivery,
  and closed-paid end state.

Commands/tests:
- `cd backend && ./mvnw -B -DskipTests compile` — passed.
- `cd backend && ./mvnw -B -Dtest=FlywayMigrationTest test` — first sandbox run skipped Docker;
  reran with Docker access and fixed V903/assertion issues; final pass.
- `cd backend && ./mvnw -B clean verify` — passed, 524 tests, 0 failures/errors/skips, Flyway
  UAT chain exercised through V908.
- `cd frontend && npm run lint` — exit 0; reports 4 existing hook-dependency warnings in
  attendance/commissions/payroll.
- `cd frontend && npm test` — passed, 126 tests.
- `cd frontend && npm run build` — passed.
- Guardrails: no anchored conflict markers in source; no `JavaMailSender` in notification/factory/
  ticket/deposit/profile runtime path; notification/factory delivery uses `Mailer`. Existing
  project lint-disable comments remain unchanged.

Known risks:
- Frontend lint is not warning-free in this worktree despite exit 0; warnings are outside this
  merge seam and were not suppressed.
- `SmtpMailer` still references `JavaMailSender` internally as a `Mailer` provider, which is the
  intended UAT mail abstraction path; no notification runtime dependency on `JavaMailSender` was
  introduced.

Next prompt:
- Review the local `uat` merge commit, then push/create PR when ready. Do not push automatically
  from this handoff thread.
