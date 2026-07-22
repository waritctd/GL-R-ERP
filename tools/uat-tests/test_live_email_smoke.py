"""Live-email smoke pass: fires real notification emails through Resend for human inbox review.

Only runs under `./run.sh --live-email` (docker-compose.live-email.yml swaps the backend mail
transport to Resend and redirects all recipients to LIVE_EMAIL_TO). These tests only assert the
triggering HTTP calls succeeded; actual delivery is verified by checking the inbox.
"""
import pytest

from helpers import (
    approved_ticket,
    assert_status,
    create_ticket,
    price_proposed_ticket,
    submit_commission,
    submit_leave,
    submit_ot,
    unique,
)


def submitted_leave_id(hr, employee_code):
    r = hr.get("/api/leave?from=2026-06-01&to=2026-06-30&status=SUBMITTED")
    assert_status(r, 200)
    matches = [req for req in r.json()["requests"] if req["employeeCode"] == employee_code]
    assert matches, r.text
    return matches[0]["id"]


def issue_deposit_notice(sales, ticket):
    tid = ticket["summary"]["id"]
    r = sales.post(
        f"/api/tickets/{tid}/deposit-notice/draft",
        json={
            "customerName": "UAT Live Customer",
            "customerAddress": "Bangkok",
            "projectName": "UAT Live Project",
            "reference": "UAT live",
            "depositPercent": "0.50",
            "notes": ["UAT live-email document"],
        },
    )
    assert_status(r, 200)
    doc = r.json()["depositNotice"]
    r = sales.post(f"/api/deposit-notices/{doc['id']}/issue")
    assert_status(r, 200)
    return r.json()["depositNotice"]


@pytest.mark.live_email
def test_live_leave_auto_approved_employee_and_manager(sales):
    submit_leave(sales, "UAT live-email leave auto-approved", leaveTypeCode="VACATION",
                 startDate="2026-10-01", endDate="2026-10-01")
    print("\n>> fired: leave AUTO_APPROVED -> employee + manager heads-up")


@pytest.mark.live_email
def test_live_leave_auto_rejected(employee):
    submit_leave(employee, "UAT live-email leave auto-rejected", leaveTypeCode="SICK",
                 startDate="2026-10-02", endDate="2026-10-02")
    print("\n>> fired: leave AUTO_REJECTED -> employee")


@pytest.mark.live_email
def test_live_leave_manual_approved(hr):
    req_id = submitted_leave_id(hr, "GLR-0003")
    r = hr.post(f"/api/leave/{req_id}/approve", json={"reviewerNote": "UAT live approve"})
    assert_status(r, 200)
    print("\n>> fired: leave APPROVED -> employee")


@pytest.mark.live_email
def test_live_overtime_submitted_employee_and_manager(sales):
    submit_ot(sales, "UAT live-email OT submitted", workDate="2026-10-03",
              plannedStartAt="2026-10-03T18:00:00+07:00",
              plannedEndAt="2026-10-03T20:00:00+07:00")
    print("\n>> fired: OT SUBMITTED -> employee + manager")


@pytest.mark.live_email
def test_live_overtime_manager_approved_employee_and_ceo(sales, salesmgr):
    req = submit_ot(sales, "UAT live-email OT manager-approved", workDate="2026-10-04",
                    plannedStartAt="2026-10-04T18:00:00+07:00",
                    plannedEndAt="2026-10-04T20:00:00+07:00")
    r = salesmgr.post(f"/api/overtime/{req['id']}/approve", json={"reviewerNote": "manager ok"})
    assert_status(r, 200)
    print("\n>> fired: OT MANAGER_APPROVED -> employee + CEO")


@pytest.mark.live_email
def test_live_overtime_approved_employee_and_manager(sales, salesmgr, ceo):
    req = submit_ot(sales, "UAT live-email OT approved", workDate="2026-10-05",
                    plannedStartAt="2026-10-05T18:00:00+07:00",
                    plannedEndAt="2026-10-05T20:00:00+07:00")
    r = salesmgr.post(f"/api/overtime/{req['id']}/approve", json={"reviewerNote": "manager ok"})
    assert_status(r, 200)
    r = ceo.post(f"/api/overtime/{req['id']}/approve", json={"reviewerNote": "ceo ok"})
    assert_status(r, 200)
    print("\n>> fired: OT APPROVED -> employee + approving manager")


@pytest.mark.live_email
def test_live_overtime_rejected(sales, salesmgr):
    req = submit_ot(sales, "UAT live-email OT rejected", workDate="2026-10-06",
                    plannedStartAt="2026-10-06T18:00:00+07:00",
                    plannedEndAt="2026-10-06T20:00:00+07:00")
    r = salesmgr.post(f"/api/overtime/{req['id']}/reject", json={"reviewerNote": "UAT live reject"})
    assert_status(r, 200)
    print("\n>> fired: OT REJECTED -> employee")


@pytest.mark.live_email
def test_live_commission_submitted_and_pending_manager(sales, account):
    # Slice A2 (handoff 98): commission creation is account/sales_manager/ceo only now.
    submit_commission(account, unique("LIVE-COM-SUB"), sales_rep_id=sales.user["employeeId"], invoiceDate="2026-10-07")
    print("\n>> fired: commission SUBMITTED -> sales rep; PENDING_MANAGER -> sales manager")


