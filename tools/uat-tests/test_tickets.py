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


# ── Dual-track post-quotation flow (payment_status / fulfillment_status) ────


def quotation_issued_ticket(sales, import_, ceo):
    """Walk a fresh ticket up to status=quotation_issued (both dual-track fields NULL)."""
    ticket = approved_ticket(sales, import_, ceo)
    tid = ticket["summary"]["id"]
    r = sales.post(f"/api/tickets/{tid}/quotation")
    assert_status(r, 200)
    return r.json()["ticket"]


@pytest.mark.uat("TKT-10", title="Sales confirms customer on a quotation_issued ticket", priority="P0")
def test_tkt10_confirm_customer(sales, import_, ceo):
    ticket = quotation_issued_ticket(sales, import_, ceo)
    tid = ticket["summary"]["id"]
    r = sales.post(f"/api/tickets/{tid}/confirm-customer")
    assert_status(r, 200)
    confirmed = r.json()["ticket"]
    assert confirmed["summary"]["paymentStatus"] == "CUSTOMER_CONFIRMED", confirmed["summary"]
    assert any(e["kind"] == "CUSTOMER_CONFIRMED" for e in confirmed["events"]), confirmed["events"]


@pytest.mark.uat("TKT-11", title="Sales issues a deposit notice (payment track)", priority="P0")
def test_tkt11_issue_deposit_notice(sales, import_, ceo):
    ticket = quotation_issued_ticket(sales, import_, ceo)
    tid = ticket["summary"]["id"]
    assert_status(sales.post(f"/api/tickets/{tid}/confirm-customer"), 200)
    r = sales.post(f"/api/tickets/{tid}/deposit-notice")
    assert_status(r, 200)
    notice_issued = r.json()["ticket"]
    assert notice_issued["summary"]["paymentStatus"] == "DEPOSIT_NOTICE_ISSUED", notice_issued["summary"]
    assert any(e["kind"] == "DEPOSIT_NOTICE_ISSUED" for e in notice_issued["events"]), notice_issued["events"]


@pytest.mark.uat("TKT-12", title="Sales confirms deposit paid", priority="P0")
def test_tkt12_confirm_deposit_paid(sales, import_, ceo):
    ticket = quotation_issued_ticket(sales, import_, ceo)
    tid = ticket["summary"]["id"]
    assert_status(sales.post(f"/api/tickets/{tid}/confirm-customer"), 200)
    assert_status(sales.post(f"/api/tickets/{tid}/deposit-notice"), 200)
    r = sales.post(f"/api/tickets/{tid}/deposit-paid")
    assert_status(r, 200)
    paid = r.json()["ticket"]
    assert paid["summary"]["paymentStatus"] == "DEPOSIT_PAID", paid["summary"]
    assert any(e["kind"] == "DEPOSIT_PAID" for e in paid["events"]), paid["events"]


@pytest.mark.uat("TKT-13", title="Import issues an import request (fulfillment track)", priority="P0")
def test_tkt13_issue_import_request(sales, import_, ceo):
    ticket = quotation_issued_ticket(sales, import_, ceo)
    tid = ticket["summary"]["id"]
    assert_status(sales.post(f"/api/tickets/{tid}/confirm-customer"), 200)
    assert_status(sales.post(f"/api/tickets/{tid}/deposit-notice"), 200)
    r = import_.post(f"/api/tickets/{tid}/import-request")
    assert_status(r, 200)
    ir = r.json()["ticket"]
    assert ir["summary"]["fulfillmentStatus"] == "IR_ISSUED", ir["summary"]
    assert any(e["kind"] == "IR_ISSUED" for e in ir["events"]), ir["events"]


@pytest.mark.uat(
    "TKT-14",
    title="Fulfillment track walk: IR sent -> shipping -> goods received, auto AWAITING_FINAL_PAYMENT flip",
    priority="P0",
)
def test_tkt14_fulfillment_walk_and_auto_flip(sales, import_, ceo):
    ticket = quotation_issued_ticket(sales, import_, ceo)
    tid = ticket["summary"]["id"]
    assert_status(sales.post(f"/api/tickets/{tid}/confirm-customer"), 200)
    assert_status(sales.post(f"/api/tickets/{tid}/deposit-notice"), 200)
    # Fulfillment track requires paymentStatus=DEPOSIT_NOTICE_ISSUED exactly (not DEPOSIT_PAID),
    # so import-request must fire before deposit-paid.
    assert_status(import_.post(f"/api/tickets/{tid}/import-request"), 200)
    assert_status(sales.post(f"/api/tickets/{tid}/deposit-paid"), 200)

    r = import_.post(f"/api/tickets/{tid}/ir-sent")
    assert_status(r, 200)
    assert r.json()["ticket"]["summary"]["fulfillmentStatus"] == "IR_SENT"

    r = import_.post(f"/api/tickets/{tid}/shipping")
    assert_status(r, 200)
    assert r.json()["ticket"]["summary"]["fulfillmentStatus"] == "SHIPPING"

    r = import_.post(f"/api/tickets/{tid}/goods-received")
    assert_status(r, 200)
    received = r.json()["ticket"]
    assert received["summary"]["fulfillmentStatus"] == "GOODS_RECEIVED", received["summary"]
    # deposit was paid before goods-received -> payment track auto-flips
    assert received["summary"]["paymentStatus"] == "AWAITING_FINAL_PAYMENT", received["summary"]
    assert any(e["kind"] == "GOODS_RECEIVED" for e in received["events"]), received["events"]
    assert any(e["kind"] == "AWAITING_FINAL_PAYMENT" for e in received["events"]), received["events"]


