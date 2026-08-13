# API surface reconciliation — Spring controllers vs `hrApi.js`

Snapshot taken 2026-08-13 at `98cfbfec` (after PRs #703–#720).

## How to regenerate this

The full endpoint-by-endpoint table is **not committed here on purpose**. A static 278-row list is a
hand-maintained mirror of exactly the kind `frontend/src/api/serverContract.test.js` exists to guard
against, and it would be stale the first time someone added an endpoint. Print the live one:

```sh
cd frontend && API_SURFACE_REPORT=1 npx vitest run src/api/serverContract.test.js
```

That emits every mapping with its controller, handler, `@Deprecated` flag and `hrApi` caller — the
same extraction the guard asserts against, so the report can never disagree with the test.

## The counts

| | |
|---|---|
| Controllers swept | 39 |
| Server mappings | 278 (276 unique verb+path) |
| `hrApi.js` call sites captured | 261 (255 unique verb+path) |
| `routes.js` declared route names | 211 |

The two "extra" mappings are `POST /api/commissions` and `POST /api/leave`, each declared twice on
one controller with different `consumes` (JSON vs multipart). Spring disambiguates by content type;
they are one endpoint each, not a routing conflict.

## Divergences by category

### 1. Client calls with no server endpoint — **0**

The urgent direction is clean. Every path and verb `hrApi.js` issues is served by a controller,
including the composed ones (`withQuery`, `action(id, 'close/confirm')`, the bare `fetch()` blob
downloads and multipart uploads).

### 2. Path or verb mismatches — **0**

No endpoint is called under the wrong verb.

### 3. Orphaned `routes.js` entries — **0**

All 211 declared route names are referenced by `hrApi.js`.

### 4. Calls that match on path and verb and still always fail — **3**

Not visible to a path comparison, and the real finding of this audit. V141 (PR #702) moved landed
costing from Import to the CEO and **severed** three write routes rather than deleting them: still
routed, still matching, throwing 409 unconditionally.

| Call | Route | Replacement |
|---|---|---|
| `pricingRequests.createCosting` | `POST /api/pricing-requests/{}/costings` | `startPricingDecision` |
| `pricingRequests.recalculateCosting` | `POST /api/pricing-costings/{}/recalculate` | `POST /api/pricing-decisions/{}/recalculate-cost` (unexposed) |
| `pricingRequests.submitCosting` | `POST /api/pricing-costings/{}/submit` | factory-quote `markReadyForCosting` |

`PricingRequestDetailPage.jsx` still drives all three, so against a real backend those buttons
always fail. It is invisible under `VITE_USE_MOCKS=true` because `mockApi` never learned the routes
were severed. The backend half of the migration is deliberate and complete; the frontend pass has
not landed. **Not fixed here** — wiring the CEO costing UI is a feature, not an audit repair.

Note the repo has two retirement shapes and only this one is detectable at the HTTP layer:

- **route deleted, service method kept `@Deprecated`** — `TicketService#submit`/`#pickup`. No
  mapping exists, so nothing can call it and nothing enters the comparison.
- **route kept and severed** — the three above.

### 5. Server endpoints with no `hrApi` caller — **21**

None are deleted by this PR. Each carries its evidence in `SERVER_ONLY` in the guard. Summary:

| Endpoints | Verdict |
|---|---|
| `POST /api/attendance/punch`, `POST /api/attendance/devices/{}/agent-token` | **Live via a non-`hrApi` client.** The physical scanners authenticate with `X-GLR-Agent-Token`; token provisioning is a documented manual HR operation. |
| All 8 `ProcurementController` mappings | **Dormant by owner ruling.** PR #683 deleted the จัดซื้อ & นำเข้า page and every client layer, keeping the backend on purpose — its own body predicts this guard would flag them and says "That is intended." 0 factory POs have ever existed in production. |
| `PUT /api/pricing-decisions/{}/items/{}/cost-override`, `POST .../recalculate-cost` | **Unexposed — V141, awaiting the frontend pass.** The other half of category 4. |
| `POST`/`DELETE /api/pricing-formula-config/freight-rates` | **Unexposed.** PR #455 shipped 3 backend files and 0 frontend; the CEO settings UI still edits freight *amounts* only, so V109's blank cells stay unfillable and a new origin country still needs a migration. |
| `GET`/`PUT /api/payroll/deduction-consents` | **Unexposed** (issue #376). A recorded field, deliberately not an enforcement gate. |
| `GET /api/payroll/deduction-shortfalls` | **Unexposed, and the data is accumulating.** The table is written on every payroll run; only the read surface has no client, so HR cannot see what the system records. |
| `POST /api/leave/policy-document` | **Unexposed.** The upload half of the §5 announcement PDF; rows can only arrive through it, so the table is empty everywhere. |
| `POST /api/employees/{}/reset-password` | **Unexposed, operationally significant.** `README.md` designates this as *the* onboarding path for a new employee's first password, yet there is no button and no documented curl recipe. |
| `GET`/`PUT /api/deal-estimate-markup` | **Orphaned — the clearest deletion candidate.** Removed from the frontend by PR #682 after UAT (reps misread the markup figure as a selling price), with two frontend tests asserting it must not return. Controller, repository, DTOs, the V112 table and two backend test classes all survive, asserting a contract nothing consumes. |

## What the guard does and does not cover

`frontend/src/api/serverContract.test.js` compares **paths, verbs and deprecation**. It does not
compare request or response DTO shapes, status codes, or authorization — a role gate still needs a
real-DB integration test through the Java service, per `CLAUDE.md`.

It also runs in `frontend-ci.yml`'s `lint-and-build`, which is gated on the diff touching
`frontend/**`. A **backend-only** PR that renames an endpoint therefore does not trip it until the
next frontend-touching PR. The gate was widened in this PR to include
`backend/src/main/java/**/*Controller.java` so the guard fires on the diffs that can break it.
