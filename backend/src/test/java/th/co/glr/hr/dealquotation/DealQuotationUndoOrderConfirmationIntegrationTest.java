package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.PromoteToDealResultDto;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeService;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.DepositPolicy;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketStatus;

/**
 * Real-DB proof for {@link DealQuotationService#undoOrderConfirmation} ({@code POST
 * /api/deal-quotations/{id}/undo-confirm-order}), the reverse of
 * {@link DealQuotationService#confirmOrderFromDirectQuotation}: a rep who pressed "ยืนยันคำสั่งซื้อ" by mistake takes
 * the order back. Production services are hand-wired against real Postgres, and the undo is called through a REAL
 * {@code @Transactional} proxy ({@link #confirmer}), so the atomicity cases prove the method's own annotation.
 *
 * <p>Convention the tests rely on (the spec leaves the wording to the implementation): the
 * {@code ORDER_CONFIRMATION_UNDONE} event's note contains the substring {@code "เหตุผล"} if and only if a non-blank
 * reason was given, followed by the trimmed reason.
 *
 * <p>Every refusal asserts through {@link #assertRefusedAndNothingWritten}: a full before/after snapshot of the deal
 * ({@link #state}) is identical, so a refusal that half-wrote would be caught.
 */
class DealQuotationUndoOrderConfirmationIntegrationTest extends DirectQuotationDealTestBase {

    private record Confirmed(long deal, DealQuotationDto quotation) {}

