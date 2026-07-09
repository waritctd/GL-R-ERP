import pytest

from helpers import approved_ticket, assert_status, create_ticket, price_proposed_ticket, ticket_item, unique


def issue_deposit_notice_for_ticket(sales, ticket):
    tid = ticket["summary"]["id"]
    r = sales.post(
        f"/api/tickets/{tid}/deposit-notice/draft",
        json={
            "customerName": "UAT Harness Customer",
            "customerAddress": "Bangkok",
            "projectName": "UAT Project",
            "reference": "UAT",
            "depositPercent": "0.50",
            "notes": ["UAT automated document"],
        },
    )
    assert_status(r, 200)
    doc = r.json()["depositNotice"]
    r = sales.post(f"/api/deposit-notices/{doc['id']}/issue")
    assert_status(r, 200)
    return r.json()["depositNotice"]


@pytest.mark.uat("TKT-01", title="Sales creates and submits ticket", priority="P0")
def test_tkt01_create_submit(sales):
    ticket = create_ticket(sales, unique("TKT01"))
    assert ticket["summary"]["status"] == "submitted", ticket
    tid = ticket["summary"]["id"]
    submitted = sales.get(f"/api/tickets/{tid}").json()["ticket"]
    assert any(e["kind"] == "SUBMITTED" for e in submitted["events"]), submitted["events"]


@pytest.mark.uat("TKT-02", title="Import picks up and proposes price", priority="P0")
def test_tkt02_pickup_propose_price(sales, import_):
    ticket = price_proposed_ticket(sales, import_)
    assert ticket["summary"]["status"] == "price_proposed", ticket
    assert any(e["kind"] == "PRICE_PROPOSED" for e in ticket["events"]), ticket["events"]


@pytest.mark.uat("TKT-03", title="CEO approves proposed price", priority="P0")
def test_tkt03_approve_price(sales, import_, ceo):
    ticket = approved_ticket(sales, import_, ceo)
    assert ticket["summary"]["status"] == "approved", ticket
    assert all(item["approvedPrice"] is not None for item in ticket["items"]), ticket["items"]


@pytest.mark.uat("TKT-04", title="CEO rejects proposed price", priority="P1")
def test_tkt04_reject_price(sales, import_, ceo):
    ticket = price_proposed_ticket(sales, import_)
    tid = ticket["summary"]["id"]
    r = ceo.post(f"/api/tickets/{tid}/reject", json={"reason": "UAT reject price"})
    assert_status(r, 200)
    rejected = r.json()["ticket"]
    assert rejected["summary"]["status"] == "in_review", rejected
    assert any(e["kind"] == "REJECTED" and e["message"] == "UAT reject price" for e in rejected["events"])


@pytest.mark.uat("TKT-05", title="Edit items after submission flags has_edits", priority="P0")
def test_tkt05_edit_items_has_edits(sales):
    ticket = create_ticket(sales, unique("TKT05"))
    tid = ticket["summary"]["id"]
    r = sales.patch(
        f"/api/tickets/{tid}/items",
        json={"items": [ticket_item(model="UAT-EDIT", proposedPrice=None)], "note": "UAT edit"},
    )
    assert_status(r, 200)
    edited = r.json()["ticket"]
    assert edited["summary"]["hasEdits"] is True, edited
    assert any(e["kind"] == "EDITED" for e in edited["events"]), edited["events"]


@pytest.mark.uat("TKT-06", title="Issue quotation with running number and downloadable file", priority="P0")
def test_tkt06_issue_quotation_file(sales, import_, ceo):
    ticket = approved_ticket(sales, import_, ceo)
    tid = ticket["summary"]["id"]
    r = sales.post(f"/api/tickets/{tid}/quotation")
    assert_status(r, 200)
    quoted = r.json()["ticket"]
    quotation = quoted["quotation"]
    assert quoted["summary"]["status"] == "quotation_issued", quoted
    assert quotation["number"], quotation
    r = sales.get(f"/api/tickets/{tid}/quotations/{quotation['id']}/file?format=pdf")
    assert_status(r, 200)
    assert r.headers["Content-Type"].startswith("application/pdf"), r.headers
    assert r.content.startswith(b"%PDF"), r.content[:20]


@pytest.mark.uat("TKT-06", title="PDF visual render inspection", priority="P0")
def test_tkt06_pdf_visual_manual():
    pytest.skip(reason="manual/UI")


@pytest.mark.uat("TKT-07", title="Revision request retains original document", priority="P0")
def test_tkt07_revision_event(sales, import_, ceo):
    ticket = approved_ticket(sales, import_, ceo)
    doc = issue_deposit_notice_for_ticket(sales, ticket)
    assert doc["docNumber"], doc
    tid = ticket["summary"]["id"]
    r = sales.post(
        f"/api/tickets/{tid}/revision",
        json={"scope": "QTY_OR_NOTE", "reason": "UAT revision note"},
    )
    assert_status(r, 200)
    revised = r.json()["ticket"]
    assert any(e["kind"] == "REVISION_REQUESTED" for e in revised["events"]), revised["events"]
    docs = sales.get(f"/api/tickets/{tid}/deposit-notices").json()["depositNotices"]
    assert any(existing["id"] == doc["id"] and existing["status"] == "ISSUED" for existing in docs), docs


@pytest.mark.uat("TKT-08", title="Comment recorded as event", priority="P1")
def test_tkt08_comment_event(sales):
    ticket = create_ticket(sales, unique("TKT08"))
    tid = ticket["summary"]["id"]
    r = sales.post(f"/api/tickets/{tid}/comments", json={"message": "UAT comment"})
    assert_status(r, 200)
    commented = r.json()["ticket"]
    assert any(e["kind"] == "COMMENTED" and e["message"] == "UAT comment" for e in commented["events"])


@pytest.mark.uat("TKT-09", title="Close a document-issued ticket", priority="P0")
def test_tkt09_close_ticket(sales, import_, ceo):
    ticket = approved_ticket(sales, import_, ceo)
    issue_deposit_notice_for_ticket(sales, ticket)
    tid = ticket["summary"]["id"]
    r = sales.post(f"/api/tickets/{tid}/close")
    assert_status(r, 200)
    closed = r.json()["ticket"]
    assert closed["summary"]["status"] == "closed", closed
    assert any(e["kind"] == "CLOSED" for e in closed["events"]), closed["events"]
