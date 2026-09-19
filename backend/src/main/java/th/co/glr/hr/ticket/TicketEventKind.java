package th.co.glr.hr.ticket;

public final class TicketEventKind {
    public static final String CREATED          = "CREATED";
    public static final String SUBMITTED        = "SUBMITTED";
    public static final String PICKED_UP        = "PICKED_UP";
    public static final String PRICE_PROPOSED   = "PRICE_PROPOSED";
    public static final String APPROVED         = "APPROVED";
    public static final String REJECTED         = "REJECTED";
    public static final String QUOTATION_ISSUED = "QUOTATION_ISSUED";
    public static final String COMMENTED        = "COMMENTED";
    public static final String CLOSED           = "CLOSED";
    public static final String CANCELLED        = "CANCELLED";
    public static final String EDITED                = "EDITED";
    public static final String DOCUMENT_ISSUED      = "DOCUMENT_ISSUED";
    public static final String REVISION_REQUESTED   = "REVISION_REQUESTED";
    public static final String PRICE_REVISED         = "PRICE_REVISED";
    // Dual-track post-quotation events (Track P = payment, Track F = fulfillment)
    public static final String CUSTOMER_CONFIRMED    = "CUSTOMER_CONFIRMED";
    public static final String DEPOSIT_NOTICE_ISSUED = "DEPOSIT_NOTICE_ISSUED";
    public static final String DEPOSIT_PAID          = "DEPOSIT_PAID";
    public static final String IR_ISSUED             = "IR_ISSUED";
    public static final String IR_SENT               = "IR_SENT";
    public static final String SHIPPING              = "SHIPPING";
    public static final String GOODS_RECEIVED        = "GOODS_RECEIVED";
    public static final String AWAITING_FINAL_PAYMENT = "AWAITING_FINAL_PAYMENT";
    public static final String FULLY_PAID            = "FULLY_PAID";
    // CEO manual price override on a ticket item (2026-07-16 pricing-integrity audit) —
    // sales.ticket_event.chk_event_kind was extended for this in V48.
    public static final String PRICE_OVERRIDDEN      = "PRICE_OVERRIDDEN";
    // Deal pipeline events (V50) — from_status/to_status carry DealStage codes.
    // chk_event_kind was re-declared for these in V50.
    public static final String STAGE_CHANGED         = "STAGE_CHANGED";
    public static final String MARKED_LOST           = "MARKED_LOST";
    public static final String REOPENED              = "REOPENED";
    // Deal lifecycle + structured policy events (V51).
    public static final String ON_HOLD                = "ON_HOLD";
    public static final String DORMANT                = "DORMANT";
    public static final String RESUMED                = "RESUMED";
    public static final String POLICY_CHANGED         = "POLICY_CHANGED";
    // Quotation recipient-chain lifecycle events (V52).
    public static final String QUOTATION_SENT         = "QUOTATION_SENT";
    public static final String QUOTATION_ACCEPTED     = "QUOTATION_ACCEPTED";
    public static final String QUOTATION_REJECTED     = "QUOTATION_REJECTED";
    // Payment ledger + billing events (V53).
    public static final String PAYMENT_RECORDED       = "PAYMENT_RECORDED";
    public static final String BILLING_UPDATED        = "BILLING_UPDATED";
    // Per-line fulfilment + delivery events (V54).
    public static final String STOCK_RESERVED         = "STOCK_RESERVED";
    public static final String DELIVERY_RECORDED      = "DELIVERY_RECORDED";
    public static final String DELIVERY_COMPLETED     = "DELIVERY_COMPLETED";
    // Three-party close (V56). CLOSED is now written only by the CEO's verification.
    public static final String CLOSE_CONFIRMED        = "CLOSE_CONFIRMED";
    public static final String CLOSE_CONFIRM_REVOKED  = "CLOSE_CONFIRM_REVOKED";
    // Step 6 (V76): the ONE place outside the legacy state machine that writes
    // sales.ticket.status directly — see OrderConfirmationService's class Javadoc. Written once,
    // by TicketRepository.markQuotationIssuedForOrderConfirmation, immediately before
    // TicketService.confirmCustomer (unmodified) takes over the rest of the existing dual-track
    // payment pipeline.
    public static final String ORDER_CONFIRMED_FROM_QUOTATION = "ORDER_CONFIRMED_FROM_QUOTATION";

    // Quotation v2 (direct deal quotation, V165): NOTIFICATION `type` values only (hr.notification.
    // type), NOT sales.ticket_event.kind values -- never pass these into TicketRepository#addEvent*,
    // whose `kind` column is DB-constrained by chk_event_kind (V78) and does not list these three.
    // Plain ticket events for this feature reuse existing, already-valid kinds instead (SUBMITTED/
    // REJECTED/CANCELLED/REVISION_REQUESTED/QUOTATION_ISSUED) -- see DealQuotationService. Declared
    // here anyway, alongside every other event/notification vocabulary this class already holds
    // (e.g. PricingRequestEventKind's identical dual use), with Thai titles in
    // NotificationRepository.TICKET_EVENT_TITLES.
    public static final String DEAL_QUOTATION_SUBMITTED = "DEAL_QUOTATION_SUBMITTED";
    public static final String DEAL_QUOTATION_APPROVED  = "DEAL_QUOTATION_APPROVED";
    public static final String DEAL_QUOTATION_REJECTED  = "DEAL_QUOTATION_REJECTED";
    // Owner request (2026-09-16): a submit whose row is a revision (parentQuotationId != null --
    // covers BOTH #createRevision's own submit and the resubmit-after-ตีกลับ path) must read as a
    // REVISION to sales_manager/ceo, not the identical "รออนุมัติ" text DEAL_QUOTATION_SUBMITTED
    // sends for a first-time submit. Distinct kind so the mail subject itself says ฉบับแก้ไข --
    // see NotificationRepository.TICKET_EVENT_TITLES and DealQuotationService#notifySubmitted.
    public static final String DEAL_QUOTATION_REVISION_SUBMITTED = "DEAL_QUOTATION_REVISION_SUBMITTED";

    // Per-factory ใบขอซื้อ progress (V184, GLA-100): one event kind for every step advance, with
    // the actual step (th.co.glr.hr.importrequest.ImportRequestStep) named in the free-text
    // message/note rather than as a distinct kind per step -- see that class's own Javadoc for why
    // step names are never event kinds. related_document_type=IMPORT_REQUEST,
    // related_document_id=the sales.import_request row. chk_event_kind re-declared for this in V184.
    public static final String IMPORT_STEP_ADVANCED = "IMPORT_STEP_ADVANCED";

    // Per-factory ใบขอซื้อ order-email draft (V184, owner decision 09-18 #3 §B): written once when
    // the draft is marked sent (ImportRequestService#markEmailSent). The email itself is never sent
    // by this system -- a human copies the draft and sends it by hand -- so this event records only
    // that a human DID, not that the system did. chk_event_kind re-declared for this in V184, the
    // SAME re-declaration that adds IMPORT_STEP_ADVANCED just above. Coordinated with the pricing
    // session's V186 (import-request-per-factory-PLAN.md's "chk_event_kind COORDINATION" section) --
    // use exactly this name; do not invent another.
    public static final String IMPORT_REQUEST_EMAIL_SENT = "IMPORT_REQUEST_EMAIL_SENT";

    private TicketEventKind() {}
}
