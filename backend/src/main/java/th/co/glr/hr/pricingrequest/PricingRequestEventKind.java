package th.co.glr.hr.pricingrequest;

import java.util.Set;

public final class PricingRequestEventKind {
    public static final String PRICING_REQUEST_CREATED   = "PRICING_REQUEST_CREATED";
    public static final String PRICING_REQUEST_UPDATED    = "PRICING_REQUEST_UPDATED";
    public static final String PRICING_REQUEST_SUBMITTED  = "PRICING_REQUEST_SUBMITTED";
    public static final String PRICING_REQUEST_PICKED_UP  = "PRICING_REQUEST_PICKED_UP";
    // Import named the factory on a line Sales left blank. Only ever raised for a line that had
    // NO factory at all (neither the catalog snapshot nor free text) — see
    // PricingRequestService#setItemFactory, which refuses to re-route a line that already has
    // one. It is a routing decision, not metadata: it decides which factory gets asked for a
    // price, so it belongs in the audit trail beside the pickup and the factory-email events.
    public static final String PRICING_REQUEST_ITEM_FACTORY_SET = "PRICING_REQUEST_ITEM_FACTORY_SET";
    public static final String MORE_INFO_REQUESTED        = "MORE_INFO_REQUESTED";
    public static final String MORE_INFO_RESPONDED        = "MORE_INFO_RESPONDED";
    public static final String PRICING_REQUEST_CANCELLED  = "PRICING_REQUEST_CANCELLED";
    public static final String PRICING_REQUEST_REVISED    = "PRICING_REQUEST_REVISED";
    public static final String FACTORY_EMAIL_READY        = "FACTORY_EMAIL_READY";
    public static final String FACTORY_EMAIL_SENT         = "FACTORY_EMAIL_SENT";
    public static final String FACTORY_RESPONSE_RECEIVED  = "FACTORY_RESPONSE_RECEIVED";
    public static final String FACTORY_NEGOTIATION_STARTED = "FACTORY_NEGOTIATION_STARTED";
    public static final String FACTORY_RESPONSE_READY_FOR_COSTING = "FACTORY_RESPONSE_READY_FOR_COSTING";
    public static final String FACTORY_RESPONSE_REVISED   = "FACTORY_RESPONSE_REVISED";
    public static final String FACTORY_NOT_AVAILABLE      = "FACTORY_NOT_AVAILABLE";
    public static final String PRICING_COSTING_STARTED    = "PRICING_COSTING_STARTED";
    public static final String PRICING_COSTING_CALCULATED = "PRICING_COSTING_CALCULATED";
    public static final String PRICING_COSTING_SUBMITTED  = "PRICING_COSTING_SUBMITTED";
    // Step 3: CEO Selling Price Decision.
    public static final String PRICING_DECISION_STARTED   = "PRICING_DECISION_STARTED";
    public static final String PRICING_DECISION_UPDATED   = "PRICING_DECISION_UPDATED";
    public static final String PRICING_DECISION_APPROVED  = "PRICING_DECISION_APPROVED";
    public static final String PRICING_DECISION_RETURNED  = "PRICING_DECISION_RETURNED";
    // V141 ("CEO owns costing"): the CEO overrode (or cleared an override of) one line's landed
    // cost on the costing bound to their in-review decision. The event trail carries the reason
    // (mandatory in both directions — see PricingDecisionRequests.CostOverrideRequest) since an
    // override is an input to price = cost x margin, not mere metadata.
    public static final String PRICING_COSTING_ITEM_COST_OVERRIDDEN = "PRICING_COSTING_ITEM_COST_OVERRIDDEN";
    // Step 4: Customer Quotation Generation and Issuance.
    public static final String CUSTOMER_QUOTATION_CREATED  = "CUSTOMER_QUOTATION_CREATED";
    public static final String CUSTOMER_QUOTATION_UPDATED  = "CUSTOMER_QUOTATION_UPDATED";
    public static final String CUSTOMER_QUOTATION_ISSUED   = "CUSTOMER_QUOTATION_ISSUED";
    public static final String CUSTOMER_QUOTATION_CANCELLED = "CUSTOMER_QUOTATION_CANCELLED";
    public static final String CUSTOMER_QUOTATION_REVISED  = "CUSTOMER_QUOTATION_REVISED";
    // Step 5: Customer Decision and Commercial Revisions.
    public static final String CUSTOMER_QUOTATION_ACCEPTED  = "CUSTOMER_QUOTATION_ACCEPTED";
    public static final String CUSTOMER_QUOTATION_REJECTED  = "CUSTOMER_QUOTATION_REJECTED";
    public static final String CUSTOMER_QUOTATION_REVISION_REQUESTED = "CUSTOMER_QUOTATION_REVISION_REQUESTED";
    // Sweep-only (QuotationExpiryWorker) — never emitted by a client-driven recordOutcome call.
    public static final String CUSTOMER_QUOTATION_EXPIRED   = "CUSTOMER_QUOTATION_EXPIRED";
    // CEO discount-approval workflow, Phase 2 (owner ruling 2026-08-16, V155). REQUESTED is
    // raised by CustomerQuotationService the first time a below-CEO-minimum line is saved at a
    // price nobody has asked for (or been granted) before; APPROVED/REJECTED are raised by
    // DiscountApprovalService. See sales.quotation_item_discount_approval's own comment for the
    // full per-line, price-bound state machine.
    public static final String DISCOUNT_APPROVAL_REQUESTED  = "DISCOUNT_APPROVAL_REQUESTED";
    public static final String DISCOUNT_APPROVED             = "DISCOUNT_APPROVED";
    public static final String DISCOUNT_REJECTED              = "DISCOUNT_REJECTED";
    // Step 6: Deposit, Payment, and Order Confirmation.
    public static final String ORDER_CONFIRMED               = "ORDER_CONFIRMED";
    public static final String DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION = "DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION";
    // Step 7: Factory Purchase Order and Import Execution.
    public static final String FACTORY_PO_CREATED             = "FACTORY_PO_CREATED";
    public static final String FACTORY_PO_PROFORMA_RECORDED   = "FACTORY_PO_PROFORMA_RECORDED";
    public static final String FACTORY_PO_SHIPPING_RECORDED   = "FACTORY_PO_SHIPPING_RECORDED";
    public static final String FACTORY_PO_GOODS_RECEIVED      = "FACTORY_PO_GOODS_RECEIVED";
    public static final String FACTORY_PO_CANCELLED           = "FACTORY_PO_CANCELLED";
    // Step 8: Receiving, Inventory Allocation, and Delivery. Logged by
    // OrderConfirmationService#reconcileTicketItems the first time confirmOrder actually has to
    // change/add a sales.ticket_item row to match this pricing request's own settled quantities.
    public static final String TICKET_ITEMS_RECONCILED        = "TICKET_ITEMS_RECONCILED";

