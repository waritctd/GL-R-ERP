import uuid

import pytest

from helpers import assert_status, create_ticket, raise_pricing_request, run_factory_quote_and_costing, unique


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


@pytest.mark.uat("SEC-03", title="Sales non-manager cannot approve a pricing decision", priority="P0")
def test_sec03_sales_approve_price_denied(sales, import_, ceo):
    # Slice S1 retired the ticket-native /approve route; the CEO-only role boundary now lives on
    # PricingDecisionService.approve (PricingDecisionController POST
    # /api/pricing-decisions/{id}/approve, CEO_ROLES=Set.of("ceo")). Drive a real deal up to a
    # DRAFT pricing decision (CEO opens it, as only CEO may), then prove sales -- not even the
    # deal's own owner -- can approve it.
    ticket = create_ticket(sales, unique("SEC03"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit = raise_pricing_request(sales, import_, tid, marker=unique("SEC03"))
    run_factory_quote_and_costing(import_, pr_id, pr_item_id, requested_unit=unit)

    # startReview is CEO-only too -- sales cannot even open the decision.
    r = sales.post(f"/api/pricing-requests/{pr_id}/pricing-decisions", json={
        "defaultMarginPct": "0.20", "currency": "THB", "ceoNote": None, "clientRequestId": None,
    })
    assert r.status_code == 403, r.text

    r = ceo.post(f"/api/pricing-requests/{pr_id}/pricing-decisions", json={
        "defaultMarginPct": "0.20", "currency": "THB", "ceoNote": "UAT SEC-03", "clientRequestId": None,
    })
    assert_status(r, 200)
    decision_id = r.json()["decision"]["id"]

    # A DRAFT decision now exists -- sales (the deal's own owner) still cannot approve it.
    r = sales.post(f"/api/pricing-decisions/{decision_id}/approve",
                    json={"ceoNote": "UAT sales trying to approve", "clientRequestId": None})
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
