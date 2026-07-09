import pytest

from helpers import assert_status, money, submit_commission, unique


@pytest.mark.uat("COM-01", title="Submit commission with invoice upload", priority="P0")
def test_com01_submit_with_invoice_upload(sales):
    commission = submit_commission(sales, unique("COM01-INV"))
    assert commission["status"] == "SUBMITTED", commission
    assert commission["kind"] == "SALE", commission
    assert commission["invoiceDetails"]["invoiceAttachmentId"] is not None, commission
    assert money(commission["actualReceived"]) == money("240000.00")
    assert money(commission["commissionableBase"]) == money("224299.07")


@pytest.mark.uat("COM-02", title="Simulator calculates without saving", priority="P1")
def test_com02_simulator_no_save(sales):
    before = sales.get("/api/commissions?payrollMonth=2026-09").json()["commissions"]
    r = sales.post(
        "/api/commissions/simulator",
        json={
            "payrollMonth": "2026-09-01",
            "grossAmount": "250000.00",
            "bankFees": "0.00",
            "suspenseVat": "0.00",
            "transportFee": "0.00",
            "cutFee": "0.00",
            "shortfall": "0.00",
        },
    )
    assert_status(r, 200)
    sim = r.json()["simulation"]
    assert money(sim["actualReceived"]) == money("250000.00"), sim
    assert money(sim["commissionableBase"]) == money("233644.86"), sim
    after = sales.get("/api/commissions?payrollMonth=2026-09").json()["commissions"]
    assert len(after) == len(before), (before, after)


@pytest.mark.uat("COM-03", title="Sales manager approval -> MANAGER_APPROVED", priority="P0")
def test_com03_manager_approves(sales, salesmgr):
    commission = submit_commission(sales, unique("COM03-INV"), invoiceDate="2026-08-06")
    r = salesmgr.post(f"/api/commissions/{commission['id']}/approve")
    assert_status(r, 200)
    approved = r.json()["commission"]
    assert approved["status"] == "MANAGER_APPROVED", approved
    assert approved["ceoApprovedBy"] is None, approved


@pytest.mark.uat("COM-04", title="Approved commission appears in payroll-ready feed", priority="P0")
def test_com04_payroll_ready_feed(hr):
    r = hr.get("/api/commissions/payroll-ready?payrollMonth=2026-06")
    assert_status(r, 200)
    summary = r.json()["summary"]
    assert summary["status"] == "PAYROLL_READY", summary
    assert money(summary["totalCommissionAmount"]) >= money("600.00"), summary
    assert any(money(rep["commissionAmount"]) == money("600.00") for rep in summary["salesReps"]), summary


@pytest.mark.uat("COM-05", title="Clawback creates negative record", priority="P1")
def test_com05_clawback_negative(sales, salesmgr, ceo):
    commission = submit_commission(sales, unique("COM05-INV"), invoiceDate="2026-08-07")
    r = salesmgr.post(f"/api/commissions/{commission['id']}/approve")
    assert_status(r, 200)
    r = ceo.post(f"/api/commissions/{commission['id']}/approve")
    assert_status(r, 200)
    r = salesmgr.post(f"/api/commissions/{commission['id']}/clawback", json={"reason": "UAT clawback"})
    assert_status(r, 200)
    clawback = r.json()["commission"]
    assert clawback["kind"] == "CLAWBACK", clawback
    assert money(clawback["commissionableBase"]) < money("0"), clawback
    assert clawback["cancellationOfId"] == commission["id"], clawback


@pytest.mark.uat("COM-06", title="CEO second sign-off finalizes commission", priority="P0")
def test_com06_ceo_second_signoff(sales, salesmgr, ceo):
    commission = submit_commission(sales, unique("COM06-INV"), invoiceDate="2026-08-08")
    r = salesmgr.post(f"/api/commissions/{commission['id']}/approve")
    assert_status(r, 200)
    manager_approved = r.json()["commission"]
    assert manager_approved["status"] == "MANAGER_APPROVED", manager_approved
    r = ceo.post(f"/api/commissions/{commission['id']}/approve")
    assert_status(r, 200)
    final = r.json()["commission"]
    assert final["status"] == "APPROVED", final
    assert final["managerApprovedBy"] is not None and final["ceoApprovedBy"] is not None, final


@pytest.mark.uat("COM-07", title="Reject commission -> REJECTED", priority="P0")
def test_com07_reject(sales, salesmgr):
    commission = submit_commission(sales, unique("COM07-INV"), invoiceDate="2026-08-09")
    r = salesmgr.post(
        f"/api/commissions/{commission['id']}/reject",
        json={"reviewerNote": "UAT commission reject"},
    )
    assert_status(r, 200)
    rejected = r.json()["commission"]
    assert rejected["status"] == "REJECTED", rejected
    assert rejected["rejectionReason"] == "UAT commission reject", rejected