    public static final Set<String> VALUES = Set.of(
        PRICING_REQUEST_CREATED, PRICING_REQUEST_UPDATED, PRICING_REQUEST_SUBMITTED,
        PRICING_REQUEST_PICKED_UP, PRICING_REQUEST_ITEM_FACTORY_SET,
        MORE_INFO_REQUESTED, MORE_INFO_RESPONDED,
        PRICING_REQUEST_CANCELLED, PRICING_REQUEST_REVISED, FACTORY_EMAIL_READY, FACTORY_EMAIL_SENT,
        FACTORY_RESPONSE_RECEIVED, FACTORY_NEGOTIATION_STARTED,
        FACTORY_RESPONSE_READY_FOR_COSTING, FACTORY_RESPONSE_REVISED,
        FACTORY_NOT_AVAILABLE, PRICING_COSTING_STARTED, PRICING_COSTING_CALCULATED,
        PRICING_COSTING_SUBMITTED, PRICING_DECISION_STARTED, PRICING_DECISION_UPDATED,
        PRICING_DECISION_APPROVED, PRICING_DECISION_RETURNED, PRICING_COSTING_ITEM_COST_OVERRIDDEN,
        CUSTOMER_QUOTATION_CREATED, CUSTOMER_QUOTATION_UPDATED, CUSTOMER_QUOTATION_ISSUED,
        CUSTOMER_QUOTATION_CANCELLED, CUSTOMER_QUOTATION_REVISED, CUSTOMER_QUOTATION_ACCEPTED,
        CUSTOMER_QUOTATION_REJECTED, CUSTOMER_QUOTATION_REVISION_REQUESTED, CUSTOMER_QUOTATION_EXPIRED,
        DISCOUNT_APPROVAL_REQUESTED, DISCOUNT_APPROVED, DISCOUNT_REJECTED,
        ORDER_CONFIRMED, DEPOSIT_NOTICE_DRAFTED_FROM_QUOTATION,
        FACTORY_PO_CREATED, FACTORY_PO_PROFORMA_RECORDED, FACTORY_PO_SHIPPING_RECORDED,
        FACTORY_PO_GOODS_RECEIVED, FACTORY_PO_CANCELLED, TICKET_ITEMS_RECONCILED);

    public static boolean isValid(String value) {
        return value != null && VALUES.contains(value);
    }

    private PricingRequestEventKind() {}
}
