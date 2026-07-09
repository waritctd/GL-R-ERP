import pytest

from helpers import assert_status, price_proposed_ticket


@pytest.mark.uat("SEC-01", title="Employee cannot call HR-only endpoint", priority="P0")
def test_sec01_employee_hr_endpoint_forbidden(employee, admin):
    r = employee.get("/api/employees?page=0&size=1")
    assert r.status_code == 403, r.text
    r = admin.get("/api/employees?page=0&size=1")
    assert r.status_code == 403, "admin@uat.glr currently resolves to employee role; " + r.text


@pytest.mark.uat("SEC-02", title="Cross-user employee data denied", priority="P0")
def test_sec02_cross_user_employee_denied(employee, hr):
    target = next(e for e in hr.get("/api/employees?search=GLR-0005&page=0&size=5").json()["employees"] if e["code"] == "GLR-0005")
    r = employee.get(f"/api/employees/{target['id']}")
    assert r.status_code == 403, r.text


@pytest.mark.uat("SEC-03", title="Sales non-manager cannot approve price", priority="P0")
def test_sec03_sales_approve_price_denied(sales, import_):
    ticket = price_proposed_ticket(sales, import_)
    r = sales.post(f"/api/tickets/{ticket['summary']['id']}/approve")
    assert r.status_code == 403, r.text


@pytest.mark.uat("SEC-04", title="Punch without device token rejected", priority="P0")
def test_sec04_punch_without_token_rejected(hr):
    payload = {
        "site_code": "SHOWROOM",
        "device_code": "SHOWROOM_SC700",
        "badge_code": "GLR-0014",
        "punch_time": "2026-08-06T08:58:00+07:00",
        "work_date": "2026-08-06",
        "device_status": 1,
        "punch_state": 0,
        "punch_source": "BIOMETRIC",
        "ingest_method": "LIVE_CAPTURE",
    }
    r = hr.post("/api/attendance/punch", json=payload)
    assert r.status_code == 401, r.text


@pytest.mark.uat("SEC-05", title="Unknown API route returns 404", priority="P1")
def test_sec05_unknown_route_404(employee):
    r = employee.get("/api/not-a-real-route")
    assert r.status_code == 404, r.text


@pytest.mark.uat("SEC-06", title="Security headers present", priority="P1")
def test_sec06_security_headers(employee, base_url):
    r = employee.get("/api/auth/me")
    assert_status(r, 200)
    assert r.headers.get("Content-Security-Policy") == "default-src 'none'; frame-ancestors 'none'"
    assert r.headers.get("X-Frame-Options") == "DENY"
    assert r.headers.get("X-Content-Type-Options") == "nosniff"
    if base_url.startswith("https://"):
        assert "Strict-Transport-Security" in r.headers
    else:
        assert "Strict-Transport-Security" not in r.headers
