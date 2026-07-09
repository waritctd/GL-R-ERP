import pytest

from helpers import (
    approved_ticket,
    assert_status,
    create_ticket,
    mailpit_body,
    mailpit_clear,
    price_proposed_ticket,
    submit_commission,
    submit_leave,
    submit_ot,
    unique,
    wait_for_email,
)


@pytest.mark.uat("NOTIF-01", title="Action creates in-app notification row", priority="P0")
def test_notif01_action_creates_in_app_row(sales):
    before = sales.get("/api/notifications").json()
    before_ids = {n["id"] for n in before}
    invoice = unique("NOTIF01-INV")
    submit_commission(sales, invoice, invoiceDate="2026-08-10")
    r = sales.get("/api/notifications")
    assert_status(r, 200)
    created = [n for n in r.json() if n["id"] not in before_ids]
    assert any(n["type"] == "COMMISSION_SUBMITTED" and invoice in n["message"] for n in created), created


@pytest.mark.uat("NOTIF-02", title="Notification bell unread count", priority="P1")
def test_notif02_bell_ui_manual():
    pytest.skip(reason="manual/UI")


@pytest.mark.uat("NOTIF-03", title="Cross-user mark-read denied", priority="P0")
def test_notif03_cross_user_mark_read_denied(sales, salesmgr):
    commission = submit_commission(sales, unique("NOTIF03-INV"), invoiceDate="2026-08-11")
    r = salesmgr.get("/api/notifications")
    assert_status(r, 200)
    manager_notifications = [
        n for n in r.json()
        if n["type"] == "COMMISSION_PENDING_MANAGER"
        and commission["invoiceDetails"]["invoiceNumber"] in n["message"]
    ]
    assert manager_notifications, r.text
    notification_id = manager_notifications[0]["id"]
    r = sales.patch(f"/api/notifications/{notification_id}/read")
    assert r.status_code == 404, r.text
    still_there = salesmgr.get("/api/notifications").json()
    owner_copy = next(n for n in still_there if n["id"] == notification_id)
    assert owner_copy["read"] is False, owner_copy


# ---------------------------------------------------------------------------
# Email leg (NOTIF-01 says "in-app AND email"). The harness backend uses the real SMTP transport
# pointed at a Mailpit capture server, so these assert an email was actually sent (recipient, sender,
# subject) -- and that it went to the real per-employee address (1-to-many), not a test redirect.
# ---------------------------------------------------------------------------


@pytest.mark.uat("MAIL-01", title="Commission submit emails the sales rep", priority="P0")
def test_mail01_commission_email(sales):
    mailpit_clear()
    invoice = unique("MAIL01-INV")
    submit_commission(sales, invoice, invoiceDate="2026-08-12")
    msg = wait_for_email(to="sales@uat.glr", subject_contains="ส่งคำขอค่าคอมแล้ว")
    assert msg["From"]["Address"] == "job@glr.co.th", msg
    body = mailpit_body(msg["ID"]).get("Text", "")
    assert invoice in body, body
    assert "Redirected for testing" not in body, body  # true 1-to-many, not the override redirect


@pytest.mark.uat("MAIL-02", title="Leave auto-approve emails the employee", priority="P0")
def test_mail02_leave_email(employee):
    mailpit_clear()
    submit_leave(
        employee,
        "UAT MAIL-02 leave",
        leaveTypeCode="VACATION",
        startDate="2026-09-15",
        endDate="2026-09-15",
    )
    msg = wait_for_email(to="employee@uat.glr", subject_contains="คำขอลาได้รับการอนุมัติอัตโนมัติ")
    assert msg["From"]["Address"] == "job@glr.co.th", msg


@pytest.mark.uat("MAIL-03", title="OT submit emails the employee", priority="P1")
def test_mail03_ot_email(employee):
    mailpit_clear()
    submit_ot(
        employee,
        "UAT MAIL-03 ot",
        workDate="2026-09-16",
        plannedStartAt="2026-09-16T18:00:00+07:00",
        plannedEndAt="2026-09-16T20:00:00+07:00",
    )
    msg = wait_for_email(to="employee@uat.glr", subject_contains="ส่งคำขอ OT แล้ว")
    assert msg["From"]["Address"] == "job@glr.co.th", msg


# ---------------------------------------------------------------------------
# Ticket flows previously sent in-app notifications only (NotificationRepository.notifyByRole/
# notifyEmployee, bypassing the email pipeline entirely). TicketService/DepositNoticeService were
# migrated to NotificationService so ticket events now email too, same as commission/OT/leave.
# ---------------------------------------------------------------------------


@pytest.mark.uat("MAIL-04", title="Ticket submit emails import and ceo roles", priority="P0")
def test_mail04_ticket_submit_email(sales):
    mailpit_clear()
    create_ticket(sales, unique("MAIL04"))
    import_msg = wait_for_email(to="import@uat.glr", subject_contains="มีคำขอราคาใหม่")
    ceo_msg = wait_for_email(to="ceo@uat.glr", subject_contains="มีคำขอราคาใหม่")
    assert import_msg["From"]["Address"] == "job@glr.co.th", import_msg
    assert ceo_msg["From"]["Address"] == "job@glr.co.th", ceo_msg


