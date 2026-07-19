# 81 — fix/sales-transition-gates

**Branch:** `fix/sales-transition-gates` (off `main` @ 7771782) · **Date:** 2026-07-19
**Agent:** Claude (Opus 4.8) · **Status:** implemented, verified, committed — not pushed

## Why

A conformance check of the built deal pipeline against the business flow analysis
(`~/Downloads/sales_status_branching_flow_report.md`) found the doc's architecture already
implemented (V50–V54 = §7's four independent status groups), but turned up two transition
defects. This branch is Tier 1 of the agreed remediation: **no schema change**.

Tier 3 (quotation identity — recipient is a role not a counterparty, and every quotation
renders the same ticket quantities) is **deliberately out of scope** and needs its own design.

## What changed

### A1 — `close()` could complete an undelivered deal

`deliveryGateComplete` accepted `GOODS_RECEIVED` with no delivery records. GOODS_RECEIVED
means the goods reached **GLR's own warehouse** (S17) — the customer has nothing. So a
fully-paid deal could be closed to COMPLETED with zero delivered units, while the automatic
gate (`maybeAdvanceClosedPaid`) refused that exact state.

**Key finding that de-risked the fix:** the javadoc justified the allowance as a concession to
"legacy coarse deals", but that is false. Legacy tickets close through the `legacyOk` branch
(`status = DOCUMENT_ISSUED`), which **never calls this predicate**. The only deals the
concession ever reached were modern dual-track ones (`QUOTATION_ISSUED`) — for which it was
simply wrong. Tightening it therefore cannot break legacy tickets, and the planned
"count uncloseable deals on the real DB first" step was not the gate it looked like.

`deliveryGateComplete` now requires `FULLY_DELIVERED`, matching the auto path.

### A2 — the documented happy path was treated as an exception

The business's fullest flow is `S1 → S2 → S4 → S3 → S5` — the designer is quoted *before*
signing off the spec. But `DealStage.ORDER` places `SPEC_APPROVED`(3) before
`QUOTE_DESIGN_SIDE`(4), so that everyday step was a **backward** move requiring a written
reason.

**Chose the allowlist, not a reorder.** Renumbering `ORDER` would touch the V50 CHECK
constraint, the uat seeds (V909 seeds all 14 stages), `stageMeta.js`/`format.js`, and every
historical `sales_stage` value — far too much blast radius for an ergonomics problem. Added
`DealStage.isRoutineBackwardMove(from, to)` exempting exactly one adjacent pair. Every other
backward move still requires a reason.

## Files changed

| File | Change |
|---|---|
| `backend/.../ticket/DealStage.java` | + `isRoutineBackwardMove`, with the reasoning for allowlist-over-reorder |
| `backend/.../ticket/TicketService.java` | `deliveryGateComplete` requires FULLY_DELIVERED; `updateStage` consults the exemption; corrected the now-false "deliberately STRICTER" javadoc on `maybeAdvanceClosedPaid` |
| `backend/.../ticket/TicketServiceTest.java` | inverted `close_dualTrackComplete_transitionsToClosed` → `close_dualTrackAtGoodsReceived_isRefused` (it encoded the defect); + 2 stage-exemption tests |
| `frontend/src/features/tickets/stageMeta.js` | + `isRoutineBackwardMove` mirroring the Java |
| `frontend/src/features/tickets/stageMeta.test.js` | + exemption test (scoped, not symmetric) |
| `frontend/src/features/tickets/UpdateStageModal.jsx` | stops demanding a note for the routine move |
| `frontend/src/api/mockApi.js` | mirrored both gates (`deliveryComplete` signature simplified — `ticketId` arg no longer needed) |

## Commands run

- `cd backend && ./mvnw -B clean verify`
- `cd frontend && npm run lint && npm test && npm run build`
- Browser verification against `frontend-mock` (port 5200) as the `sales` persona

## Results

- **Backend: 525 tests pass, 0 failures, 0 skipped.**
- **Frontend: 191 tests pass** (34 files), **lint 0 errors** (4 pre-existing
  `react-hooks/exhaustive-deps` warnings, none in changed files), **build clean**.
- **Integration tests RAN — they were not skipped.** `FlywayMigrationTest` (2),
  `TicketRepositoryIntegrationTest` (9), `TicketEventStatusIntegrationTest` (3) all green on
  real Postgres via **Testcontainers**. ⚠️ **CLAUDE.md is out of date on this** — it says
  integration tests are gated on `TEST_DB_URL` and skipped locally. `TEST_DB_URL` was unset
  and they ran anyway (Testcontainers 2.0.5 + Docker). Worth correcting in CLAUDE.md.

### Browser verification (mock, `sales` persona)

- **A2 on PR-2026-0007** (stage 4): selecting `SPEC_APPROVED` leaves Save **enabled** with an
  empty note; submitting moved the deal to stage 3 with no error. Probed the neighbours to
  confirm the exemption is scoped, not a general relaxation:

  | Target | Save |
  |---|---|
  | SPEC_APPROVED (the exempted pair) | enabled, no note |
  | PRESENTATION (back 2) | disabled — note required |
  | LEAD_APPROACH (back 3) | disabled — note required |
  | OWNER_SIGNOFF (forward 1) | enabled |
  | AWAITING_BUYER (forward 2 = skip) | disabled — note required |

- **A1 on PR-2026-0014** — the exact risky state (`quotation_issued` + `FULLY_PAID` +
  `GOODS_RECEIVED`, zero delivery records): **no close button is offered**. Previously it was.

## Known risks

- **Behaviour change for real data.** Any live deal sitting at FULLY_PAID + GOODS_RECEIVED
  with no delivery records can no longer be closed until a delivery is recorded. This is the
  intended correction, but it is user-visible. I **could not count affected rows** — the
  Supabase MCP servers need authorisation and this session was non-interactive. **Run that
  count before merging to `main`.** The legacy `DOCUMENT_ISSUED` path is provably unaffected.
- A2 is a one-pair allowlist. If the business wants other pairs exempted, extend
  `isRoutineBackwardMove` — do not relax the general rule.
- No migration, so no uat seed risk on this branch.

## Incidental finding (not fixed here)

PR-2026-0014 renders as stage **14 "ปิดงาน — รับเงินครบถ้วน"** while showing delivery
**`0 / 800`**. The stage comes from V50's historical backfill (`FULLY_PAID → CLOSED_PAID`),
which the mock mirrors faithfully. This is open UX finding **UX-23**, and it is **not
cosmetic** — the pipeline stage and the delivery track genuinely contradict each other on
backfilled rows. Worth deciding whether V50-era backfilled stages should be corrected.

## Next prompt

> Continue the sales remediation on a fresh branch `feat/sales-cancel-reason` off `main`.
> Read `docs/agent-handoffs/81_fix-sales-transition-gates.md` first.
> `TicketService.cancel` (~:1169) takes only `(ticketId, actor)`, no reason, and writes a null
> event message — a cancelled deal carries zero explanation, unlike CLOSED_LOST. Add **V55**
> with `cancel_reason VARCHAR(40)` + `cancelled_at TIMESTAMPTZ` on `sales.ticket` and a CHECK
> on the codes; add a `DealCancelReason` class mirroring `DealLostReason.java` exactly with
> `OWNER_CANCELLED`, `PROJECT_SUSPENDED`, `BUDGET_CANCELLED`, `OTHER`; make the reason
> **mandatory** as `markLost` does. Thread it through the controller DTO, service, repository,
> `mockApi.js` (contract.test.js enforces parity), and a frontend modal copying
> `MarkLostModal.jsx`. Extend `FlywayMigrationTest` to cover V55 in the full
> V1..V54+V900..V909 chain. Do not touch the quotation model.
