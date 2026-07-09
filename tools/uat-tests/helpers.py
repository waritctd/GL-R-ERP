import itertools
import os
import time
from decimal import Decimal

import requests

from glrclient import GlrClient


_counter = itertools.count(1)

# Mailpit REST API (SMTP capture running in the harness stack). Used by the MAIL-* email tests.
MAILPIT_URL = os.environ.get("MAILPIT_URL", "http://localhost:8025")


def mailpit_clear():
    """Delete all captured messages so a subsequent wait_for_email() is unambiguous."""
    requests.delete(f"{MAILPIT_URL}/api/v1/messages", timeout=15)


def wait_for_email(to=None, subject_contains=None, timeout=15):
    """Poll Mailpit until a message matching `to` / `subject_contains` appears (email is @Async /
    after-commit, so it lands a moment after the triggering call). Returns the matching message dict
    (Mailpit summary: From{Address}, To[{Address}], Subject). Raises AssertionError on timeout."""
    deadline = time.time() + timeout
    seen = []
    while time.time() < deadline:
        r = requests.get(f"{MAILPIT_URL}/api/v1/messages?limit=200", timeout=15)
        r.raise_for_status()
        seen = r.json().get("messages", [])
        for m in seen:
            addrs = {a.get("Address", "").lower() for a in m.get("To", [])}
            if to is not None and to.lower() not in addrs:
                continue
            if subject_contains is not None and subject_contains not in (m.get("Subject") or ""):
                continue
            return m
        time.sleep(0.5)
    raise AssertionError(
        f"no email matching to={to!r} subject~={subject_contains!r} within {timeout}s; "
        f"captured={[(m.get('Subject'), [a.get('Address') for a in m.get('To', [])]) for m in seen]}"
    )


def mailpit_body(message_id):
    """Full message (incl. Text body) for a captured message ID."""
    r = requests.get(f"{MAILPIT_URL}/api/v1/message/{message_id}", timeout=15)
    r.raise_for_status()
    return r.json()


def unique(prefix):
    return f"{prefix}-{int(time.time() * 1000)}-{next(_counter)}"


def assert_status(response, status):
    assert response.status_code == status, (
        f"expected HTTP {status}, got {response.status_code}: {response.text[:500]}"
    )


def money(value):
    return Decimal(str(value)).quantize(Decimal("0.01"))


def employee_by_code(hr, code):
    r = hr.get(f"/api/employees?search={code}&page=0&size=5")
    assert_status(r, 200)
    matches = [e for e in r.json()["employees"] if e["code"] == code]
    assert matches, f"employee {code} not found in {r.text[:500]}"
    return matches[0]


def create_employee(hr, marker, **overrides):
    payload = {
        "code": f"UAT-{marker}"[:20],
        "nameTh": f"ทดสอบ {marker}",
        "nameEn": f"UAT {marker}",
        "email": f"{marker.lower()}@uat-harness.glr",
        "phone": "0800000000",
        "divisionId": "GA",
        "divisionTh": "GA-ธุรการทั่วไป",
        "departmentTh": "แผนกธุรการ",
        "positionTh": "เจ้าหน้าที่ธุรการ",
        "level": "O2",
        "statusId": "ACT",
        "salary": "21000.00",
        "hireDate": "2026-01-15",
    }
    payload.update(overrides)
    r = hr.post("/api/employees", json=payload)
    assert_status(r, 200)
    return r.json()["employee"]


def create_throwaway_login(hr, base_url, marker):
    employee = create_employee(hr, marker)
    r = hr.post(f"/api/employees/{employee['id']}/reset-password")
    assert_status(r, 200)
    password = r.json()["temporaryPassword"]
    return employee, employee["email"], password


def login_client(base_url, email, password):
    client = GlrClient(base_url)
    client.login(email, password)
    return client


def self_employee(client):
    user = client.get("/api/auth/me").json()["user"]
    r = client.get(f"/api/employees/{user['employeeId']}")
    assert_status(r, 200)
    return r.json()["employee"]


def submit_ot(client, reason, **overrides):
    payload = {
        "employeeId": None,
        "workDate": "2026-08-20",
        "plannedStartAt": "2026-08-20T18:00:00+07:00",
        "plannedEndAt": "2026-08-20T20:00:00+07:00",
        "dayType": "WORKDAY",
        "reason": reason,
    }
    payload.update(overrides)
    r = client.post("/api/overtime", json=payload)
    assert_status(r, 200)
    return r.json()["request"]


def submit_leave(client, reason, **overrides):
    payload = {
        "employeeId": None,
        "leaveTypeCode": "VACATION",
        "startDate": "2026-08-24",
        "endDate": "2026-08-24",
        "reason": reason,
    }
    payload.update(overrides)
    r = client.post("/api/leave", json=payload)
    assert_status(r, 200)
    return r.json()["request"]


def ticket_item(**overrides):
    item = {
        "brand": "GLR Tile",
        "model": "UAT-6060",
        "color": "Ivory",
        "texture": "Matt",
        "size": "60x60",
        "factory": "Foshan A",
        "qty": "10.00",
        "qtySqm": "10.00",
        "rawPrice": "10.00",
        "rawCurrency": "USD",
        "rawUnit": "sqm",
        "proposedPrice": "1200.00",
        "currency": "THB",
    }
    item.update(overrides)
    return item


def create_ticket(sales, title=None):
    r = sales.post(
        "/api/tickets",
        json={
            "title": title or unique("UAT ticket"),
            "priority": "NORMAL",
            "customerName": "UAT Harness Customer",
            "note": "UAT automated harness",
            "items": [ticket_item(proposedPrice=None)],
        },
    )
    assert_status(r, 200)
    return r.json()["ticket"]


def price_proposed_ticket(sales, import_):
    ticket = create_ticket(sales)
    tid = ticket["summary"]["id"]
    r = import_.post(f"/api/tickets/{tid}/pickup")
    assert_status(r, 200)
    r = import_.post(
        f"/api/tickets/{tid}/propose-price",
        json={"items": [ticket_item()], "note": "UAT proposed price"},
    )
    assert_status(r, 200)
    return r.json()["ticket"]


def approved_ticket(sales, import_, ceo):
    ticket = price_proposed_ticket(sales, import_)
    tid = ticket["summary"]["id"]
    r = ceo.post(f"/api/tickets/{tid}/approve")
    assert_status(r, 200)
    return r.json()["ticket"]


def tiny_pdf_bytes():
    return (
        b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
        b"2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj\n"
        b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 72 72]>>endobj\n"
        b"trailer<</Root 1 0 R>>\n%%EOF\n"
    )


def submit_commission(client, invoice_number=None, **overrides):
    data = {
        "invoiceNumber": invoice_number or unique("UAT-INV"),
        "invoiceDate": "2026-08-05",
        "grossAmount": "240000.00",
        "bankFees": "0.00",
        "suspenseVat": "0.00",
        "transportFee": "0.00",
        "cutFee": "0.00",
        "shortfall": "0.00",
    }
    data.update(overrides)
    files = {
        "invoiceAttachment": ("invoice.pdf", tiny_pdf_bytes(), "application/pdf"),
    }
    r = client.post("/api/commissions", data=data, files=files)
    assert_status(r, 200)
    return r.json()["commission"]


def payroll_preview(client, month, inputs=None):
    r = client.post("/api/payroll/preview", json={"payrollMonth": month, "inputs": inputs or []})
    assert_status(r, 200)
    return r.json()["period"]


def payroll_line(period, code):
    matches = [line for line in period["lines"] if line["employeeCode"] == code]
    assert matches, f"payroll line for {code} missing"
    return matches[0]