@pytest.mark.uat("MAIL-05", title="Ticket approve emails the ticket creator", priority="P0")
def test_mail05_ticket_approve_email(sales, import_, ceo):
    mailpit_clear()
    approved_ticket(sales, import_, ceo)
    msg = wait_for_email(to="sales@uat.glr", subject_contains="ราคาได้รับการอนุมัติ")
    assert msg["From"]["Address"] == "job@glr.co.th", msg


@pytest.mark.uat("MAIL-06", title="Ticket reject emails the import role", priority="P1")
def test_mail06_ticket_reject_email(sales, import_, ceo):
    mailpit_clear()
    ticket = price_proposed_ticket(sales, import_)
    tid = ticket["summary"]["id"]
    r = ceo.post(f"/api/tickets/{tid}/reject", json={"reason": "UAT MAIL-06 reject"})
    assert_status(r, 200)
    msg = wait_for_email(to="import@uat.glr", subject_contains="ราคาถูกตีกลับ")
    assert msg["From"]["Address"] == "job@glr.co.th", msg


@pytest.mark.uat("MAIL-07", title="Profile request submit emails HR", priority="P0")
def test_mail07_profile_request_submit_email(employee):
    mailpit_clear()
    r = employee.post(
        "/api/profile-requests",
        json={
            "fieldKey": "email",
            "fieldLabel": "อีเมล",
            "oldValue": "employee@uat.glr",
            "newValue": f"{unique('mail07').lower()}@uat-harness.glr",
        },
    )
    assert_status(r, 200)
    msg = wait_for_email(to="hr@uat.glr", subject_contains="มีคำขอแก้ไขข้อมูลพนักงาน")
    assert msg["From"]["Address"] == "job@glr.co.th", msg


@pytest.mark.uat("MAIL-08", title="Profile request approval emails employee", priority="P0")
def test_mail08_profile_request_approval_email(employee, hr):
    r = employee.post(
        "/api/profile-requests",
        json={
            "fieldKey": "address",
            "fieldLabel": "ที่อยู่ปัจจุบัน",
            "oldValue": "",
            "newValue": unique("UAT MAIL-08 address"),
        },
    )
    assert_status(r, 200)
    req_id = r.json()["profileRequest"]["id"]
    mailpit_clear()
    r = hr.patch(f"/api/profile-requests/{req_id}", json={"status": "approved", "reviewerNote": "OK"})
    assert_status(r, 200)
    msg = wait_for_email(to="employee@uat.glr", subject_contains="คำขอแก้ไขข้อมูลได้รับการอนุมัติ")
    assert msg["From"]["Address"] == "job@glr.co.th", msg


@pytest.mark.uat("MAIL-09", title="Profile request rejection emails employee with reason", priority="P1")
def test_mail09_profile_request_rejection_email(employee, hr):
    reason = "Need clearer evidence"
    r = employee.post(
        "/api/profile-requests",
        json={
            "fieldKey": "emergency",
            "fieldLabel": "ผู้ติดต่อฉุกเฉิน",
            "oldValue": "",
            "newValue": "UAT Contact · 0800000909",
        },
    )
    assert_status(r, 200)
    req_id = r.json()["profileRequest"]["id"]
    mailpit_clear()
    r = hr.patch(f"/api/profile-requests/{req_id}", json={"status": "rejected", "reviewerNote": reason})
    assert_status(r, 200)
    msg = wait_for_email(to="employee@uat.glr", subject_contains="คำขอแก้ไขข้อมูลไม่ได้รับการอนุมัติ")
    body = mailpit_body(msg["ID"]).get("Text", "")
    assert reason in body, body
    assert msg["From"]["Address"] == "job@glr.co.th", msg


@pytest.mark.uat("MAIL-10", title="Email template has greeting CTA and signature", priority="P0")
def test_mail10_email_template_structure(employee):
    mailpit_clear()
    submit_leave(
        employee,
        "UAT MAIL-10 template",
        leaveTypeCode="VACATION",
        startDate="2026-09-17",
        endDate="2026-09-17",
    )
    msg = wait_for_email(to="employee@uat.glr", subject_contains="คำขอลาได้รับการอนุมัติอัตโนมัติ")
    assert msg["Subject"].startswith("[GL&R HR] "), msg
    body = mailpit_body(msg["ID"]).get("Text", "")
    assert "เรียน คุณ" in body or "เรียน ท่านผู้ใช้งาน," in body, body
    assert "ดูรายละเอียดในระบบ: https://demo-glr-git-uat-waritctds-projects.vercel.app/leave" in body, body
    assert "ระบบบริหารงานบุคคล GL&R" in body, body
