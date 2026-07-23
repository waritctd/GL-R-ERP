import pytest

from helpers import assert_status, employee_by_code, money, submit_leave


@pytest.mark.uat("LV-01", title="Vacation within quota auto-approves", priority="P0")
def test_lv01_within_quota_auto_approved(employee):
    req = submit_leave(
        employee,
        "UAT LV-01 vacation",
        leaveTypeCode="VACATION",
        startDate="2026-08-24",
        endDate="2026-08-24",
    )
    assert req["status"] == "APPROVED", req
    assert money(req["quotaRemainingAfter"]) == money(req["quotaRemainingBefore"]) - money(req["totalDays"])


@pytest.mark.uat("LV-02", title="Over-quota leave is approved with the excess unpaid", priority="P0")
def test_lv02_over_quota_approved_with_unpaid(hr):
    # Thai-labour-law leave feature: over-quota leave is no longer AUTO_REJECTED. It is APPROVED,
    # with days covered by remaining quota paid and the excess split off as unpaid (base/30
    # deduction flows to payroll). paid_days = min(quotaRemaining, total); unpaid = total - paid.
    sales = employee_by_code(hr, "GLR-0005")
    req = submit_leave(
        hr,
        "UAT LV-02 over quota",
        employeeId=sales["id"],
        leaveTypeCode="VACATION",
        startDate="2026-08-25",
        endDate="2026-08-26",
    )
    assert req["status"] == "APPROVED", req
    total = money(req["totalDays"])
    paid = money(req["paidDays"])
    unpaid = money(req["unpaidDays"])
    remaining_before = money(req["quotaRemainingBefore"])
    assert paid + unpaid == total, req
    expected_paid = min(remaining_before, total)
    assert paid == expected_paid, req
    assert unpaid == total - expected_paid, req
    # over-quota by definition: at least one unpaid day
    assert unpaid > money("0"), req


@pytest.mark.uat("LV-03", title="Sick leave without attachment is blocked", priority="P0")
def test_lv03_sick_without_attachment_auto_rejected(employee):
    req = submit_leave(
        employee,
        "UAT LV-03 sick no certificate",
        leaveTypeCode="SICK",
        startDate="2026-08-27",
        endDate="2026-08-27",
    )
    assert req["status"] == "AUTO_REJECTED", req
    assert "attachment" in req["systemNote"].lower() or "certificate" in req["systemNote"].lower(), req


@pytest.mark.uat("LV-04", title="HR approves a submitted leave request", priority="P0")
def test_lv04_approve_seeded_submitted_leave(hr):
    # submit() now auto-decides every new leave (APPROVED / AUTO_REJECTED) — there is no app path
    # that leaves a request in SUBMITTED, so the only SUBMITTED leaves come from the seed and are
    # single-use (approving one is terminal). Make this idempotent against the persistent hosted DB:
    # approve the seeded pending leave if it is still SUBMITTED; if an earlier run already approved
    # it, assert the seeded GLR-0003 leave is APPROVED (the approve path was exercised).
    r = hr.get("/api/leave?from=2026-06-01&to=2026-06-30")
    assert_status(r, 200)
    mine = [req for req in r.json()["requests"] if req["employeeCode"] == "GLR-0003"]
    assert mine, "seeded GLR-0003 June leave is missing"
    submitted = [req for req in mine if req["status"] == "SUBMITTED"]
    if submitted:
        req_id = submitted[0]["id"]
        r = hr.post(f"/api/leave/{req_id}/approve", json={"reviewerNote": "UAT LV-04 approved"})
        assert_status(r, 200)
        assert r.json()["request"]["status"] == "APPROVED", r.text
    else:
        assert any(req["status"] == "APPROVED" for req in mine), mine


@pytest.mark.uat("LV-05", title="Cancel active leave restores balance", priority="P1")
def test_lv05_cancel_restores_balance(hr):
    target = employee_by_code(hr, "GLR-0015")
    before = hr.get(f"/api/leave/balances?employeeId={target['id']}&year=2026").json()["balances"]
    before_personal = next(b for b in before if b["leaveTypeCode"] == "PERSONAL")
    req = submit_leave(
        hr,
        "UAT LV-05 cancel",
        employeeId=target["id"],
        leaveTypeCode="PERSONAL",
        startDate="2026-08-28",
        endDate="2026-08-28",
    )
    assert req["status"] == "APPROVED", req
    r = hr.post(f"/api/leave/{req['id']}/cancel", json={"reviewerNote": "UAT cancel"})
    assert_status(r, 200)
    assert r.json()["request"]["status"] == "CANCELLED"
    after = hr.get(f"/api/leave/balances?employeeId={target['id']}&year=2026").json()["balances"]
    after_personal = next(b for b in after if b["leaveTypeCode"] == "PERSONAL")
    assert money(after_personal["remainingDays"]) == money(before_personal["remainingDays"])


@pytest.mark.uat("LV-06", title="Balances show Sick 30 / Vacation 6 / Personal 3", priority="P1")
def test_lv06_balances(employee):
    r = employee.get("/api/leave/balances?year=2026")
    assert_status(r, 200)
    balances = {b["leaveTypeCode"]: b for b in r.json()["balances"]}
    assert money(balances["SICK"]["annualQuotaDays"]) == money("30")
    assert money(balances["VACATION"]["annualQuotaDays"]) == money("6")
    assert money(balances["PERSONAL"]["annualQuotaDays"]) == money("3")
