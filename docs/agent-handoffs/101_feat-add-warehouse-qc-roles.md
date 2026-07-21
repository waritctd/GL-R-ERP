# 101 — feat/add-warehouse-qc-roles

**Branch:** `feat/add-warehouse-qc-roles` (rebased onto `origin/main` `842c5de` / #263).
**Status:** ready for review. Recognition-only role addition; no behaviour beyond employee-tier.

## Goal
Add `warehouse` and `qc` as first-class app roles so future work can build scoped access on
them. **This change adds the roles only** — WH/QC users get the **same self-service experience as
a plain `employee`** (no `งานขาย`/sales access). The scoped warehouse/QC views, the 3-sourcing-case
model, and the real QC quality-inspection step are a **separate, later change** (see handoff 100 §6
+ memory `sales-flow-gaps-sourcing-and-qc`).

## Files changed (7)
**Backend (authoritative role source):**
- `auth/ApplicationRoles.java` — `warehouse`, `qc` added to the role registry (PRIORITY/ALLOWED).
- `auth/DivisionAccessPolicy.java` — `roleFor`: `wh` → `warehouse`, `qc` → `qc` (after `ac`,
  before `sa`); Javadoc precedence updated (notes they are employee-tier for now).
- `auth/DivisionAccessPolicyTest.java` — new `warehouseDivisionDerivesWarehouse` /
  `qcDivisionDerivesQc`; the old `WH → employee` assertion retargeted to a non-mapped division
  (`nonMappedDivisionManagerKeepsBaseEmployeeRole`, PD-ฝ่ายผลิต).
- `auth/ApplicationRolesTest.java` — `isAllowed("warehouse"|"qc")` true.

**Frontend (no regression):**
- `api/routes.js` — `warehouse`, `qc` added to `canUseEmployeeExperience` +
  `canSubmitProfileRequests` (exact employee-tier parity).
- `utils/format.js` — `roleLabel`: `WAREHOUSE`, `QC`.
- `features/profile/ProfilePage.jsx` — `isEmployee` now derives from
  `hasPermission(role, 'canSubmitProfileRequests')` instead of `role === 'employee'`, so WH/QC
  keep the "request changes" affordance. Dashboard/attendance already fall through to `employee`
  mode for any non-company/non-manager role (verified `dashboardMode`/`attendanceMode`).

## Commands run
- Backend: `./mvnw -o test -Dtest=DivisionAccessPolicyTest,ApplicationRolesTest` → **19 pass /
  BUILD SUCCESS**; `./mvnw -o clean test-compile` → BUILD SUCCESS (whole backend compiles).
- Frontend: `npm ci && npm run lint && npx vitest run && npm run build` → lint 0 errors, **418
  tests pass**, build OK.

## Authz evidence
This changes **role derivation** (`DivisionAccessPolicy.roleFor`, a pure function — no DB, no
WHERE clause) and grants WH/QC **employee-equivalent** access (same permission keys as `employee`).
No scope/filter or who-can-read-whose-rows boundary moved, so per CLAUDE.md the correct evidence is
the **unit test** (`DivisionAccessPolicyTest` + `ApplicationRolesTest`, both extended and green).
No real-DB integration test is warranted for this change; when WH/QC later gain scoped `งานขาย`
read access, THAT change will require the real-service integration test.

## Known risks
- `roleFor` matches `qc` on `source_code == "QC"` exactly (real active QC division 38 has it); a
  legacy QC&ISO row whose `source_code` is a numeric code (e.g. `0009`) and has no `WH-`/`QC-` name
  prefix would fall back to `employee`. Broaden the match if such rows must map.
- `app/roles.js` (`roleForDivision`) is unused in the frontend runtime (dead mirror) — left
  untouched; the authoritative derivation is the backend.

## Next prompt
> Review PR for `feat/add-warehouse-qc-roles`: adds `warehouse`/`qc` roles at employee-tier only
> (handoff 101). Confirm no `งานขาย`/VIEWER_ROLES access was granted. Separately, when starting the
> deferred gap work (scoped WH/QC views + 3 sourcing cases + real QC step), build on these roles.
