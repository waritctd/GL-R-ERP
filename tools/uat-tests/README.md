# GL-R-ERP — Automated UAT test harness

Python/pytest suite that drives the backend API through the UAT acceptance cases and writes a
pass/fail report keyed to the UAT test IDs in `ERP Documentation/11_UAT_Test_Cases.md`.

- **Design + full 69-ID coverage matrix:** [`PLAN.md`](PLAN.md)
- **`uat`-branch only** (depends on the `db/migration-uat` seed). Do not merge to `main`.

## Run it

```bash
cd tools/uat-tests
python -m pip install -r requirements.txt
./run.sh                 # fresh local uat stack (docker) -> full suite -> teardown
./run.sh -k ot01         # pass pytest args through (single case / -k filter)
```

`run.sh` brings up an ephemeral local stack (`docker-compose.uat.yml`: Postgres + backend on
`SPRING_PROFILES_ACTIVE=uat`, freshly migrated so all 9 personas are at `Uat@2026` and quotas/payroll
are pristine), waits for `/actuator/health`, runs pytest, then tears the stack down.

Point at an already-running backend (e.g. a live smoke) with:
```bash
UAT_BASE_URL=https://gl-r-erp-uat.onrender.com python -m pytest -q -m uat   # no reset/teardown
```

## Report

Written to `reports/` (git-ignored): `uat-results.md` (+ `.csv` to paste into
`UAT Deliverables/UAT_Execution_Log.xlsx`, + `junit.xml`). The summary line is the **P0 go-live gate**
(`P0: X/Y passed`).

## Adding tests

Each case is a function tagged with its UAT ID:
```python
@pytest.mark.uat("OT-03", title="Manager approves -> MANAGER_APPROVED", priority="P0")
def test_ot03(...): ...
```
Use the persona fixtures (`employee`, `hr`, `ceo`, `sales`, `salesmgr`, `account`, `import_`,
`divmgr`, `nulldiv`) and the `GlrClient` they yield (handles session + `X-XSRF-TOKEN`). Manual/UI-only cases
should still appear in the report via `pytest.skip(reason=...)`. See `PLAN.md` §3 for the matrix and
§4 for the state/ordering rules.
