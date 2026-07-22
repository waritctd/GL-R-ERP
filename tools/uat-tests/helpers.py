import itertools
import os
import time
import uuid
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


def create_customer_and_project(sales, marker=None):
    """Create a throwaway customer + project via the API, the way the real app does it (see
    TicketCreateModal.jsx: customer -> project -> ticket). V50 made `projectId` @NotNull on
    CreateTicketRequest ("1 deal = 1 ticket under a project"), so every ticket needs one.
    Both endpoints are `sales`-role-gated."""
    marker = marker or unique("UAT")
    r = sales.post("/api/customers", json={"name": f"UAT Harness Customer {marker}"})
    assert_status(r, 200)
    customer_id = r.json()["customer"]["id"]
    r = sales.post(f"/api/customers/{customer_id}/projects", json={"name": f"UAT Harness Project {marker}"})
    assert_status(r, 200)
    project_id = r.json()["project"]["id"]
    return customer_id, project_id


def create_ticket(sales, title=None, project_id=None, include_item=True):
    """Create a deal ticket. `include_item=False` leaves it a lightweight, item-less DRAFT (V50:
    "a deal may start at the lead stage with no product items yet") -- used by the pricing-request
    (PCR) chain helpers below, whose own OrderConfirmationService.reconcileTicketItems is what
    creates the ticket's real `sales.ticket_item` row (see raise_pricing_request's docstring for
    why: a pre-existing item here would leave a second, never-reconciled row that blocks
    completeDelivery's "every item fully delivered" gate at the end of the chain)."""
    if project_id is None:
        _customer_id, project_id = create_customer_and_project(sales)
    r = sales.post(
        "/api/tickets",
        json={
            "title": title or unique("UAT ticket"),
            "priority": "NORMAL",
            "customerName": "UAT Harness Customer",
            "projectId": project_id,
            "note": "UAT automated harness",
            "items": [ticket_item(proposedPrice=None)] if include_item else [],
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


# ─────────────────────────────────────────────────────────────────────────────────────
# PricingRequest (PCR) chain -- Slice S1 "engine collapse" retired the legacy ticket-native
# submit/pickup/propose-price/approve/reject/quotation/close(single-step) routes; the real path
# to price+quote+deliver+close a deal is now: PricingRequest -> FactoryQuote -> PricingCosting ->
# PricingDecision -> CustomerQuotation -> OrderConfirmationService bridge -> deposit/fulfilment ->
# three-party close. Blueprint: backend/src/test/java/th/co/glr/hr/pricingchain/
# PricingChainEndToEndIntegrationTest.java (service layer) + db/migration-uat/
# V910__uat_golden_pcr_deal.sql (the "golden" journey to CLOSED_PAID + which persona acts where).
# Roles: sales owns create/submit/quotation/confirm-order (ticket+PCR owner-scoped); import owns
# pickup/factory-quote/costing/fulfilment; ceo owns the pricing decision + verifies close; account
# confirms payments + the three-party close + the post-close commission invoice.
# ─────────────────────────────────────────────────────────────────────────────────────

SEEDED_FACTORY_NAME = "SCG Ceramics"  # sales.factory_config seed (V25), country=Thailand/THB --
# real, non-all-zero price_calc_config row (V26), so PricingCostingService resolves a real country
# instead of 404ing on "no price config for country: null".


def _find_or_create_catalog_factory(actor, name=SEEDED_FACTORY_NAME, country="TH", currency="THB"):
    """`price_catalog.factories` is its OWN id-space, entirely separate from `sales.factory_config`
    (the seed used for factory-quote email dispatch) -- but FactoryQuoteService.groupByFactory
    resolves a pricing-request item's factory as `firstText(item.resolvedFactoryName(),
    item.factory())`, i.e. the CATALOG product's own snapshotted factory name wins over the item's
    free-text `factory` field. So the catalog factory must be named EXACTLY `SEEDED_FACTORY_NAME`
    too, or generateDrafts groups items under a factory name `sales.factory_config` has never heard
    of and PricingCostingService 422s with "ไม่พบ factory config สำหรับโรงงาน". `POST
    /api/price-import/factories` has no ON CONFLICT (unlike CatalogController.addProduct's
    manual-version path), so look it up first and only create once."""
    r = actor.get("/api/price-import/factories")
    assert_status(r, 200)
    existing = next((f for f in r.json() if f.get("name") == name), None)
    if existing is not None:
        return existing["factoryId"]
    r = actor.post("/api/price-import/factories",
                    json={"name": name, "country": country, "defaultCurrency": currency})
    assert_status(r, 200)
    return r.json()["factoryId"]


def create_catalog_product(actor, marker=None):
    """A pricing-request item's `productId` must resolve to an ACTIVE price_catalog.product_prices
    row (financial-integrity 'catalog is mandatory' gate on PricingRequestService.submit()).
    `actor` must be `import` or `ceo` (CatalogController.requireCatalogEditor). Returns the created
    product's price_id, exactly what PricingRequestItemRequest.productId expects."""
    marker = marker or unique("CAT")
    factory_id = _find_or_create_catalog_factory(actor)
    r = actor.post(
        "/api/catalog/prices",
        json={
            "factoryId": factory_id,
            "productCode": f"UAT-{marker}",
            "productName": f"UAT Catalog Product {marker}",
            "price": "100.00",
            "currency": "THB",
            "priceUnit": "per_piece",
        },
    )
    assert_status(r, 200)
    return r.json()["priceId"]


def raise_pricing_request(sales, import_, ticket_id, unit_basis="PER_PIECE", requested_qty="10", marker=None):
    """Step 1: sales drafts + submits a PricingRequest on `ticket_id` (must be the SAME `sales`
    client that created the ticket -- createDraft/submit both check ticket.createdById==actor.id).
    Returns (pricing_request_id, pricing_request_item_id, requested_unit).
    """
    marker = marker or unique("PCR")
    product_id = create_catalog_product(import_, marker)
    requested_unit = "sqm" if unit_basis == "PER_SQM" else "piece"
    item = {
        "sourceTicketItemId": None,
        "productId": product_id,
        "variantId": None,
        "brand": "UAT Brand",
        "model": f"UAT-{marker}",
        "productDescription": None,
        "color": "Ivory",
        "texture": "Matt",
        "size": "60x60",
        "factory": SEEDED_FACTORY_NAME,
        "requestedQty": requested_qty,
        "requestedQtySqm": requested_qty,
        "requestedUnit": requested_unit,
        "requestedUnitBasis": unit_basis,
        "quantityType": "CONFIRMED",
        "targetDeliveryDate": None,
        "deliveryLocation": None,
        "specialRequirement": None,
    }
    r = sales.post(
        f"/api/tickets/{ticket_id}/pricing-requests",
        json={
            "recipientType": "OWNER",
            "recipientContactId": None,
            "recipientLabel": "UAT Owner Contact",
            "requiredDate": None,
            "customerTargetPrice": None,
            "targetCurrency": "THB",
            "note": "UAT harness pricing request",
            "clientRequestId": str(uuid.uuid4()),
            "items": [item],
        },
    )
    assert_status(r, 201)
    detail = r.json()["pricingRequest"]
    pr_id = detail["summary"]["id"]
    pr_item_id = detail["items"][0]["id"]

    r = sales.post(f"/api/pricing-requests/{pr_id}/submit")
    assert_status(r, 200)
    submitted = r.json()["pricingRequest"]
    assert submitted["summary"]["status"] == "SUBMITTED", submitted
    return pr_id, pr_item_id, requested_unit


def run_factory_quote_and_costing(import_, pricing_request_id, pricing_request_item_id,
                                   unit_basis="PER_PIECE", requested_unit="piece", requested_qty="10"):
    """Step 1->2 handoff + Step 2: import picks up, drafts+sends the factory email, records the
    factory's response, marks it ready, then drafts/recalculates/submits costing. Returns the
    submitted PricingCostingDto."""
    r = import_.post(f"/api/pricing-requests/{pricing_request_id}/pickup")
    assert_status(r, 200)
    assert r.json()["pricingRequest"]["summary"]["status"] == "IMPORT_REVIEWING", r.json()

    r = import_.post(f"/api/pricing-requests/{pricing_request_id}/factory-email-drafts")
    assert_status(r, 200)
    drafts = r.json()["items"]
    assert len(drafts) == 1, drafts  # exactly one factory (SEEDED_FACTORY_NAME) used above
    quote_id = drafts[0]["id"]

    r = import_.post(
        f"/api/factory-quotes/{quote_id}/send",
        json={
            "emailTo": "sales@scg.co.th",
            "emailSubject": "UAT RFQ",
            "emailBody": "UAT harness factory RFQ",
            "clientRequestId": str(uuid.uuid4()),
        },
    )
    assert_status(r, 200)

    sqm_per_unit = "1.00" if unit_basis == "PER_PIECE" else "0.36"  # 60x60cm piece ~= 0.36 sqm
    r = import_.post(
        f"/api/factory-quotes/{quote_id}/receive",
        json={
            "supplierQuoteRef": "UAT-REF-01",
            "defaultCurrency": "THB",
            "paymentTerms": "30 days",
            "leadTimeText": "45 days",
            "revisionReason": None,
            "negotiationNote": None,
            "items": [{
                "pricingRequestItemId": pricing_request_item_id,
                "supplierProductCode": None,
                "supplierProductDescription": None,
                "quotedQuantity": requested_qty,
                "quotedUnit": requested_unit,
                "unitBasis": unit_basis,
                "rawUnitPrice": "10.00",
                "currency": "THB",
                "minimumOrderQuantity": None,
                "sqmPerUnit": sqm_per_unit,
                "piecesPerBox": None,
                "linearMPerUnit": None,
                "leadTimeText": "45 days",
                "availabilityNote": None,
                "lineNote": None,
            }],
            "clientRequestId": str(uuid.uuid4()),
        },
    )
    assert_status(r, 200)

    r = import_.post(f"/api/factory-quotes/{quote_id}/mark-ready-for-costing")
    assert_status(r, 200)
    assert r.json()["factoryQuote"]["status"] == "READY_FOR_COSTING", r.json()

    r = import_.post(f"/api/pricing-requests/{pricing_request_id}/costings",
                      json={"note": "UAT costing draft", "clientRequestId": None})
    assert_status(r, 200)
    costing_id = r.json()["costing"]["id"]

    r = import_.post(f"/api/pricing-costings/{costing_id}/recalculate", json={"note": "pass 1"})
    assert_status(r, 200)
    r = import_.post(f"/api/pricing-costings/{costing_id}/recalculate", json={"note": "pass 2"})
    assert_status(r, 200)
    assert r.json()["costing"]["status"] == "CALCULATED", r.json()

    r = import_.post(f"/api/pricing-costings/{costing_id}/submit", json={"note": "submit to CEO"})
    assert_status(r, 200)
    submitted = r.json()["costing"]
    assert submitted["status"] == "SUBMITTED", submitted
    return submitted


def ceo_price_decision(ceo, pricing_request_id, margin="0.20"):
    """Step 3: CEO starts review, sets margin/minimum-selling-price on every item (minimum is
    required at approval), then approves. Returns the APPROVED PricingDecisionDto."""
    r = ceo.post(
        f"/api/pricing-requests/{pricing_request_id}/pricing-decisions",
        json={"defaultMarginPct": margin, "currency": "THB", "ceoNote": "UAT CEO review",
              "clientRequestId": str(uuid.uuid4())},
    )
    assert_status(r, 200)
    decision = r.json()["decision"]
    decision_id = decision["id"]

    item_updates = [
        {
            "pricingDecisionItemId": item["id"],
            "marginPct": margin,
            "discountCeilingPct": "0.05",
            "minimumSellingPrice": "1.00",
            "decisionNote": None,
        }
        for item in decision["items"]
    ]
    r = ceo.put(
        f"/api/pricing-decisions/{decision_id}",
        json={"ceoNote": "UAT set minimum selling price", "items": item_updates},
    )
    assert_status(r, 200)

    r = ceo.post(
        f"/api/pricing-decisions/{decision_id}/approve",
        json={"ceoNote": "UAT approve", "clientRequestId": str(uuid.uuid4())},
    )
    assert_status(r, 200)
    approved = r.json()["decision"]
    assert approved["status"] == "APPROVED", approved
    return approved


def issue_and_accept_quotation(sales, pricing_request_id):
    """Step 4 + 5: sales creates the customer-quotation draft, issues it, then records the
    customer's ACCEPTED outcome. Returns the ACCEPTED CustomerQuotationDto."""
    r = sales.post(f"/api/pricing-requests/{pricing_request_id}/quotations", json={
        "paymentTerms": "30 days", "leadTime": "45 days", "deliveryTerms": "รถขนส่ง",
        "validityDate": None, "customerNotes": "UAT customer quotation", "clientRequestId": str(uuid.uuid4()),
    })
    assert_status(r, 201)
    quotation_id = r.json()["quotation"]["id"]

    r = sales.post(f"/api/customer-quotations/{quotation_id}/issue",
                    json={"clientRequestId": str(uuid.uuid4())})
    assert_status(r, 200)
    issued = r.json()["quotation"]
    assert issued["docStatus"] == "ISSUED", issued

    r = sales.post(f"/api/customer-quotations/{quotation_id}/outcome", json={
        "outcome": "ACCEPTED", "customerNote": "UAT customer accepted", "clientRequestId": str(uuid.uuid4()),
    })
    assert_status(r, 200)
    accepted = r.json()["quotation"]
    return accepted


def confirm_order_and_issue_deposit_notice(sales, pricing_request_id, deposit_percent="0.50"):
    """Step 6 (OrderConfirmationService bridge): confirms the order (writes ticket.status
    draft->quotation_issued + paymentStatus=CUSTOMER_CONFIRMED -- the ONE bridge from the PCR chain
    back into the legacy dual-track ticket machinery), then drafts + issues a deposit notice from
    the accepted quotation. Returns (ticket_id, deposit_notice_dict)."""
    r = sales.post(f"/api/pricing-requests/{pricing_request_id}/confirm-order",
                    json={"clientRequestId": str(uuid.uuid4())})
    assert_status(r, 200)
    result = r.json()["result"]
    ticket_id = result["ticket"]["summary"]["id"]
    assert result["ticket"]["summary"]["status"] == "quotation_issued", result
    assert result["ticket"]["summary"]["paymentStatus"] == "CUSTOMER_CONFIRMED", result

    r = sales.post(f"/api/pricing-requests/{pricing_request_id}/deposit-notice",
                    json={"depositPercent": deposit_percent})
    assert_status(r, 200)
    draft = r.json()["depositNotice"]

    r = sales.post(f"/api/deposit-notices/{draft['id']}/issue")
    assert_status(r, 200)
    issued = r.json()["depositNotice"]
    assert issued["status"] == "ISSUED", issued
    return ticket_id, issued


def raise_and_approve_pricing_request(sales, import_, ceo, ticket_id, unit_basis="PER_PIECE",
                                       requested_qty="10", marker=None):
    """Composes Steps 1-3 (raise_pricing_request -> run_factory_quote_and_costing ->
    ceo_price_decision) -- the common prefix every PCR-chain test needs before it can branch into
    quotation/order/fulfilment assertions of its own. Returns (pricing_request_id,
    pricing_request_item_id, requested_unit, submitted_costing, approved_decision)."""
    pr_id, pr_item_id, requested_unit = raise_pricing_request(
        sales, import_, ticket_id, unit_basis=unit_basis, requested_qty=requested_qty, marker=marker)
    costing = run_factory_quote_and_costing(
        import_, pr_id, pr_item_id, unit_basis=unit_basis, requested_unit=requested_unit,
        requested_qty=requested_qty)
    decision = ceo_price_decision(ceo, pr_id)
    return pr_id, pr_item_id, requested_unit, costing, decision


def drive_to_closed_paid(sales, import_, ceo, account, marker=None):
    """The FULL chain (blueprint: V910__uat_golden_pcr_deal.sql's journey), end to end via the real
    HTTP API, for tests that need a genuinely CLOSED_PAID/CLOSED deal: create ticket -> raise +
    approve PCR -> issue+accept quotation -> confirm order + deposit notice -> deposit paid ->
    import request -> ir-sent -> shipping -> goods received -> complete delivery -> final payment
    (paymentStatus FULLY_PAID + fulfillmentStatus FULLY_DELIVERED auto-advances sales_stage to
    CLOSED_PAID) -> account records the post-close commission invoice (the ONLY thing that sets
    ticket.invoiceOnFile, required by the close gate) -> three-party close (account confirms, ceo
    verifies). Returns the final ticket dict (status=closed, lifecycle=COMPLETED)."""
    marker = marker or unique("CLOSE")
    ticket = create_ticket(sales, unique(f"PCR-{marker}"), include_item=False)
    ticket_id = ticket["summary"]["id"]

    pr_id, _pr_item_id, _unit, _costing, _decision = raise_and_approve_pricing_request(
        sales, import_, ceo, ticket_id, marker=marker)
    issue_and_accept_quotation(sales, pr_id)
    ticket_id, _deposit_notice = confirm_order_and_issue_deposit_notice(sales, pr_id)

    r = import_.post(f"/api/tickets/{ticket_id}/import-request")
    assert_status(r, 200)
    r = account.post(f"/api/tickets/{ticket_id}/deposit-paid")
    assert_status(r, 200)
    r = import_.post(f"/api/tickets/{ticket_id}/ir-sent")
    assert_status(r, 200)
    r = import_.post(f"/api/tickets/{ticket_id}/shipping")
    assert_status(r, 200)
    r = import_.post(f"/api/tickets/{ticket_id}/goods-received")
    assert_status(r, 200)
    r = import_.post(f"/api/tickets/{ticket_id}/deliveries/complete",
                      json={"note": "UAT full delivery", "recipientName": "UAT Owner"})
    assert_status(r, 200)
    delivered = r.json()["ticket"]
    assert delivered["summary"]["fulfillmentStatus"] == "FULLY_DELIVERED", delivered

    r = account.post(f"/api/tickets/{ticket_id}/final-payment")
    assert_status(r, 200)
    paid = r.json()["ticket"]
    assert paid["summary"]["paymentStatus"] == "FULLY_PAID", paid

    r = account.post(
        "/api/commissions/from-deal",
        data={"ticketId": ticket_id, "invoiceNumber": unique("UAT-CLOSE-INV"), "invoiceDate": "2026-08-05"},
        files={"invoiceAttachment": ("invoice.pdf", tiny_pdf_bytes(), "application/pdf")},
    )
    assert_status(r, 200)

    r = account.post(f"/api/tickets/{ticket_id}/close/confirm")
    assert_status(r, 200)
    r = ceo.post(f"/api/tickets/{ticket_id}/close/verify")
    assert_status(r, 200)
    closed = r.json()["ticket"]
    assert closed["summary"]["status"] == "closed", closed
    assert closed["summary"]["lifecycle"] == "COMPLETED", closed
    return closed


def tiny_pdf_bytes():
    return (
        b"%PDF-1.4\n1 0 obj<</Type/Catalog/Pages 2 0 R>>endobj\n"
        b"2 0 obj<</Type/Pages/Count 1/Kids[3 0 R]>>endobj\n"
        b"3 0 obj<</Type/Page/Parent 2 0 R/MediaBox[0 0 72 72]>>endobj\n"
        b"trailer<</Root 1 0 R>>\n%%EOF\n"
    )


def submit_commission(client, invoice_number=None, sales_rep_id=None, **overrides):
    """POST /api/commissions (multipart). Slice A2 (handoff 98) gated commission creation to
    hasAnyRole('ACCOUNT','SALES_MANAGER','CEO') -- sales was removed, so `client` must now be one
    of those personas (typically `account`). `sales_rep_id` attributes the commission to a specific
    rep: CommissionService.resolveSalesRep() defaults an unset salesRepId to the *caller's own*
    employeeId, which is wrong when an accountant is submitting on a rep's behalf -- callers that
    need the commission (and its notifications/emails) tied to a particular sales rep must pass
    that rep's employeeId explicitly (e.g. `sales_rep_id=sales.user["employeeId"]`)."""
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
    if sales_rep_id is not None:
        data["salesRepId"] = sales_rep_id
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
