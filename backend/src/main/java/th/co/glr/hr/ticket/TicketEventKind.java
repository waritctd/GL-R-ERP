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
    // CEO manual price override on a ticket item (chk_event_kind extended in V48).
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
    // Order confirmation (V76): the ONE place outside the legacy state machine that writes
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
    // A submit whose row is a revision (parentQuotationId != null --
    // covers BOTH #createRevision's own submit and the resubmit-after-ตีกลับ path) must read as a
    // REVISION to sales_manager/ceo, not the identical "รออนุมัติ" text DEAL_QUOTATION_SUBMITTED
    // sends for a first-time submit. Distinct kind so the mail subject itself says ฉบับแก้ไข --
    // see NotificationRepository.TICKET_EVENT_TITLES and DealQuotationService#notifySubmitted.
    public static final String DEAL_QUOTATION_REVISION_SUBMITTED = "DEAL_QUOTATION_REVISION_SUBMITTED";

    // Hand-off notifications: NOTIFICATION `type` values only (hr.notification.type), NOT
    // ticket_event kinds -- never pass these into TicketRepository#addEvent* (chk_event_kind). Thai
    // titles live in NotificationRepository.TICKET_EVENT_TITLES. DELIVERY_COMPLETED above is reused.
    public static final String IMPORT_REQUEST_ISSUED = "IMPORT_REQUEST_ISSUED";
    public static final String IMPORT_REQUEST_REVISED = "IMPORT_REQUEST_REVISED";
    public static final String DEPOSIT_PAYMENT_CONFIRMED = "DEPOSIT_PAYMENT_CONFIRMED";
    public static final String IMPORT_STARTED = "IMPORT_STARTED";
    public static final String IMPORT_GOODS_RECEIVED_ALL = "IMPORT_GOODS_RECEIVED_ALL";
    public static final String REMAINING_INVOICE_ISSUED = "REMAINING_INVOICE_ISSUED";
    public static final String CLOSE_READY_FOR_CEO = "CLOSE_READY_FOR_CEO";
    public static final String ORDER_CONFIRMED_NO_DEPOSIT = "ORDER_CONFIRMED_NO_DEPOSIT";

    // Payment/deal-lifecycle notifications: NOTIFICATION `type` values only, like the hand-off block
    // above -- never ticket_event kinds. Titles in NotificationRepository.TICKET_EVENT_TITLES.
    // FACTORY_NOT_AVAILABLE, CUSTOMER_QUOTATION_EXPIRED, PRICING_REQUEST_CANCELLED and
    // PRICING_REQUEST_REVISED (PricingRequestEventKind) are reused as types too.
    public static final String PAYMENT_RECEIVED = "PAYMENT_RECEIVED";
    public static final String PAYMENT_FULLY_PAID = "PAYMENT_FULLY_PAID";
    public static final String DEAL_CLOSED = "DEAL_CLOSED";
    public static final String CLOSE_CONFIRMATION_REVOKED = "CLOSE_CONFIRMATION_REVOKED";
    public static final String DEAL_LOST = "DEAL_LOST";
    public static final String DEAL_CANCELLED = "DEAL_CANCELLED";
    public static final String DEAL_ON_HOLD = "DEAL_ON_HOLD";
    public static final String DEAL_DORMANT = "DEAL_DORMANT";
    public static final String DEAL_RESUMED = "DEAL_RESUMED";
    public static final String DEAL_REOPENED = "DEAL_REOPENED";
    public static final String DEAL_QUOTATION_CANCELLED = "DEAL_QUOTATION_CANCELLED";
    public static final String DEAL_QUOTATION_DECIDED = "DEAL_QUOTATION_DECIDED";

    // Per-factory ใบขอซื้อ progress (V184): one event kind for every step advance, with
    // the actual step (th.co.glr.hr.importrequest.ImportRequestStep) named in the free-text
    // message/note rather than as a distinct kind per step -- see that class's own Javadoc for why
    // step names are never event kinds. related_document_type=IMPORT_REQUEST,
    // related_document_id=the sales.import_request row. chk_event_kind re-declared for this in V184.
    public static final String IMPORT_STEP_ADVANCED = "IMPORT_STEP_ADVANCED";

    // Per-factory ใบขอซื้อ order-email draft (V184): written once when
    // the draft is marked sent (ImportRequestService#markEmailSent). The email itself is never sent
    // by this system -- a human copies the draft and sends it by hand -- so this event records only
    // that a human DID, not that the system did. chk_event_kind re-declared for this in V184, the
    // SAME re-declaration that adds IMPORT_STEP_ADVANCED just above (the name is pinned by the
    // CHECK constraint).
    public static final String IMPORT_REQUEST_EMAIL_SENT = "IMPORT_REQUEST_EMAIL_SENT";

    // "สร้างจากใบเดิม" / สั่งเหมือนเดิม (V186): a REAL sales.ticket_event.kind value
    // (unlike the three DEAL_QUOTATION_* notification-only kinds above) -- chk_event_kind was
    // widened for it in V186. Written once, by DealQuotationService#createReorder, when an
    // APPROVED direct-deal quotation is cloned into a new independent DRAFT. Deliberately NOT a
    // reuse of REVISION_REQUESTED: a reorder never revises, rejects, or supersedes its source, so
    // sharing that kind would misdescribe it in DealHistoryPanel.jsx's EVENT_KIND_LABEL ("ขอแก้ไข").
    public static final String DEAL_QUOTATION_REORDERED = "DEAL_QUOTATION_REORDERED";

    // A deal may hold only ONE APPROVED DEAL_DIRECT quotation at a
    // time -- approving a new one (a first issue, a revision, or a reorder clone) supersedes
    // every OTHER currently-APPROVED DEAL_DIRECT quotation on the same ticket. A REAL
    // sales.ticket_event.kind value (chk_event_kind widened for it in V186). Written once per
    // superseded sibling, by DealQuotationService#approve, message "ใบ {old} ถูกแทนที่ด้วย {new}" --
    // the ancestor-chain supersede walk (a DIFFERENT rule: climbing a REJECTED/DRAFT
    // parentQuotationId lineage) stays silent; this kind is only for the same-ticket sweep.
    public static final String DEAL_QUOTATION_SUPERSEDED = "DEAL_QUOTATION_SUPERSEDED";

    // A REAL sales.ticket_event.kind value (chk_event_kind widened for it in V193). Written once,
    // by DealQuotationService#confirmOrderFromDirectQuotation (formerly named promoteToDeal — this
    // kind keeps the old name because the DB CHECK pins it), when the order is confirmed from an
    // APPROVED direct (DEAL_DIRECT) quotation (draft -> quotation_issued, related document = that
    // QUOTATION). Deliberately NOT a reuse of
    // ORDER_CONFIRMED_FROM_QUOTATION, which means a customer-accepted PRICING_REQUEST quotation's
    // order confirmation (OrderConfirmationService) — a different business event.
    public static final String DEAL_PROMOTED_FROM_QUOTATION = "DEAL_PROMOTED_FROM_QUOTATION";

    // ── V204 deal party (docs/decisions/deal-party-model.md) ──
    /** A customer joined the deal in a role (DESIGNER / OWNER / BUYER). */
    public static final String DEAL_PARTY_ADDED = "DEAL_PARTY_ADDED";
    /** "บันทึกผลประมูล": which contractors won, which lost or still wait. Written once on the primary deal. */
    public static final String BID_OUTCOME_RECORDED = "BID_OUTCOME_RECORDED";
    /** On a child deal forked for a second winner: cites the parent it was split from. */
    public static final String DEAL_SPLIT_FROM_PARENT = "DEAL_SPLIT_FROM_PARENT";
    /** On a container deal emptied by the V205 reconcile: cites the deal its quotation moved to. */
    public static final String DEAL_MERGED_INTO = "DEAL_MERGED_INTO";

    private TicketEventKind() {}
}
