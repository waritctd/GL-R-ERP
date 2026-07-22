import uuid

import pytest

from helpers import (
    assert_status,
    confirm_order_and_issue_deposit_notice,
    create_ticket,
    drive_to_closed_paid,
    issue_and_accept_quotation,
    raise_and_approve_pricing_request,
    raise_pricing_request,
    run_factory_quote_and_costing,
    ticket_item,
    unique,
)

# ─────────────────────────────────────────────────────────────────────────────────────
# Slice S1 "engine collapse" (TicketController.java:136-145) retired the legacy ticket-native
# submit/pickup/propose-price/approve/reject/quotation-generate/close(single-step) routes for
# every NEW deal -- TicketService.submit() now always 409s from `draft`. The real path is the
# PricingRequest (PCR) chain: PricingRequest -> FactoryQuote -> PricingCosting -> PricingDecision
# -> CustomerQuotation -> OrderConfirmationService bridge -> deposit/fulfilment -> three-party
# close. Every TKT-* case below is rewritten to drive that real chain via the real HTTP API
# (helpers.py), preserving each case's original INTENT (a stage transition / role boundary /
# document produced) rather than just chasing a 2xx. Blueprint: backend/src/test/java/th/co/glr/
# hr/pricingchain/PricingChainEndToEndIntegrationTest.java + db/migration-uat/
# V910__uat_golden_pcr_deal.sql. TKT-05/TKT-08 (editItems/comment) are untouched -- those routes
# were never retired.
# ─────────────────────────────────────────────────────────────────────────────────────