@pytest.mark.live_email
def test_live_commission_manager_approved_and_pending_ceo(sales, salesmgr, account):
    commission = submit_commission(
        account, unique("LIVE-COM-MGR"), sales_rep_id=sales.user["employeeId"], invoiceDate="2026-10-08"
    )
    r = salesmgr.post(f"/api/commissions/{commission['id']}/approve")
    assert_status(r, 200)
    print("\n>> fired: commission MANAGER_APPROVED -> sales rep; PENDING_CEO -> CEO")


@pytest.mark.live_email
def test_live_commission_approved(sales, salesmgr, ceo, account):
    commission = submit_commission(
        account, unique("LIVE-COM-APP"), sales_rep_id=sales.user["employeeId"], invoiceDate="2026-10-09"
    )
    r = salesmgr.post(f"/api/commissions/{commission['id']}/approve")
    assert_status(r, 200)
    r = ceo.post(f"/api/commissions/{commission['id']}/approve")
    assert_status(r, 200)
    print("\n>> fired: commission APPROVED -> sales rep + approving manager")


@pytest.mark.live_email
def test_live_commission_rejected(sales, salesmgr, account):
    commission = submit_commission(
        account, unique("LIVE-COM-REJ"), sales_rep_id=sales.user["employeeId"], invoiceDate="2026-10-10"
    )
    r = salesmgr.post(f"/api/commissions/{commission['id']}/reject",
                      json={"reviewerNote": "UAT live reject"})
    assert_status(r, 200)
    print("\n>> fired: commission REJECTED -> sales rep")


@pytest.mark.live_email
def test_live_ticket_submitted(sales):
    create_ticket(sales, unique("LIVE-TKT-SUB"))
    print("\n>> fired: ticket SUBMITTED -> import + CEO")


@pytest.mark.live_email
def test_live_ticket_price_proposed(sales, import_):
    price_proposed_ticket(sales, import_)
    print("\n>> fired: ticket PRICE_PROPOSED -> CEO")


@pytest.mark.live_email
def test_live_ticket_approved(sales, import_, ceo):
    approved_ticket(sales, import_, ceo)
    print("\n>> fired: ticket APPROVED -> ticket creator")


@pytest.mark.live_email
def test_live_ticket_rejected(sales, import_, ceo):
    ticket = price_proposed_ticket(sales, import_)
    tid = ticket["summary"]["id"]
    r = ceo.post(f"/api/tickets/{tid}/reject", json={"reason": "UAT live reject price"})
    assert_status(r, 200)
    print("\n>> fired: ticket REJECTED -> import")


@pytest.mark.live_email
def test_live_deposit_revision_requested(sales, import_, ceo):
    ticket = approved_ticket(sales, import_, ceo)
    issue_deposit_notice(sales, ticket)
    tid = ticket["summary"]["id"]
    r = sales.post(f"/api/tickets/{tid}/revision",
                   json={"scope": "QTY_OR_NOTE", "reason": "UAT live revision"})
    assert_status(r, 200)
    print("\n>> fired: deposit notice REVISION_REQUESTED -> import/CEO role")


@pytest.mark.live_email
def test_live_profile_request_submitted(employee):
    r = employee.post(
        "/api/profile-requests",
        json={
            "fieldKey": "email",
            "fieldLabel": "อีเมล",
            "oldValue": "employee@uat.glr",
            "newValue": f"{unique('live-profile').lower()}@uat-harness.glr",
        },
    )
    assert_status(r, 200)
    print("\n>> fired: profile PROFILE_REQUEST_SUBMITTED -> HR")


@pytest.mark.live_email
def test_live_profile_request_approved(employee, hr):
    r = employee.post(
        "/api/profile-requests",
        json={
            "fieldKey": "address",
            "fieldLabel": "ที่อยู่ปัจจุบัน",
            "oldValue": "",
            "newValue": unique("UAT live address"),
        },
    )
    assert_status(r, 200)
    req_id = r.json()["profileRequest"]["id"]
    r = hr.patch(f"/api/profile-requests/{req_id}", json={"status": "approved", "reviewerNote": "OK"})
    assert_status(r, 200)
    print("\n>> fired: profile PROFILE_REQUEST_APPROVED -> employee")


@pytest.mark.live_email
def test_live_profile_request_rejected(employee, hr):
    r = employee.post(
        "/api/profile-requests",
        json={
            "fieldKey": "emergency",
            "fieldLabel": "ผู้ติดต่อฉุกเฉิน",
            "oldValue": "",
            "newValue": "UAT Live Contact · 0800000909",
        },
    )
    assert_status(r, 200)
    req_id = r.json()["profileRequest"]["id"]
    r = hr.patch(f"/api/profile-requests/{req_id}",
                 json={"status": "rejected", "reviewerNote": "Need clearer evidence"})
    assert_status(r, 200)
    print("\n>> fired: profile PROFILE_REQUEST_REJECTED -> employee")
