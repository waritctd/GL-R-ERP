"""Live-email smoke pass: fires one real notification per module through Resend, for a human to
verify by eye in their inbox. Only runs under `./run.sh --live-email` (docker-compose.live-email.yml
swaps the backend's mail transport to the real Resend API + APP_MAIL_OVERRIDE_TO=LIVE_EMAIL_TO).

Not part of the deterministic pass/fail UAT suite (@pytest.mark.uat / uat-results.md): these tests
only assert the triggering HTTP call succeeded (200) - actual inbox delivery can't be asserted
without reading a real mailbox, so it's a fire-and-check-by-eye pass, not a go-live gate. See
tools/uat-tests/PLAN.md and README.md for how this differs from the Mailpit-backed MAIL-* tests.
"""
import pytest

from helpers import approved_ticket, create_ticket, submit_commission, submit_leave, submit_ot, unique


@pytest.mark.live_email
def test_live_leave_email(employee):
    submit_leave(employee, "UAT live-email leave", leaveTypeCode="VACATION",
                startDate="2026-10-01", endDate="2026-10-01")
    print("\n>> fired: leave auto-approve email -> check your inbox for 'คำขอลาได้รับการอนุมัติอัตโนมัติ'")


@pytest.mark.live_email
def test_live_overtime_email(employee):
    submit_ot(employee, "UAT live-email OT", workDate="2026-10-02",
             plannedStartAt="2026-10-02T18:00:00+07:00", plannedEndAt="2026-10-02T20:00:00+07:00")
    print("\n>> fired: OT submit email -> check your inbox for 'ส่งคำขอ OT แล้ว'")


@pytest.mark.live_email
def test_live_commission_email(sales):
    submit_commission(sales, unique("LIVE-INV"), invoiceDate="2026-10-03")
    print("\n>> fired: commission submit email -> check your inbox for 'ส่งคำขอค่าคอมแล้ว'")


@pytest.mark.live_email
def test_live_ticket_submit_email(sales):
    create_ticket(sales, unique("LIVE-TKT"))
    print("\n>> fired: ticket submit email (import+ceo fan-out) -> check your inbox for 'มีคำขอราคาใหม่'")


@pytest.mark.live_email
def test_live_ticket_approve_email(sales, import_, ceo):
    approved_ticket(sales, import_, ceo)
    print("\n>> fired: ticket approve email -> check your inbox for 'ราคาได้รับการอนุมัติ'")