    // ───────────────────────────────────────────────────────────────────────────────────
    // 1. Happy path — quotation-first deal with a deposit: every write is reversed
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void happyPath_quotationFirstDealWithDeposit_reversesEveryWriteOfTheConfirmation() {
        long deal = quotationOnlyTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);
        long partyId = partyOf(deal, approved.id());
        String preStage = summary(deal).salesStage();
        assertThat(preStage).as("fixture: the quotation's own recipient stage").isEqualTo(DealStage.QUOTE_OWNER);
        List<String> kindsBeforeConfirm = eventKinds(deal);
        // confirmed by the SALES MANAGER; undone by the owning rep -- the note must name who had confirmed.
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesManagerActor);
        assertThat(partyStatus(partyId)).as("fixture: confirmation awards the party").isEqualTo("AWARDED");
        assertThat(summary(deal).salesStage()).isEqualTo(DealStage.ORDER_RECEIVED);
        assertThat(itemCount(deal)).isEqualTo(2);

        PromoteToDealResultDto result = confirmer.undoOrderConfirmation(approved.id(), "ลูกค้าแจ้งว่ากดผิด", null, salesActor);

        assertThat(result.ticketId()).isEqualTo(deal);
        assertThat(result.ticket().status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(result.ticket().paymentStatus()).isNull();
        assertThat(result.ticket().salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(result.quotation().docStatus()).as("the quotation itself stays APPROVED")
            .isEqualTo(QuotationStatus.APPROVED);

        Map<String, Object> ticket = jdbc.queryForMap("""
            SELECT status, payment_status, sales_stage, fulfillment_status, deposit_policy, deposit_policy_set_by,
                   quotation_only FROM sales.ticket WHERE ticket_id = :id
            """, Map.of("id", deal));
        assertThat(ticket.get("status")).isEqualTo(TicketStatus.DRAFT);
        assertThat(ticket.get("payment_status")).isNull();
        assertThat(ticket.get("sales_stage")).as("exactly the pre-confirm stage").isEqualTo(preStage);
        assertThat(ticket.get("fulfillment_status")).isNull();
        assertThat(ticket.get("deposit_policy")).as("a 50% quotation leaves REQUIRED as it was")
            .isEqualTo(DepositPolicy.REQUIRED);
        assertThat(ticket.get("quotation_only")).as("provenance only, deliberately NOT restored").isEqualTo(false);
        assertThat(itemCount(deal)).as("the copies made at confirm are gone").isZero();
        assertThat(partyStatus(partyId)).as("AWARDED -> QUOTED").isEqualTo("QUOTED");

        // The confirmation's events are DELETED (SQL readers treat the promotion event's existence as "confirmed").
        assertThat(eventCount(deal, TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isZero();
        assertThat(eventCount(deal, TicketEventKind.CUSTOMER_CONFIRMED)).isZero();
        assertThat(jdbc.queryForList("SELECT to_status FROM sales.ticket_event WHERE ticket_id = :id AND kind = 'STAGE_CHANGED'"
            + " ORDER BY event_id", Map.of("id", deal), String.class))
            .as("the automatic move to ORDER_RECEIVED is gone; the earlier move onto the quotation's stage stays")
            .containsExactly(DealStage.QUOTE_OWNER);
        // Net event log == the log before the confirmation, plus exactly the one undo event, last.
        List<String> expectedKinds = new ArrayList<>(kindsBeforeConfirm);
        expectedKinds.add(TicketEventKind.ORDER_CONFIRMATION_UNDONE);
        assertThat(eventKinds(deal)).isEqualTo(expectedKinds);

        Map<String, Object> undo = jdbc.queryForMap("""
            SELECT from_status, to_status, related_document_type, related_document_id, actor_id, message
              FROM sales.ticket_event WHERE ticket_id = :id AND kind = :kind
            """, Map.of("id", deal, "kind", TicketEventKind.ORDER_CONFIRMATION_UNDONE));
        assertThat(undo.get("from_status")).isEqualTo(TicketStatus.QUOTATION_ISSUED);
        assertThat(undo.get("to_status")).isEqualTo(TicketStatus.DRAFT);
        assertThat(undo.get("related_document_type")).isEqualTo("QUOTATION");
        assertThat(((Number) undo.get("related_document_id")).longValue()).isEqualTo(approved.id());
        assertThat(((Number) undo.get("actor_id")).longValue()).isEqualTo(salesActor.id());
        assertThat((String) undo.get("message"))
            .contains(approved.number())
            .contains(salesManagerActor.name())
            .contains("เหตุผล").contains("ลูกค้าแจ้งว่ากดผิด")
            .doesNotContain("ใบแจ้งรับมัดจำ"); // no deposit notice was deleted
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // Cut-offs: each is a 409 and NOTHING is written
    // ───────────────────────────────────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = {"ISSUED", "SUPERSEDED"})
    void cutOff_aDepositNoticeThatIsNotADraft(String status) {
        Confirmed c = confirmedDeal();
        jdbc.update("""
            INSERT INTO sales.deposit_notice (ticket_id, version, status, customer_name, deposit_percent,
                subtotal, deposit_amount, vat_percent, vat_amount, total_payable)
            VALUES (:id, 1, :status, 'x', 0.3, 100000.00, 30000.00, 0.07, 2100.00, 32100.00)
            """, Map.of("id", c.deal(), "status", status));

        assertRefusedAndNothingWritten(c);
    }

    @Test
    void cutOff_aPaymentReceipt() {
        Confirmed c = confirmedDeal();
        jdbc.update("INSERT INTO sales.payment_receipt (ticket_id, kind, amount, recorded_by) VALUES (:id, 'DEPOSIT', 100, :by)",
            Map.of("id", c.deal(), "by", accountActor.id()));

        assertRefusedAndNothingWritten(c);
    }

    /** A deal made pre-portal by its first document: confirmed, the deposit policy set to NOT_REQUIRED, a PRE_PORTAL receipt. */
    private Confirmed prePortalDeal(String deposit) {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);
        new th.co.glr.hr.deliveryrequest.PrePortal(tickets, new th.co.glr.hr.deliveryrequest.DeliveryRequestRepository(jdbc),
            quotationService, ticketService).confirmAndMark(deal, approved.id(), new java.math.BigDecimal(deposit),
            java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")).minusDays(2), salesActor);
        return new Confirmed(deal, approved);
    }

    @Test
    void prePortal_undoRemovesTheMarking_receiptFlagAndPolicyGoBack() {
        Confirmed c = prePortalDeal("1000.00");
        String policyBefore = jdbc.queryForObject("""
            SELECT substr(message, position(' · เดิม ' in message) + 8) FROM sales.ticket_event
             WHERE ticket_id = :t AND kind = 'POLICY_CHANGED' AND message LIKE '%เดิม%'
            """, Map.of("t", c.deal()), String.class);
        jdbc.update("""
            INSERT INTO sales.expected_payment (ticket_id, kind, amount, expected_week, status, created_by)
            VALUES (:t, 'DEPOSIT', 1000, date '2026-11-30', 'OPEN', :by)""", Map.of("t", c.deal(), "by", salesActor.id()));
        // the fixture's expected row was filled by the receipt only if inserted before; apply it by hand to prove the reversal
        jdbc.update("UPDATE sales.expected_payment SET received_amount = 1000, status = 'RECEIVED', receipt_id = "
            + "(SELECT receipt_id FROM sales.payment_receipt WHERE ticket_id = :t) WHERE ticket_id = :t", Map.of("t", c.deal()));
        assertThat(jdbc.queryForObject("SELECT pre_portal FROM sales.ticket WHERE ticket_id = :t", Map.of("t", c.deal()), Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject("SELECT deposit_policy FROM sales.ticket WHERE ticket_id = :t", Map.of("t", c.deal()), String.class))
            .isEqualTo(DepositPolicy.NOT_REQUIRED);

        confirmer.undoOrderConfirmation(c.quotation().id(), null, null, salesActor);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.payment_receipt WHERE ticket_id = :t", Map.of("t", c.deal()), Long.class)).isZero();
        assertThat(jdbc.queryForMap("SELECT pre_portal, deposit_policy, deposit_policy_set_by, status FROM sales.ticket WHERE ticket_id = :t",
            Map.of("t", c.deal()))).containsEntry("pre_portal", false).containsEntry("deposit_policy", policyBefore)
            .containsEntry("deposit_policy_set_by", null).containsEntry("status", TicketStatus.DRAFT);
        assertThat(jdbc.queryForMap("SELECT status, received_amount, receipt_id FROM sales.expected_payment WHERE ticket_id = :t",
            Map.of("t", c.deal()))).containsEntry("status", "OPEN").containsEntry("receipt_id", null)
            .extracting(m -> m.get("received_amount")).isEqualTo(new java.math.BigDecimal("0.00"));
        assertThat(eventCount(c.deal(), TicketEventKind.ORDER_CONFIRMATION_UNDONE)).isEqualTo(1);
    }

    @Test
    void prePortal_undoIsRefusedWhenAnExpressRowResolvedAgainstTheReceipt() {
        Confirmed c = prePortalDeal("1000.00");
        long receiptId = jdbc.queryForObject("SELECT receipt_id FROM sales.payment_receipt WHERE ticket_id = :t", Map.of("t", c.deal()), Long.class);
        jdbc.update("UPDATE sales.payment_receipt SET express_doc_no = 'RE-X-1' WHERE receipt_id = :r", Map.of("r", receiptId));

        assertRefusedAndNothingWritten(c);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM sales.payment_receipt WHERE ticket_id = :t", Map.of("t", c.deal()), Long.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"DRAFT", "ISSUED"})
    void cutOff_anImportRequest(String status) {
        Confirmed c = confirmedDeal();
        long factoryId = jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, default_currency)
            VALUES ('Factory Undo Cutoff', 'IT', 'EUR') RETURNING factory_id
            """, Map.of(), Long.class);
        if ("DRAFT".equals(status)) {
            jdbc.update("""
                INSERT INTO sales.import_request (ticket_id, factory_id, factory_name, brand, version, status)
                VALUES (:t, :f, 'Factory Undo Cutoff', 'Brand', 1, 'DRAFT')
                """, Map.of("t", c.deal(), "f", factoryId));
        } else {
            jdbc.update("""
                INSERT INTO sales.import_request
                    (ticket_id, factory_id, factory_name, brand, version, status, doc_number, issue_date,
                     import_step, import_step_at, first_issued_date)
                VALUES (:t, :f, 'Factory Undo Cutoff', 'Brand', 1, 'ISSUED', 'IR-UNDO-1', CURRENT_DATE,
                        'CONTACTED', CURRENT_DATE, CURRENT_DATE)
                """, Map.of("t", c.deal(), "f", factoryId));
        }

        assertRefusedAndNothingWritten(c);
    }

    @ParameterizedTest
    @ValueSource(strings = {DealStage.DEPOSIT_RECEIVED, DealStage.PROCUREMENT, DealStage.DELIVERY_SCHEDULING})
    void cutOff_aSalesStagePastOrderReceived(String stage) {
        Confirmed c = confirmedDeal(); // 50% deposit: stays at ORDER_RECEIVED, fulfilment null
        tickets.updateSalesStage(c.deal(), stage);

        assertRefusedAndNothingWritten(c);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // Authz: who cannot reach it (403 before any 409), who can
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void nonOwningSales_ceo_import_account_areRefused403_andNothingIsWritten() {
        Confirmed confirmed = confirmedDeal();
        long unconfirmedDeal = pipelineTicket(salesActor);
        DealQuotationDto unconfirmed = approvedDirectQuotation(unconfirmedDeal, salesActor);
        Map<String, Object> confirmedBefore = state(confirmed.deal());
        Map<String, Object> unconfirmedBefore = state(unconfirmedDeal);

        for (UserPrincipal outsider : List.of(otherSalesActor, ceoActor, importActor, accountActor)) {
            assertThatThrownBy(() -> confirmer.undoOrderConfirmation(confirmed.quotation().id(), "x", null, outsider))
                .as(outsider.role() + " on a confirmed order")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThatThrownBy(() -> confirmer.undoOrderConfirmation(unconfirmed.id(), "x", null, outsider))
                .as(outsider.role() + " where the state alone would be a 409: 403 first")
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }

        assertThat(state(confirmed.deal())).isEqualTo(confirmedBefore);
        assertThat(state(unconfirmedDeal)).isEqualTo(unconfirmedBefore);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // Idempotent replay
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void replay_returnsTheSameState_andWritesNothingMore() {
        Confirmed c = confirmedDeal();
        PromoteToDealResultDto first = confirmer.undoOrderConfirmation(c.quotation().id(), "กดผิด", null, salesActor);
        Map<String, Object> afterFirst = state(c.deal());

        PromoteToDealResultDto replay = confirmer.undoOrderConfirmation(c.quotation().id(), "กดผิด", null, salesActor);

        assertThat(replay.ticketId()).isEqualTo(first.ticketId());
        assertThat(replay.ticket().status()).isEqualTo(TicketStatus.DRAFT);
        assertThat(replay.ticket().salesStage()).isEqualTo(first.ticket().salesStage());
        assertThat(state(c.deal())).isEqualTo(afterFirst);
        assertThat(eventCount(c.deal(), TicketEventKind.ORDER_CONFIRMATION_UNDONE)).isEqualTo(1);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // Atomicity — through the real @Transactional proxy
    // ───────────────────────────────────────────────────────────────────────────────────

    /**
     * A failure injected at a write the undo makes (a trigger that RAISEs) rolls every earlier write back. Three
     * injection points so that whichever write the implementation does LAST is covered: the undo event insert, the
     * promotion event delete, the ticket status reset. The exception must be the database's, never the not-yet-written
     * undo itself.
     */
    @ParameterizedTest
    @ValueSource(strings = {"undoEventInsert", "promotionEventDelete", "ticketStatusReset"})
    void aFailureLateInTheUndo_rollsTheWholeUndoBack(String failurePoint) {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedZeroDepositQuotation(deal); // exercises policy flip, items, events
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        Map<String, Object> before = state(deal);
        String trigger = switch (failurePoint) {
            case "undoEventInsert" -> "BEFORE INSERT ON sales.ticket_event FOR EACH ROW"
                + " WHEN (NEW.kind = 'ORDER_CONFIRMATION_UNDONE')";
            case "promotionEventDelete" -> "BEFORE DELETE ON sales.ticket_event FOR EACH ROW"
                + " WHEN (OLD.kind = 'DEAL_PROMOTED_FROM_QUOTATION')";
            default -> "BEFORE UPDATE ON sales.ticket FOR EACH ROW"
                + " WHEN (OLD.status = 'quotation_issued' AND NEW.status = 'draft')";
        };
        jdbc.getJdbcOperations().execute("CREATE FUNCTION sales.fail_undo_test() RETURNS trigger LANGUAGE plpgsql AS $$"
            + " BEGIN RAISE EXCEPTION 'injected undo failure'; END $$");
        jdbc.getJdbcOperations().execute("CREATE TRIGGER trg_fail_undo_test " + trigger
            + " EXECUTE FUNCTION sales.fail_undo_test()");

        assertThatThrownBy(() -> confirmer.undoOrderConfirmation(approved.id(), "กดผิด", null, salesActor))
            .as("the injected database failure, not an ApiException")
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("injected undo failure");

        assertThat(state(deal)).as("nothing of the undo survived").isEqualTo(before);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // Concurrency: a draft cannot be created behind an undo's back
    // ───────────────────────────────────────────────────────────────────────────────────

    /**
     * The undo holds the ticket row lock while it deletes the draft deposit notices. A deposit-notice creator that did its
     * state check WITHOUT that lock could insert a draft after the delete and survive the undo. So creation waits on the
     * row lock, then re-reads the deal: after an undo commits first it is refused 409 and no draft row exists.
     */
    @Test
    void depositDraftCreation_waitsForTheTicketRowLock_andIsRefusedOnceAnUndoCommitted() throws Exception {
        Confirmed c = confirmedDeal();
        DepositNoticeService creator = transactional(depositNoticeService);
        Throwable outcome = outcomeAfterWaitingForTicketRowLock(c.deal(), () -> creator.createDraft(c.deal(),
            new DepositNoticeDraftRequest(null, null, null, null, null, null, null, null), salesActor));
        assertThat(outcome).isInstanceOfSatisfying(ApiException.class,
            e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        assertThat(count("SELECT COUNT(*) FROM sales.deposit_notice WHERE ticket_id = :id", c.deal())).isZero();
    }

    /**
     * A second connection holds {@code SELECT ... FOR UPDATE} on the deal's ticket row; {@code creator} runs on another
     * thread and must still be blocked after 2 s. The holder then commits what an undo commits (deal back to draft)
     * and the creator's failure is returned (null if it succeeded).
     */
    private Throwable outcomeAfterWaitingForTicketRowLock(long deal, java.util.concurrent.Callable<?> creator)
            throws Exception {
        return outcomeAfterWaitingForTicketRowLock(deal, creator,
            "UPDATE sales.ticket SET status = 'draft', payment_status = NULL WHERE ticket_id = ?");
    }

    /** {@link #outcomeAfterWaitingForTicketRowLock(long, java.util.concurrent.Callable)} with the holder's own
     * undo-effect UPDATE (one {@code ?} = the ticket id), for a writer whose gate reads more than status. */
    private Throwable outcomeAfterWaitingForTicketRowLock(long deal, java.util.concurrent.Callable<?> creator,
                                                          String undoUpdateSql) throws Exception {
        javax.sql.DataSource ds = ((org.springframework.jdbc.datasource.DataSourceTransactionManager) transactionManager)
            .getDataSource();
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (java.sql.Connection holder = ds.getConnection()) {
            holder.setAutoCommit(false);
            try (java.sql.PreparedStatement ps = holder.prepareStatement(
                    "SELECT 1 FROM sales.ticket WHERE ticket_id = ? FOR UPDATE")) {
                ps.setLong(1, deal);
                ps.execute();
            }
            java.util.concurrent.Future<?> create = pool.submit(creator);
            assertThatThrownBy(() -> create.get(2, java.util.concurrent.TimeUnit.SECONDS))
                .as("creation must wait on the ticket row lock another transaction holds")
                .isInstanceOf(java.util.concurrent.TimeoutException.class);
            try (java.sql.PreparedStatement ps = holder.prepareStatement(undoUpdateSql)) {
                ps.setLong(1, deal);
                ps.executeUpdate();
            }
            holder.commit();
            try {
                create.get(20, java.util.concurrent.TimeUnit.SECONDS);
                return null;
            } catch (java.util.concurrent.ExecutionException e) {
                return e.getCause();
            }
        } finally {
            pool.shutdownNow();
        }
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // Delivery rounds (V236): a sent round holds lines on the items the undo deletes
    // ───────────────────────────────────────────────────────────────────────────────────

    @Test
    void sentDeliveryRound_refusesTheUndoWithItsOwnReason() {
        Confirmed c = confirmedDeal();
        long round = insertRound(c.deal(), "SENT");
        jdbc.update("INSERT INTO sales.delivery_request_line (delivery_request_id, item_id, qty) "
            + "SELECT :r, item_id, 1 FROM sales.ticket_item WHERE ticket_id = :t LIMIT 1", Map.of("r", round, "t", c.deal()));
        assertThatThrownBy(() -> confirmer.undoOrderConfirmation(c.quotation().id(), "กดผิด", null, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ส่งเรื่องขอออกใบกำกับ");
            });
        assertThat(itemCount(c.deal())).as("nothing was deleted").isPositive();
    }

    @Test
    void draftDeliveryRound_isDroppedWithTheUndo_andItsFilesStayOnTheDeal() {
        Confirmed c = confirmedDeal();
        long round = insertRound(c.deal(), "DRAFT");
        jdbc.update("INSERT INTO sales.attachment (ticket_id, file_name, file_path, attach_type, uploaded_by, delivery_request_id,"
            + " uploader_side) VALUES (:t, 'slip.jpg', '/x/slip.jpg', 'OTHER', :u, :r, 'SALES')",
            Map.of("t", c.deal(), "u", salesActor.id(), "r", round));

        confirmer.undoOrderConfirmation(c.quotation().id(), "กดผิด", null, salesActor);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.delivery_request WHERE ticket_id = :t",
            Map.of("t", c.deal()), Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM sales.attachment WHERE ticket_id = :t AND delivery_request_id IS NULL",
            Map.of("t", c.deal()), Integer.class)).as("the file is kept, unlinked").isEqualTo(1);
    }

    private long insertRound(long deal, String status) {
        return jdbc.queryForObject("INSERT INTO sales.delivery_request (ticket_id, round_no, status) VALUES (:t, 1, :s)"
            + " RETURNING delivery_request_id", Map.of("t", deal, "s", status), Long.class);
    }

    // ───────────────────────────────────────────────────────────────────────────────────
    // Helpers
    // ───────────────────────────────────────────────────────────────────────────────────

    /** An ordinary deal with an APPROVED 50% direct quotation, order confirmed by the owning rep. */
    private Confirmed confirmedDeal() {
        long deal = pipelineTicket(salesActor);
        DealQuotationDto approved = approvedDirectQuotation(deal, salesActor);
        confirmer.confirmOrderFromDirectQuotation(approved.id(), salesActor);
        return new Confirmed(deal, approved);
    }

    private void assertRefusedAndNothingWritten(Confirmed c) {
        Map<String, Object> before = state(c.deal());
        assertThatThrownBy(() -> confirmer.undoOrderConfirmation(c.quotation().id(), "กดผิด", null, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).as("a Thai message of its own").isNotBlank().containsPattern("[\\u0E00-\\u0E7F]");
            });
        assertThat(state(c.deal())).as("a refusal writes nothing").isEqualTo(before);
        assertThat(eventCount(c.deal(), TicketEventKind.DEAL_PROMOTED_FROM_QUOTATION)).isEqualTo(1);
        assertThat(eventCount(c.deal(), TicketEventKind.ORDER_CONFIRMATION_UNDONE)).isZero();
    }

    /** Everything an undo may touch (or must not), for before/after equality. */
    private Map<String, Object> state(long deal) {
        Map<String, Object> m = new LinkedHashMap<>(jdbc.queryForMap("""
            SELECT status, payment_status, sales_stage, fulfillment_status, lifecycle, deposit_policy,
                   deposit_policy_reason, deposit_policy_set_by, quotation_only, procurement_source_decided_at
              FROM sales.ticket WHERE ticket_id = :id
            """, Map.of("id", deal)));
        m.put("items", jdbc.queryForList(
            "SELECT item_id, qty, qty_from_stock, qty_delivered FROM sales.ticket_item WHERE ticket_id = :id ORDER BY item_id",
            Map.of("id", deal)));
        m.put("events", jdbc.queryForList(
            "SELECT event_id, kind, from_status, to_status FROM sales.ticket_event WHERE ticket_id = :id ORDER BY event_id",
            Map.of("id", deal)));
        m.put("depositNotices", jdbc.queryForList(
            "SELECT deposit_notice_id, status FROM sales.deposit_notice WHERE ticket_id = :id ORDER BY 1", Map.of("id", deal)));
        m.put("depositNoticeItems", jdbc.queryForList("""
            SELECT i.item_id FROM sales.deposit_notice_item i JOIN sales.deposit_notice d USING (deposit_notice_id)
             WHERE d.ticket_id = :id ORDER BY 1
            """, Map.of("id", deal)));
        m.put("quotations", jdbc.queryForList(
            "SELECT quotation_id, doc_status, origin FROM sales.quotation WHERE ticket_id = :id ORDER BY 1", Map.of("id", deal)));
        m.put("parties", jdbc.queryForList(
            "SELECT party_id, status FROM sales.deal_party WHERE ticket_id = :id ORDER BY 1", Map.of("id", deal)));
        return m;
    }

    private List<String> eventKinds(long deal) {
        return jdbc.queryForList("SELECT kind FROM sales.ticket_event WHERE ticket_id = :id ORDER BY event_id",
            Map.of("id", deal), String.class);
    }

    private int count(String sql, long deal) {
        return jdbc.queryForObject(sql, Map.of("id", deal), Integer.class);
    }

    /** The quotation's deal_party (V204): the row the quotation already points at, else one inserted for it. */
    private long partyOf(long deal, long quotationId) {
        Long party = jdbc.queryForObject("SELECT party_id FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), Long.class);
        if (party == null) {
            party = jdbc.queryForObject("""
                INSERT INTO sales.deal_party (ticket_id, customer_id, role, status, is_headline)
                VALUES (:t, :c, 'OWNER', 'QUOTED', FALSE) RETURNING party_id
                """, Map.of("t", deal, "c", customer.id()), Long.class);
            jdbc.update("UPDATE sales.quotation SET party_id = :p WHERE quotation_id = :id",
                Map.of("p", party, "id", quotationId));
        }
        return party;
    }

    private String partyStatus(long partyId) {
        return jdbc.queryForObject("SELECT status FROM sales.deal_party WHERE party_id = :id", Map.of("id", partyId),
            String.class);
    }
}
