import pytest

from helpers import assert_status


@pytest.mark.uat("ATT-01", title="Physical SC700 card tap appears", priority="P0")
def test_att01_sc700_tap_manual():
    pytest.skip(reason="manual/UI")


@pytest.mark.uat("ATT-02", title="Employee sees only own attendance", priority="P0")
def test_att02_employee_scope(employee):
    user = employee.get("/api/auth/me").json()["user"]
    r = employee.get("/api/attendance/punches?from=2026-06-29&to=2026-07-04&limit=100")
    assert_status(r, 200)
    punches = r.json()["punches"]
    assert all(p["employee_id"] == user["employeeId"] for p in punches), punches


@pytest.mark.uat("ATT-03", title="Division manager sees division attendance", priority="P0")
def test_att03_division_manager_scope(divmgr):
    r = divmgr.get("/api/attendance/punches?from=2026-06-29&to=2026-07-04&limit=100")
    assert_status(r, 200)
    punches = r.json()["punches"]
    codes = {p["employee_code"] for p in punches if p["employee_code"]}
    assert "GLR-0008" in codes, punches
    assert "GLR-0005" not in codes, punches


@pytest.mark.uat("ATT-04", title="HR imports valid DAT file", priority="P0")
def test_att04_dat_import(hr):
    content = "GLR-0011\t2026-08-03 08:55:00\t1\t0\t0\t0\nGLR-0011\t2026-08-03 18:05:00\t1\t1\t0\t0\n"
    r = hr.post(
        "/api/attendance/imports/dat",
        json={
            "site_code": "SHOWROOM",
            "device_code": "SHOWROOM_SC700",
            "file_name": "uat-valid.dat",
            "content": content,
        },
    )
    assert_status(r, 200)
    body = r.json()
    assert body["status"] == "imported", body
    assert body["row_count"] == 2 and body["inserted_punch_count"] == 2, body


@pytest.mark.uat("ATT-05", title="HR rotates device token", priority="P1")
def test_att05_rotate_device_token(hr):
    r = hr.post("/api/attendance/devices/SHOWROOM_SC700/agent-token")
    assert_status(r, 200)
    old_token = r.json()["agent_token"]
    r = hr.post("/api/attendance/devices/SHOWROOM_SC700/agent-token")
    assert_status(r, 200)
    new_token = r.json()["agent_token"]
    assert new_token != old_token
    payload = {
        "site_code": "SHOWROOM",
        "device_code": "SHOWROOM_SC700",
        "badge_code": "GLR-0014",
        "punch_time": "2026-08-05T08:58:00+07:00",
        "work_date": "2026-08-05",
        "device_status": 1,
        "punch_state": 0,
        "punch_source": "BIOMETRIC",
        "ingest_method": "LIVE_CAPTURE",
    }
    rejected = hr.post(
        "/api/attendance/punch",
        json=payload,
        headers={"X-GLR-Agent-Token": old_token},
    )
    assert rejected.status_code == 401, rejected.text
    accepted = hr.post(
        "/api/attendance/punch",
        json=payload,
        headers={"X-GLR-Agent-Token": new_token},
    )
    assert_status(accepted, 200)
    assert accepted.json()["status"] in {"inserted", "duplicate"}, accepted.text


@pytest.mark.uat("ATT-06", title="Oversized DAT upload is rejected", priority="P1")
def test_att06_oversized_dat_rejected(hr):
    r = hr.post(
        "/api/attendance/imports/dat",
        json={
            "site_code": "SHOWROOM",
            "device_code": "SHOWROOM_SC700",
            "file_name": "uat-oversized.dat",
            "content": "x" * 5_000_001,
        },
    )
    assert r.status_code in {400, 413}, r.text
