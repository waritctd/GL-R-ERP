"""Overtime UAT cases. OT-01 is the worked example (exact contract verified live this session);
the rest of the OT matrix (OT-02..OT-09) is for Codex to add per tools/uat-tests/PLAN.md."""
import pytest


@pytest.mark.uat("OT-01", title="Submit workday OT -> SUBMITTED, x1.50", priority="P0")
def test_ot01_submit_workday(employee):
    r = employee.post(
        "/api/overtime",
        json={
            "employeeId": None,
            "workDate": "2026-08-03",
            "plannedStartAt": "2026-08-03T18:00:00+07:00",
            "plannedEndAt": "2026-08-03T20:00:00+07:00",
            "dayType": "WORKDAY",
            "reason": "UAT harness OT-01",
        },
    )
    assert r.status_code == 200, r.text
    req = r.json()["request"]
    assert req["status"] == "SUBMITTED", req
    assert float(req["payRateMultiplier"]) == 1.50, req
    assert req["plannedMinutes"] == 120, req