@pytest.mark.uat("TKT-15", title="Sales confirms final payment -> FULLY_PAID", priority="P0")
def test_tkt15_confirm_final_payment(sales, import_, ceo):
    ticket = quotation_issued_ticket(sales, import_, ceo)
    tid = ticket["summary"]["id"]
    assert_status(sales.post(f"/api/tickets/{tid}/confirm-customer"), 200)
    assert_status(sales.post(f"/api/tickets/{tid}/deposit-notice"), 200)
    assert_status(import_.post(f"/api/tickets/{tid}/import-request"), 200)
    assert_status(sales.post(f"/api/tickets/{tid}/deposit-paid"), 200)
    assert_status(import_.post(f"/api/tickets/{tid}/ir-sent"), 200)
    assert_status(import_.post(f"/api/tickets/{tid}/shipping"), 200)
    assert_status(import_.post(f"/api/tickets/{tid}/goods-received"), 200)

    r = sales.post(f"/api/tickets/{tid}/final-payment")
    assert_status(r, 200)
    paid = r.json()["ticket"]
    assert paid["summary"]["paymentStatus"] == "FULLY_PAID", paid["summary"]
    assert any(e["kind"] == "FULLY_PAID" for e in paid["events"]), paid["events"]

    # Fully closable now: FULLY_PAID + GOODS_RECEIVED on a quotation_issued ticket.
    r = sales.post(f"/api/tickets/{tid}/close")
    assert_status(r, 200)
    assert r.json()["ticket"]["summary"]["status"] == "closed"


@pytest.mark.uat("TKT-16", title="Download the remaining invoice for a quotation_issued ticket", priority="P0")
def test_tkt16_remaining_invoice_download(sales, import_, ceo):
    ticket = quotation_issued_ticket(sales, import_, ceo)
    tid = ticket["summary"]["id"]
    r = sales.get(f"/api/tickets/{tid}/remaining-invoice/file")
    assert_status(r, 200)
    assert r.headers["Content-Type"].startswith(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ), r.headers
    assert len(r.content) > 0, "remaining invoice file was empty"


@pytest.mark.uat("TKT-17", title="CEO manual price override on a price_proposed item", priority="P0")
def test_tkt17_manual_price_override(sales, import_, ceo):
    ticket = price_proposed_ticket(sales, import_)
    tid = ticket["summary"]["id"]
    item_id = ticket["items"][0]["id"]

    r = ceo.put(
        f"/api/tickets/{tid}/items/{item_id}/price-override",
        json={"manualPrice": "999.50", "reason": "UAT manual override"},
    )
    assert_status(r, 200)
    overridden = r.json()["ticket"]
    item = next(i for i in overridden["items"] if i["id"] == item_id)
    assert item["manualPrice"] == "999.50" or float(item["manualPrice"]) == 999.50, item
    assert item["manualOverrideReason"] == "UAT manual override", item

    # Outside price_proposed, the override is rejected (409).
    approved = ceo.post(f"/api/tickets/{tid}/approve")
    assert_status(approved, 200)
    r2 = ceo.put(
        f"/api/tickets/{tid}/items/{item_id}/price-override",
        json={"manualPrice": "111.00", "reason": "should be rejected"},
    )
    assert_status(r2, 409)


@pytest.mark.uat("TKT-18", title="Unit-basis SQM item round-trips", priority="P1")
def test_tkt18_unit_basis_sqm_roundtrip(sales, import_):
    ticket = create_ticket(sales, unique("TKT18"))
    tid = ticket["summary"]["id"]
    assert_status(import_.post(f"/api/tickets/{tid}/pickup"), 200)
    r = import_.post(
        f"/api/tickets/{tid}/propose-price",
        json={
            "items": [
                ticket_item(
                    model="UAT-SQM-ITEM",
                    unitBasis="SQM",
                    # qty is NOT NULL at the DB level even for SQM-basis items; the real
                    # frontend always sends Number(item.qty) || 0 rather than a literal
                    # null when unit_basis=SQM (qty_sqm is the primary quantity instead).
                    qty="0",
                    qtySqm="42.75",
                )
            ],
            "note": "UAT SQM unit-basis",
        },
    )
    assert_status(r, 200)
    proposed = r.json()["ticket"]
    item = next(i for i in proposed["items"] if i["model"] == "UAT-SQM-ITEM")
    assert item["unitBasis"] == "SQM", item
    assert float(item["qtySqm"]) == 42.75, item