@pytest.mark.uat("TKT-01", title="Sales raises and submits a pricing request", priority="P0")
def test_tkt01_create_submit(sales, import_):
    ticket = create_ticket(sales, unique("TKT01"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, _unit = raise_pricing_request(sales, import_, tid, marker=unique("TKT01"))
    detail = sales.get(f"/api/pricing-requests/{pr_id}").json()["pricingRequest"]
    assert detail["summary"]["status"] == "SUBMITTED", detail
    assert any(e["eventKind"] == "PRICING_REQUEST_CREATED" for e in detail["events"]), detail["events"]


@pytest.mark.uat("TKT-02", title="Import picks up, gets a factory quote, and submits costing", priority="P0")
def test_tkt02_pickup_propose_price(sales, import_):
    ticket = create_ticket(sales, unique("TKT02"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit = raise_pricing_request(sales, import_, tid, marker=unique("TKT02"))
    costing = run_factory_quote_and_costing(import_, pr_id, pr_item_id, requested_unit=unit)
    assert costing["status"] == "SUBMITTED", costing
    detail = import_.get(f"/api/pricing-requests/{pr_id}").json()["pricingRequest"]
    assert detail["summary"]["status"] == "READY_FOR_CEO_REVIEW", detail


@pytest.mark.uat("TKT-03", title="CEO approves the pricing decision", priority="P0")
def test_tkt03_approve_price(sales, import_, ceo):
    ticket = create_ticket(sales, unique("TKT03"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, marker=unique("TKT03"))
    assert decision["status"] == "APPROVED", decision
    assert all(item["approvedSellingPricePerRequestedUnit"] is not None for item in decision["items"]), decision["items"]


@pytest.mark.uat("TKT-04", title="CEO returns costing for revision (closest equivalent to reject)", priority="P1")
def test_tkt04_reject_price(sales, import_, ceo):
    ticket = create_ticket(sales, unique("TKT04"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit = raise_pricing_request(sales, import_, tid, marker=unique("TKT04"))
    run_factory_quote_and_costing(import_, pr_id, pr_item_id, requested_unit=unit)
    r = ceo.post(f"/api/pricing-requests/{pr_id}/pricing-decisions", json={
        "defaultMarginPct": "0.20", "currency": "THB", "ceoNote": "UAT review",
        "clientRequestId": str(uuid.uuid4()),
    })
    assert_status(r, 200)
    decision_id = r.json()["decision"]["id"]
    r = ceo.post(f"/api/pricing-decisions/{decision_id}/return-to-import",
                 json={"returnReason": "UAT reject: ราคาต้นทุนคลาดเคลื่อน กรุณาคำนวณใหม่"})
    assert_status(r, 200)
    returned = r.json()["decision"]
    assert returned["status"] == "RETURNED", returned
    detail = ceo.get(f"/api/pricing-requests/{pr_id}").json()["pricingRequest"]
    assert detail["summary"]["status"] == "COSTING_REVISION_REQUIRED", detail


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


@pytest.mark.uat("TKT-06", title="Issue customer quotation with running number and downloadable file", priority="P0")
def test_tkt06_issue_quotation_file(sales, import_, ceo):
    ticket = create_ticket(sales, unique("TKT06"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, marker=unique("TKT06"))
    r = sales.post(f"/api/pricing-requests/{pr_id}/quotations", json={
        "paymentTerms": "30 days", "leadTime": "45 days", "deliveryTerms": "รถขนส่ง",
        "validityDate": None, "customerNotes": "UAT", "clientRequestId": str(uuid.uuid4()),
    })
    assert_status(r, 201)
    quotation_id = r.json()["quotation"]["id"]
    r = sales.post(f"/api/customer-quotations/{quotation_id}/issue", json={"clientRequestId": str(uuid.uuid4())})
    assert_status(r, 200)
    quoted = r.json()["quotation"]
    assert quoted["docStatus"] == "ISSUED", quoted
    assert quoted["number"], quoted

    r = sales.get(f"/api/customer-quotations/{quotation_id}/file?format=pdf")
    assert_status(r, 200)
    assert r.headers["Content-Type"].startswith("application/pdf"), r.headers
    assert r.content.startswith(b"%PDF"), r.content[:20]


@pytest.mark.uat("TKT-06", title="PDF visual render inspection", priority="P0")
def test_tkt06_pdf_visual_manual():
    pytest.skip(reason="manual/UI")


@pytest.mark.uat("TKT-07", title="Customer-change revision retains the original issued quotation", priority="P0")
def test_tkt07_revision_event(sales, import_, ceo):
    ticket = create_ticket(sales, unique("TKT07"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, marker=unique("TKT07"))
    accepted = issue_and_accept_quotation(sales, pr_id)
    original_quotation_id = accepted["id"]
    assert accepted["docStatus"] == "ACCEPTED", accepted

    # createCustomerChangeRevision is reachable from any non-DRAFT/CANCELLED/SUPERSEDED status,
    # including the terminal QUOTATION_ACCEPTED reached above -- the modern equivalent of "the
    # customer wants to change the order after quoting": a NEW pricing-request chain, the OLD one
    # (and its issued/accepted quotation) untouched.
    r = sales.post(f"/api/pricing-requests/{pr_id}/customer-change-revision", json={
        "revisionReason": "UAT: ลูกค้าขอเปลี่ยนจำนวนสินค้า",
        "clientRequestId": str(uuid.uuid4()),
        "recipientType": "OWNER",
        "recipientContactId": None,
        "recipientLabel": "UAT Owner Contact",
        "requiredDate": None,
        "customerTargetPrice": None,
        "targetCurrency": "THB",
        "note": "UAT revision",
        "items": [{
            "sourceTicketItemId": None,
            "productId": None,
            "variantId": None,
            "brand": "UAT Brand",
            "model": f"UAT-REV-{unique('x')}",
            "productDescription": "UAT revised line item",
            "color": "Ivory",
            "texture": "Matt",
            "size": "60x60",
            "factory": "SCG Ceramics",
            "requestedQty": "15",
            "requestedQtySqm": "15",
            "requestedUnit": "piece",
            "requestedUnitBasis": "PER_PIECE",
            "quantityType": "CONFIRMED",
            "targetDeliveryDate": None,
            "deliveryLocation": None,
            "specialRequirement": None,
        }],
    })
    assert_status(r, 201)
    revision = r.json()["pricingRequest"]
    assert revision["summary"]["parentPricingRequestId"] == pr_id, revision["summary"]
    assert revision["summary"]["revisionNo"] == 2, revision["summary"]

    # The ORIGINAL quotation document is untouched by the revision -- still ACCEPTED, still
    # fetchable, same number.
    original_still_there = sales.get(f"/api/customer-quotations/{original_quotation_id}")
    assert_status(original_still_there, 200)
    still_accepted = original_still_there.json()["quotation"]
    assert still_accepted["docStatus"] == "ACCEPTED", still_accepted
    assert still_accepted["number"] == accepted["number"], still_accepted


@pytest.mark.uat("TKT-08", title="Comment recorded as event", priority="P1")
def test_tkt08_comment_event(sales):
    ticket = create_ticket(sales, unique("TKT08"))
    tid = ticket["summary"]["id"]
    r = sales.post(f"/api/tickets/{tid}/comments", json={"message": "UAT comment"})
    assert_status(r, 200)
    commented = r.json()["ticket"]
    assert any(e["kind"] == "COMMENTED" and e["message"] == "UAT comment" for e in commented["events"])


@pytest.mark.uat("TKT-09", title="Three-party close: account confirms, CEO verifies a CLOSED_PAID deal", priority="P0")
def test_tkt09_close_ticket(sales, import_, ceo, account):
    closed = drive_to_closed_paid(sales, import_, ceo, account, marker=unique("TKT09"))
    assert closed["summary"]["status"] == "closed", closed
    assert any(e["kind"] == "CLOSED" for e in closed["events"]), closed["events"]


# ── Dual-track post-quotation flow (payment_status / fulfillment_status), reached via the
# OrderConfirmationService bridge instead of the retired ticket-native /quotation route ──────────


@pytest.mark.uat("TKT-10", title="confirm-order bridge advances a deal to quotation_issued + CUSTOMER_CONFIRMED", priority="P0")
def test_tkt10_confirm_customer(sales, import_, ceo):
    ticket = create_ticket(sales, unique("TKT10"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, marker=unique("TKT10"))
    issue_and_accept_quotation(sales, pr_id)

    r = sales.post(f"/api/pricing-requests/{pr_id}/confirm-order", json={"clientRequestId": str(uuid.uuid4())})
    assert_status(r, 200)
    result = r.json()["result"]
    confirmed = result["ticket"]
    assert confirmed["summary"]["paymentStatus"] == "CUSTOMER_CONFIRMED", confirmed["summary"]
    assert confirmed["summary"]["status"] == "quotation_issued", confirmed["summary"]
    assert any(e["kind"] == "CUSTOMER_CONFIRMED" for e in confirmed["events"]), confirmed["events"]


@pytest.mark.uat("TKT-11", title="Sales issues a deposit notice (payment track)", priority="P0")
def test_tkt11_issue_deposit_notice(sales, import_, ceo):
    ticket = create_ticket(sales, unique("TKT11"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, marker=unique("TKT11"))
    issue_and_accept_quotation(sales, pr_id)
    ticket_id, deposit_notice = confirm_order_and_issue_deposit_notice(sales, pr_id)

    r = sales.get(f"/api/tickets/{ticket_id}")
    assert_status(r, 200)
    notice_issued = r.json()["ticket"]
    assert notice_issued["summary"]["paymentStatus"] == "DEPOSIT_NOTICE_ISSUED", notice_issued["summary"]
    assert any(e["kind"] == "DEPOSIT_NOTICE_ISSUED" for e in notice_issued["events"]), notice_issued["events"]
    assert deposit_notice["docNumber"], deposit_notice


@pytest.mark.uat("TKT-12", title="Sales confirms deposit paid", priority="P0")
def test_tkt12_confirm_deposit_paid(sales, import_, ceo, account):
    ticket = create_ticket(sales, unique("TKT12"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, marker=unique("TKT12"))
    issue_and_accept_quotation(sales, pr_id)
    ticket_id, _notice = confirm_order_and_issue_deposit_notice(sales, pr_id)

    r = account.post(f"/api/tickets/{ticket_id}/deposit-paid")
    assert_status(r, 200)
    paid = r.json()["ticket"]
    assert paid["summary"]["paymentStatus"] == "DEPOSIT_PAID", paid["summary"]
    assert any(e["kind"] == "DEPOSIT_PAID" for e in paid["events"]), paid["events"]


@pytest.mark.uat("TKT-13", title="Import issues an import request (fulfillment track)", priority="P0")
def test_tkt13_issue_import_request(sales, import_, ceo, account):
    ticket = create_ticket(sales, unique("TKT13"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, marker=unique("TKT13"))
    issue_and_accept_quotation(sales, pr_id)
    ticket_id, _notice = confirm_order_and_issue_deposit_notice(sales, pr_id)

    r = import_.post(f"/api/tickets/{ticket_id}/import-request")
    assert_status(r, 200)
    ir = r.json()["ticket"]
    assert ir["summary"]["fulfillmentStatus"] == "IR_ISSUED", ir["summary"]
    assert any(e["kind"] == "IR_ISSUED" for e in ir["events"]), ir["events"]


@pytest.mark.uat(
    "TKT-14",
    title="Fulfillment track walk: IR sent -> shipping -> goods received, auto AWAITING_FINAL_PAYMENT flip",
    priority="P0",
)
def test_tkt14_fulfillment_walk_and_auto_flip(sales, import_, ceo, account):
    ticket = create_ticket(sales, unique("TKT14"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, marker=unique("TKT14"))
    issue_and_accept_quotation(sales, pr_id)
    ticket_id, _notice = confirm_order_and_issue_deposit_notice(sales, pr_id)

    # Fulfillment track requires paymentStatus=DEPOSIT_NOTICE_ISSUED exactly (not DEPOSIT_PAID),
    # so import-request must fire before deposit-paid -- same ordering the original test proved.
    assert_status(import_.post(f"/api/tickets/{ticket_id}/import-request"), 200)
    assert_status(account.post(f"/api/tickets/{ticket_id}/deposit-paid"), 200)

    r = import_.post(f"/api/tickets/{ticket_id}/ir-sent")
    assert_status(r, 200)
    assert r.json()["ticket"]["summary"]["fulfillmentStatus"] == "IR_SENT"

    r = import_.post(f"/api/tickets/{ticket_id}/shipping")
    assert_status(r, 200)
    assert r.json()["ticket"]["summary"]["fulfillmentStatus"] == "SHIPPING"

    r = import_.post(f"/api/tickets/{ticket_id}/goods-received")
    assert_status(r, 200)
    received = r.json()["ticket"]
    assert received["summary"]["fulfillmentStatus"] == "GOODS_RECEIVED", received["summary"]
    # deposit was paid before goods-received -> payment track auto-flips
    assert received["summary"]["paymentStatus"] == "AWAITING_FINAL_PAYMENT", received["summary"]
    assert any(e["kind"] == "GOODS_RECEIVED" for e in received["events"]), received["events"]
    assert any(e["kind"] == "AWAITING_FINAL_PAYMENT" for e in received["events"]), received["events"]


@pytest.mark.uat("TKT-15", title="Sales confirms final payment -> FULLY_PAID", priority="P0")
def test_tkt15_confirm_final_payment(sales, import_, ceo, account):
    ticket = create_ticket(sales, unique("TKT15"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, marker=unique("TKT15"))
    issue_and_accept_quotation(sales, pr_id)
    ticket_id, _notice = confirm_order_and_issue_deposit_notice(sales, pr_id)
    assert_status(import_.post(f"/api/tickets/{ticket_id}/import-request"), 200)
    assert_status(account.post(f"/api/tickets/{ticket_id}/deposit-paid"), 200)
    assert_status(import_.post(f"/api/tickets/{ticket_id}/ir-sent"), 200)
    assert_status(import_.post(f"/api/tickets/{ticket_id}/shipping"), 200)
    assert_status(import_.post(f"/api/tickets/{ticket_id}/goods-received"), 200)

    r = account.post(f"/api/tickets/{ticket_id}/final-payment")
    assert_status(r, 200)
    paid = r.json()["ticket"]
    assert paid["summary"]["paymentStatus"] == "FULLY_PAID", paid["summary"]
    assert any(e["kind"] == "FULLY_PAID" for e in paid["events"]), paid["events"]
    # The original case also closed the ticket here via the now-retired single-step POST
    # /{id}/close (V56 replaced it with the three-party close/confirm + close/verify flow, which
    # additionally requires FULLY_DELIVERED, not just GOODS_RECEIVED) -- that assertion now lives
    # in TKT-09 (drive_to_closed_paid), which drives a deal all the way through a real delivery
    # and the real three-party close.


@pytest.mark.uat("TKT-16", title="Download the remaining invoice for a quotation_issued ticket", priority="P0")
def test_tkt16_remaining_invoice_download(sales, import_, ceo):
    ticket = create_ticket(sales, unique("TKT16"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, marker=unique("TKT16"))
    issue_and_accept_quotation(sales, pr_id)
    ticket_id, _notice = confirm_order_and_issue_deposit_notice(sales, pr_id)

    r = sales.get(f"/api/tickets/{ticket_id}/remaining-invoice/file")
    assert_status(r, 200)
    assert r.headers["Content-Type"].startswith(
        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
    ), r.headers
    assert len(r.content) > 0, "remaining invoice file was empty"


@pytest.mark.uat(
    "TKT-17",
    title="CEO manual per-item price override on a price_proposed ticket item",
    priority="P0",
)
def test_tkt17_manual_price_override():
    # Genuinely removed capability, no equivalent: TicketService.overrideItemPrice /
    # calculatePrices (the legacy price_proposed-only CEO tooling this case exercised) are
    # @Deprecated with NO controller route exposing them anymore (TicketController.java:338-340,
    # "calculate-prices and items/{itemId}/price-override are retired too -- same Slice S1
    # rationale"). The modern equivalent -- CEO adjusting margin/minimumSellingPrice per line via
    # PUT /api/pricing-decisions/{id} -- only exists BEFORE approval, on a PricingDecisionItem, not
    # a ticket_item, and has no "outside the window -> 409" analog matching this case's specific
    # second assertion (override rejected outside price_proposed). Forcing a rewrite here would
    # assert a materially different feature, not this case's actual intent. See PLAN.md / handoff
    # for CEO/reviewer sign-off before permanently retiring this UAT-ID.
    pytest.xfail(reason=(
        "removed capability, no replacement: ticket-native price-override "
        "(items/{itemId}/price-override) has no controller route since Slice S1; the PCR "
        "equivalent (PricingDecisionItem margin edit, pre-approval) is a different feature shape"
    ))


@pytest.mark.uat("TKT-18", title="Unit-basis SQM item round-trips through the PCR chain", priority="P1")
def test_tkt18_unit_basis_sqm_roundtrip(sales, import_, ceo):
    ticket = create_ticket(sales, unique("TKT18"), include_item=False)
    tid = ticket["summary"]["id"]
    pr_id, pr_item_id, unit, costing, decision = raise_and_approve_pricing_request(
        sales, import_, ceo, tid, unit_basis="PER_SQM", requested_qty="42.75", marker=unique("TKT18"))
    assert unit == "sqm", unit

    pr_detail = ceo.get(f"/api/pricing-requests/{pr_id}").json()["pricingRequest"]
    item = next(i for i in pr_detail["items"] if i["id"] == pr_item_id)
    assert item["requestedUnitBasis"] == "PER_SQM", item
    assert float(item["requestedQty"]) == 42.75, item

    decision_item = next(i for i in decision["items"] if i["pricingRequestItemId"] == pr_item_id)
    assert decision_item["requestedUnitBasis"] == "PER_SQM", decision_item
    assert float(decision_item["requestedQuantity"]) == 42.75, decision_item
