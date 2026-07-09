import pytest

from helpers import assert_status, submit_ot


def punch_for_ot(hr, employee_code, work_date, start_time, end_time):
    r = hr.post("/api/attendance/devices/SHOWROOM_SC700/agent-token")
    assert_status(r, 200)
    token = r.json()["agent_token"]
    for state, at in [(0, start_time), (1, end_time)]:
        r = hr.post(
            "/api/attendance/punch",
            json={
                "site_code": "SHOWROOM",
                "device_code": "SHOWROOM_SC700",
                "badge_code": employee_code,
                "punch_time": f"{work_date}T{at}+07:00",
                "work_date": work_date,
                "device_status": 1,
                "punch_state": state,
                "punch_source": "BIOMETRIC",
                "ingest_method": "LIVE_CAPTURE",
            },
            headers={"X-GLR-Agent-Token": token},
        )
        assert_status(r, 200)


@pytest.mark.uat("OT-01", title="Submit workday OT -> SUBMITTED, x1.50", priority="P0")
def test_ot01_submit_workday(employee):
    req = submit_ot(employee, "UAT harness OT-01", workDate="2026-08-03",
                    plannedStartAt="2026-08-03T18:00:00+07:00",
                    plannedEndAt="2026-08-03T20:00:00+07:00")
    assert req["status"] == "SUBMITTED", req
    assert float(req["payRateMultiplier"]) == 1.50, req
    assert req["plannedMinutes"] == 120, req


@pytest.mark.uat("OT-02", title="Submit holiday OT -> x3.00", priority="P0")
def test_ot02_submit_holiday(sales):
    req = submit_ot(
        sales,
        "UAT harness OT-02",
        workDate="2026-08-08",
        plannedStartAt="2026-08-08T09:00:00+07:00",
        plannedEndAt="2026-08-08T12:00:00+07:00",
        dayType="HOLIDAY",
    )
    assert req["status"] == "SUBMITTED", req
    assert float(req["payRateMultiplier"]) == 3.00, req


@pytest.mark.uat("OT-03", title="Manager approves -> MANAGER_APPROVED", priority="P0")
def test_ot03_manager_approves(sales, salesmgr):
    req = submit_ot(sales, "UAT harness OT-03", workDate="2026-08-10",
                    plannedStartAt="2026-08-10T18:00:00+07:00",
                    plannedEndAt="2026-08-10T20:00:00+07:00")
    r = salesmgr.post(f"/api/overtime/{req['id']}/approve", json={"reviewerNote": "manager ok"})
    assert_status(r, 200)
    assert r.json()["request"]["status"] == "MANAGER_APPROVED", r.text


@pytest.mark.uat("OT-04", title="Approved OT appears in payroll preview", priority="P0")
def test_ot04_approved_ot_in_payroll_preview(hr):
    r = hr.post("/api/payroll/preview", json={"payrollMonth": "2026-06-01", "inputs": []})
    assert_status(r, 200)
    line = next(line for line in r.json()["period"]["lines"] if line["employeeCode"] == "GLR-0005")
    assert float(line["overtimePay"]) == 350.0, line


@pytest.mark.uat("OT-05", title="Employee cancels pending OT", priority="P1")
def test_ot05_cancel_pending(sales):
    req = submit_ot(sales, "UAT harness OT-05", workDate="2026-08-11",
                    plannedStartAt="2026-08-11T18:00:00+07:00",
                    plannedEndAt="2026-08-11T20:00:00+07:00")
    r = sales.post(f"/api/overtime/{req['id']}/cancel", json={"reviewerNote": "cancel"})
    assert_status(r, 200)
    assert r.json()["request"]["status"] == "CANCELLED", r.text


@pytest.mark.uat("OT-06", title="End before start is rejected", priority="P1")
def test_ot06_end_before_start(employee):
    r = employee.post(
        "/api/overtime",
        json={
            "employeeId": None,
            "workDate": "2026-08-12",
            "plannedStartAt": "2026-08-12T20:00:00+07:00",
            "plannedEndAt": "2026-08-12T18:00:00+07:00",
            "dayType": "WORKDAY",
            "reason": "UAT harness OT-06",
        },
    )
    assert r.status_code == 400, r.text


@pytest.mark.uat("OT-07", title="Cross-division manager approval denied", priority="P0")
def test_ot07_cross_division_approve_denied(sales, divmgr):
    req = submit_ot(sales, "UAT harness OT-07", workDate="2026-08-13",
                    plannedStartAt="2026-08-13T18:00:00+07:00",
                    plannedEndAt="2026-08-13T20:00:00+07:00")
    r = divmgr.post(f"/api/overtime/{req['id']}/approve", json={"reviewerNote": "nope"})
    assert r.status_code == 403, r.text


@pytest.mark.uat("OT-08", title="CEO final approval -> APPROVED with payable minutes", priority="P0")
def test_ot08_ceo_final_approval(sales, salesmgr, ceo, hr):
    work_date = "2026-08-14"
    req = submit_ot(
        sales,
        "UAT harness OT-08",
        workDate=work_date,
        plannedStartAt=f"{work_date}T18:00:00+07:00",
        plannedEndAt=f"{work_date}T20:00:00+07:00",
    )
    punch_for_ot(hr, "GLR-0005", work_date, "18:00:00", "20:00:00")
    r = salesmgr.post(f"/api/overtime/{req['id']}/approve", json={"reviewerNote": "manager ok"})
    assert_status(r, 200)
    assert r.json()["request"]["status"] == "MANAGER_APPROVED"
    r = ceo.post(f"/api/overtime/{req['id']}/approve", json={"reviewerNote": "ceo ok"})
    assert_status(r, 200)
    approved = r.json()["request"]
    assert approved["status"] == "APPROVED", approved
    assert approved["payableMinutes"] == 120, approved


@pytest.mark.uat("OT-09", title="Manager reject -> REJECTED", priority="P0")
def test_ot09_reject(sales, salesmgr):
    req = submit_ot(sales, "UAT harness OT-09", workDate="2026-08-17",
                    plannedStartAt="2026-08-17T18:00:00+07:00",
                    plannedEndAt="2026-08-17T20:00:00+07:00")
    r = salesmgr.post(f"/api/overtime/{req['id']}/reject", json={"reviewerNote": "UAT reject"})
    assert_status(r, 200)
    rejected = r.json()["request"]
    assert rejected["status"] == "REJECTED", rejected
    assert rejected["reviewerNote"] == "UAT reject", rejected
