-- sales.ticket.payment_status has never had a CHECK constraint since the column was introduced:
-- V6 created it VARCHAR(20) with no constraint, V39 attempted to widen it to VARCHAR(40) (a
-- no-op — `ADD COLUMN IF NOT EXISTS` on an already-existing column), and V44 actually widened it.
-- None of the three ever constrained its *values* — only its width. Five literals
-- (CUSTOMER_CONFIRMED, DEPOSIT_NOTICE_ISSUED, DEPOSIT_PAID, AWAITING_FINAL_PAYMENT, FULLY_PAID)
-- are hand-written as Java string constants across all 7 production write sites
-- (TicketService.confirmCustomer/applyGoodsReceived/confirmFinalPayment/reconcilePaymentStatus
-- [x3], DepositNoticeService.issue). With no CHECK, a single-character typo in any one of those
-- literals would silently persist an unrecognised value that nothing downstream can ever
-- transition away from — the deal's payment track would deadlock silently, undetected until
-- someone happened to inspect the row directly.
--
-- th.co.glr.hr.ticket.PaymentTrack is the Java mirror of this constraint's value set (and the
-- state machine governing which transitions between them are legal — this constraint only
-- guards the five literals themselves, not the transitions; PaymentTrack.canTransition/
-- stepsBetween is where transition legality is enforced, in TicketRepository.advancePaymentStatus,
-- before any SQL runs). Keep both in sync if this set ever changes.
--
-- NULL is explicitly permitted: a ticket's payment track has not started until confirmCustomer's
-- null -> CUSTOMER_CONFIRMED entry edge runs, and payment_status has no default (V6/V39/V44 never
-- gave it one), so NULL is the normal, common resting state for any ticket that has not yet
-- reached QUOTATION_ISSUED and been customer-confirmed.
--
-- No NOT VALID and no pre-emptive data cleanup here, deliberately: if any existing row already
-- carries a value outside this set, this migration is SUPPOSED to fail loudly on apply so that
-- row is investigated, not silently coerced or grandfathered in past a constraint meant to catch
-- exactly that class of bug.
ALTER TABLE sales.ticket
    ADD CONSTRAINT chk_ticket_payment_status CHECK (
        payment_status IS NULL OR payment_status IN (
            'CUSTOMER_CONFIRMED', 'DEPOSIT_NOTICE_ISSUED', 'DEPOSIT_PAID',
            'AWAITING_FINAL_PAYMENT', 'FULLY_PAID'
        )
    );
