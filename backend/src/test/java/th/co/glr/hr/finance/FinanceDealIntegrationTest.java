package th.co.glr.hr.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.deposit.DepositNoticeDraftRequest;
import th.co.glr.hr.deposit.DepositNoticeItemRequest;
import th.co.glr.hr.ticket.AttachType;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DealStage;

/**
 * GET /api/finance/deals/{id} against the real service and real Postgres (H1, owner decision A2).
 * Written wrong-way-round: every role that must NOT reach a deal, and every key that must NOT
 * appear in the payload, is asserted before the happy path is.
 */
class FinanceDealIntegrationTest extends FinanceDealTestBase {

    // ── who may NOT reach it (wrong way round first) ────────────────────────────────────────

    @Test
    void everyRoleExceptAccountAndCeo_isForbidden_evenOnTheirOwnDeal() {
        long ticketId = createTicket(DealStage.PROCUREMENT); // salesRep owns it
        tickets.updatePaymentStatusUnchecked(ticketId, "DEPOSIT_PAID");

        for (UserPrincipal actor : List.of(salesRep, salesManager, importUser, hrUser, employeeUser)) {
            assertThatThrownBy(() -> service.get(ticketId, actor))
                .as("role " + actor.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        }
    }

    @Test
    void accountIsForbidden_onAPreOrderDeal() {
        long ticketId = createTicket(DealStage.QUOTE_OWNER);

        assertThatThrownBy(() -> service.get(ticketId, accountUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    @Test
    void accountIsForbidden_onALostProcurementDeal() {
        long ticketId = createTicket(DealStage.PROCUREMENT);
        tickets.updateLifecycle(ticketId, DealLifecycle.CLOSED_LOST);

        assertThatThrownBy(() -> service.get(ticketId, accountUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    @Test
    void missingDeal_is404_forAccountAndCeo() {
        for (UserPrincipal actor : List.of(accountUser, ceoUser)) {
            assertThatThrownBy(() -> service.get(999_999_999L, actor))
                .as("role " + actor.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(404));
        }
    }

    // ── who may ─────────────────────────────────────────────────────────────────────────────

    @Test
    void ceoReadsAnyDeal_evenAPreOrderOne() {
        long ticketId = createTicket(DealStage.QUOTE_OWNER);

        FinanceDealDto dto = service.get(ticketId, ceoUser);

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(ticketId);
        assertThat(dto.salesStage()).isEqualTo(DealStage.QUOTE_OWNER);
        assertThat(dto.moneyMilestone()).isNull();
        assertThat(dto.milestoneTrack()).hasSize(5);
        assertThat(dto.milestoneTrack()).noneMatch(FinanceDealDto.Milestone::current);
    }

    @Test
    void accountReadsAProcurementDepositPaidDeal_withMilestone3AndFinanceFields() {
        long ticketId = createTicket(DealStage.PROCUREMENT);
        tickets.updatePaymentStatusUnchecked(ticketId, "DEPOSIT_PAID");
        long quotationId = insertAcceptedQuotation(ticketId, "QT-FIN-0001", new BigDecimal("100000.00"));
        insertQuotationItem(quotationId, 1, "กระเบื้อง PADANA 60x60", new BigDecimal("50"), "ตร.ม.",
            new BigDecimal("2000.00"), new BigDecimal("100000.00"));
        insertPayment(ticketId, "DEPOSIT", new BigDecimal("50000.00"));

        FinanceDealDto dto = service.get(ticketId, accountUser);

        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(ticketId);
        assertThat(dto.salesStage()).isEqualTo(DealStage.PROCUREMENT);
        assertThat(dto.customerName()).isEqualTo("ลูกค้าทดสอบ");
        assertThat(dto.moneyMilestone().index()).isEqualTo(3);
        assertThat(dto.moneyMilestone().key()).isEqualTo("PROCUREMENT");
        assertThat(dto.moneyMilestone().current()).isTrue();
        assertThat(dto.milestoneTrack()).extracting(FinanceDealDto.Milestone::index).containsExactly(1, 2, 3, 4, 5);

        assertThat(dto.items()).hasSize(1);
        FinanceDealDto.Item item = dto.items().get(0);
        assertThat(item.description()).contains("PADANA");
        assertThat(item.qty()).isEqualByComparingTo("50");
        assertThat(item.unit()).isEqualTo("ตร.ม.");
        assertThat(item.unitPrice()).isEqualByComparingTo("2000.00");
        assertThat(item.lineTotal()).isEqualByComparingTo("100000.00");

        FinanceDealDto.Money money = dto.money();
        assertThat(money.paymentStatus()).isEqualTo("DEPOSIT_PAID");
        assertThat(money.amountPayable()).isEqualByComparingTo("107000.00"); // VAT-inclusive (owner ruling 2026-09-30)
        assertThat(money.amountPaid()).isEqualByComparingTo("50000.00");
        assertThat(money.amountOutstanding()).isEqualByComparingTo("57000.00");
        assertThat(money.payments()).hasSize(1);
        assertThat(money.payments().get(0).kind()).isEqualTo("DEPOSIT");

        assertThat(dto.documents().acceptedQuotation()).isNotNull();
        assertThat(dto.documents().acceptedQuotation().number()).isEqualTo("QT-FIN-0001");
    }

    /**
     * C1 (owner rules of 2026-10-05): a deal whose ACCEPTED quotation asks 0% has no deposit step, so the
     * deposit milestone (ขั้น 11, index 2 on the track) is shown as skipped and the deal stands on milestone 1.
     * This was {@code bypassPolicyDeal_flagsTheDepositMilestoneAsSkipped}, whose "no deposit" deal was built by
     * writing the deal-level switch ({@code CREDIT_CUSTOMER}) — a fixture for a rule the owner replaced: the
     * switch decides nothing any more. The fixture is now {@link #confirmedOrderAsking}: the 0% quotation row
     * is written BEFORE the order is confirmed through the real service, and the switch is never written.
     * Red today — the read model derives "skipped" from the switch ({@code MoneyMilestone.isSkipped(step,
     * depositPolicy)}), which this deal never had written. The two assertions that hold today come first.
     */
    @Test
    void zeroPercentDeal_flagsTheDepositMilestoneAsSkipped() {
        long ticketId = confirmedOrderAsking(0);

        FinanceDealDto dto = service.get(ticketId, accountUser);

        assertThat(dto.moneyMilestone().index()).isEqualTo(1);
        assertThat(dto.milestoneTrack().get(0).skipped()).isFalse();
        assertThat(dto.milestoneTrack().get(1).skipped()).as("the deposit milestone of a 0% deal").isTrue();
    }

    /**
     * The green mirror of the test above — nothing else pinned it on this read model: a deposit deal (its
     * accepted quotation asks 50%) does NOT show the deposit milestone as skipped. Green today and after.
     */
    @Test
    void depositDeal_doesNotFlagTheDepositMilestoneAsSkipped() {
        long ticketId = confirmedOrderAsking(50);

        FinanceDealDto dto = service.get(ticketId, accountUser);

        assertThat(dto.moneyMilestone().index()).isEqualTo(1);
        assertThat(dto.milestoneTrack().get(1).skipped()).as("the deposit milestone of a 50% deal").isFalse();
    }

    /**
     * C1: a deal at ORDER_RECEIVED whose accepted quotation asks {@code depositPercent} (whole percent), whose
     * order the owning rep has REALLY confirmed through {@code TicketService#confirmCustomer}. The quotation
     * row — the origin-tagged form on a bare ticket ({@code origin = 'DEAL_DIRECT'}, {@code doc_status =
     * 'APPROVED'}), carrying its {@code deposit_percent} — is written BEFORE that confirmation (the S1 fixture
     * rule, so the fixture is right whether the read model reads the quotation when it is asked or derives
     * something when the order is confirmed), and the deal-level deposit switch is never written.
     */
    private long confirmedOrderAsking(int depositPercent) {
        long ticketId = createTicket(DealStage.ORDER_RECEIVED);
        jdbc.update("""
            INSERT INTO sales.quotation (ticket_id, number, issued_by, doc_status, quotation_version,
                                         origin, deposit_percent)
            VALUES (:ticketId, :number, :by, 'APPROVED', 1, 'DEAL_DIRECT', :depositPercent)
            """, new MapSqlParameterSource().addValue("ticketId", ticketId)
                .addValue("number", "QTD-FIN-" + ticketId).addValue("by", salesRepId)
                .addValue("depositPercent", (short) depositPercent));
        tickets.markQuotationIssuedForOrderConfirmation(ticketId); // draft -> quotation_issued
        ticketService.confirmCustomer(ticketId, salesRep);
        return ticketId;
    }

    @Test
    void accountReadsAClosedPaidDeal_asMilestone5() {
        long ticketId = createTicket(DealStage.CLOSED_PAID);

        assertThat(service.get(ticketId, accountUser).moneyMilestone().index()).isEqualTo(5);
    }

    @Test
    void documentsBlock_listsQuotationDepositRemainingInvoiceTaxInvoiceBillingNotePoAndContract() {
        long ticketId = createTicket(DealStage.DELIVERED);
        long quotationId = insertAcceptedQuotation(ticketId, "QT-FIN-0002", new BigDecimal("80000.00"));
        insertQuotationItem(quotationId, 1, "สินค้า", new BigDecimal("1"), "ชุด",
            new BigDecimal("80000.00"), new BigDecimal("80000.00"));

        long depositId = depositNotices.createDraft(ticketId,
            new DepositNoticeDraftRequest("ลูกค้าทดสอบ", null, null, null, "REF", new BigDecimal("0.50"), List.of(), null),
            List.of(new DepositNoticeItemRequest(1, "สินค้า", BigDecimal.ONE, "ชุด",
                new BigDecimal("80000.00"), null, new BigDecimal("80000.00"))));
        depositNotices.issue(depositId, salesRepId, "พนักงานขาย ทดสอบ");
        // a DRAFT deposit notice must not be listed
        depositNotices.createDraft(ticketId,
            new DepositNoticeDraftRequest("ลูกค้าทดสอบ", null, null, null, "REF2", new BigDecimal("0.50"), List.of(), null),
            List.of(new DepositNoticeItemRequest(1, "สินค้า", BigDecimal.ONE, "ชุด",
                new BigDecimal("80000.00"), null, new BigDecimal("80000.00"))));

        long riId = remainingInvoices.insertDraft(ticketId, null, depositId, "REF", null, LocalDate.now(), List.of(),
            "ลูกค้าทดสอบ", null, null, null, null,
            new BigDecimal("80000"), new BigDecimal("40000"), new BigDecimal("40000"),
            new BigDecimal("2800"), new BigDecimal("42800"), salesRepId, "พนักงานขาย ทดสอบ");
        remainingInvoices.issue(riId, "RI-FIN-1", 1, "RI-FIN-1", salesRepId, "พนักงานขาย ทดสอบ");

        long invoiceAtt = attach(ticketId, "tax-invoice.pdf", AttachType.INVOICE);
        long poAtt = attach(ticketId, "po.pdf", AttachType.PO);
        long signedAtt = attach(ticketId, "signed.pdf", AttachType.SIGNED_QUOTATION);
        long otherAtt = attach(ticketId, "contract.pdf", AttachType.OTHER);

        long billingNoteId = insertIssuedBillingNote(ticketId, "BN-FIN-1", new BigDecimal("42800.00"), "ISSUED");

        FinanceDealDto.Documents docs = service.get(ticketId, accountUser).documents();

        assertThat(docs.acceptedQuotation().number()).isEqualTo("QT-FIN-0002");
        assertThat(docs.depositNotices()).hasSize(1);
        assertThat(docs.depositNotices().get(0).id()).isEqualTo(depositId);
        assertThat(docs.remainingInvoices()).extracting(FinanceDealDto.RemainingInvoiceDoc::id).containsExactly(riId);
        assertThat(docs.taxInvoices()).extracting(FinanceDealDto.FileDoc::id).containsExactly(invoiceAtt);
        assertThat(docs.purchaseOrders()).extracting(FinanceDealDto.FileDoc::id).containsExactly(poAtt);
        assertThat(docs.contracts()).extracting(FinanceDealDto.FileDoc::id).containsExactlyInAnyOrder(signedAtt, otherAtt);
        assertThat(docs.billingNotes()).extracting(FinanceDealDto.BillingNoteDoc::id).containsExactly(billingNoteId);
        assertThat(docs.billingNotes().get(0).amountForThisDeal()).isEqualByComparingTo("42800.00");
    }

    @Test
    void payload_hasNoPricingCostTrackingActivityOrCommentKeys() throws Exception {
        long ticketId = createTicket(DealStage.PROCUREMENT);
        tickets.updatePaymentStatusUnchecked(ticketId, "DEPOSIT_PAID");
        // Seed everything a leaky implementation would surface.
        jdbc.update("""
            UPDATE sales.ticket SET win_probability = 80, designer_name = 'ผู้ออกแบบลับ',
                   owner_name = 'เจ้าของลับ', buyer_name = 'ผู้ซื้อลับ' WHERE ticket_id = :id
            """, new MapSqlParameterSource("id", ticketId));
        jdbc.update("""
            INSERT INTO sales.pricing_request (request_code, ticket_id, recipient_type, status, requested_by)
            VALUES ('PCR-FIN-1', :id, 'DESIGNER', 'SUBMITTED', :by)
            """, new MapSqlParameterSource("id", ticketId).addValue("by", salesRepId));
        long quotationId = insertAcceptedQuotation(ticketId, "QT-FIN-0003", new BigDecimal("1000.00"));
        insertQuotationItem(quotationId, 1, "สินค้า", BigDecimal.ONE, "ชุด",
            new BigDecimal("1000.00"), new BigDecimal("1000.00"));

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        String json = mapper.writeValueAsString(service.get(ticketId, accountUser));
        JsonNode tree = mapper.readTree(json);

        assertThat(tree.size()).isGreaterThan(0);
        assertThat(forbiddenKeysIn(tree)).as("forbidden keys in the finance payload").isEmpty();
        // And the seeded secret VALUES never travel either.
        assertThat(json).doesNotContain("ผู้ออกแบบลับ").doesNotContain("เจ้าของลับ").doesNotContain("ผู้ซื้อลับ");
    }


    // ── A4 (owner ruling): lost/cancelled deals with money still pending STAY visible ───────────

    @Test
    void lostDealWithAPendingPayment_staysVisibleToAccount_inTheFinanceViewToo() {
        long ticketId = createTicket(DealStage.PROCUREMENT);
        tickets.updatePaymentStatusUnchecked(ticketId, "AWAITING_FINAL_PAYMENT");
        tickets.updateLifecycle(ticketId, DealLifecycle.CLOSED_LOST);

        assertThat(ticketService.listPage(null, accountUser, th.co.glr.hr.common.PageRequest.resolve(0, 200)).items())
            .extracting(th.co.glr.hr.ticket.TicketSummaryDto::id).contains(ticketId);
        FinanceDealDto dto = service.get(ticketId, accountUser);
        assertThat(dto).isNotNull();
        assertThat(dto.id()).isEqualTo(ticketId);
    }

    @Test
    void money_carriesNoBillingColumns_andTheDerivedDueFieldsReplaceTheStoredDueDate() throws Exception {
        long ticketId = createS15Deal("AWAITING_FINAL_PAYMENT");
        tickets.updateBilling(ticketId, LocalDate.now().minusDays(3), LocalDate.now().plusDays(9), 30, null, null);

        com.fasterxml.jackson.databind.JsonNode money = json.readTree(
            new com.fasterxml.jackson.databind.ObjectMapper().registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule())
                .writeValueAsString(service.get(ticketId, accountUser))).get("money");
        for (String gone : List.of("billingDate", "creditTermDays", "lastFollowUpAt", "nextFollowUpAt", "dueDate")) {
            assertThat(money.has(gone)).as(gone).isFalse();
        }
        assertThat(money.has("paymentDueDate")).isTrue();
        assertThat(money.has("paymentDueBasis")).isTrue();
    }

    @Test
    void cancelledDealWithAnOverdueBalance_staysVisibleToAccount() {
        long ticketId = createTicket(DealStage.PROCUREMENT);
        insertAcceptedQuotation(ticketId, "QT-OD-" + ticketId, new BigDecimal("5000.00"));
        // Overdue by the quotation's terms (owner ruling 2026-09-30): credit 30 days, fully delivered 45 days ago.
        jdbc.update("UPDATE sales.quotation SET remainder_mode = 'CREDIT', credit_days = 30 WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId));
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = 'FULLY_DELIVERED' WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId));
        jdbc.update("""
            INSERT INTO sales.delivery_record (ticket_id, source, delivered_at, delivered_by)
            VALUES (:t, 'WAREHOUSE', now() - interval '45 days', :by)
            """, new MapSqlParameterSource().addValue("t", ticketId).addValue("by", salesRepId));
        tickets.updateLifecycle(ticketId, DealLifecycle.CANCELLED);

        assertThat(ticketService.listPage(null, accountUser, th.co.glr.hr.common.PageRequest.resolve(0, 200)).items())
            .extracting(th.co.glr.hr.ticket.TicketSummaryDto::id).contains(ticketId);
        assertThat(service.get(ticketId, accountUser)).isNotNull();
    }

    @Test
    void lostDealWithNoMoneyPending_isNotVisibleToAccount() {
        long ticketId = createTicket(DealStage.PROCUREMENT);
        tickets.updateLifecycle(ticketId, DealLifecycle.CLOSED_LOST);

        assertThat(ticketService.listPage(null, accountUser, th.co.glr.hr.common.PageRequest.resolve(0, 200)).items())
            .extracting(th.co.glr.hr.ticket.TicketSummaryDto::id).doesNotContain(ticketId);
        assertThatThrownBy(() -> service.get(ticketId, accountUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    // ── A2: the item unit for a quotation on the rows a hand fixture would not have filled ─────

    @Test
    void itemUnit_prefersTheHumanRawUnit_overTheCanonicalBasisCode() {
        long ticketId = createS15Deal("DEPOSIT_PAID");
        long quotationId = quotationIdOf(ticketId);
        jdbc.update("""
            INSERT INTO sales.quotation_item (quotation_id, seq, description, qty, raw_unit, unit_price, amount,
                   requested_unit_basis, requested_quantity, final_unit_price, line_subtotal)
            VALUES (:q, 1, 'กระเบื้อง', 10, 'ตร.ม.', 100.00, 1000.00, 'PER_SQM', 10, 100.00, 1000.00)
            """, new MapSqlParameterSource("q", quotationId));

        FinanceDealDto.Item item = service.get(ticketId, accountUser).items().get(0);

        assertThat(item.unit()).as("the human unit, not the canonical code PER_SQM").isEqualTo("ตร.ม.");
    }

    // ── A6: nothing from ANOTHER deal leaks in; only live billing notes; ACCEPTED beats ISSUED ─

    @Test
    void documentsOfAnotherDeal_doNotLeakIn() {
        long mine = createS15Deal("DEPOSIT_PAID");
        long other = createS15Deal("DEPOSIT_PAID");
        long depositOnOther = depositNotices.createDraft(other,
            new DepositNoticeDraftRequest("x", null, null, null, "R", new BigDecimal("0.50"), List.of(), null),
            List.of(new DepositNoticeItemRequest(1, "สินค้า", BigDecimal.ONE, "ชุด",
                new BigDecimal("100.00"), null, new BigDecimal("100.00"))));
        depositNotices.issue(depositOnOther, salesRepId, "x");
        long invoiceRi = remainingInvoices.insertDraft(other, null, depositOnOther, "R2", null, LocalDate.now(),
            List.of(), "x", null, null, null, null, new BigDecimal("100"), new BigDecimal("50"),
            new BigDecimal("50"), new BigDecimal("3.5"), new BigDecimal("53.5"), salesRepId, "x");
        remainingInvoices.issue(invoiceRi, "RI-OTHER", 1, "RI-OTHER", salesRepId, "x");
        attach(other, "other-po.pdf", AttachType.PO);
        insertIssuedBillingNote(other, "BN-OTHER-1", new BigDecimal("999.00"), "ISSUED");

        FinanceDealDto.Documents docs = service.get(mine, accountUser).documents();

        assertThat(docs.depositNotices()).isEmpty();
        assertThat(docs.remainingInvoices()).isEmpty();
        assertThat(docs.purchaseOrders()).isEmpty();
        assertThat(docs.billingNotes()).isEmpty();
    }

    @Test
    void billingNotes_draftCancelledAndSupersededAreExcluded_issuedAndSettledAreListed() {
        long ticketId = createS15Deal("DEPOSIT_PAID");
        insertIssuedBillingNote(ticketId, "BN-DR", new BigDecimal("1.00"), "DRAFT");
        insertIssuedBillingNote(ticketId, "BN-CA", new BigDecimal("2.00"), "CANCELLED");
        insertIssuedBillingNote(ticketId, "BN-SU", new BigDecimal("3.00"), "SUPERSEDED");
        long issued = insertIssuedBillingNote(ticketId, "BN-IS", new BigDecimal("4.00"), "ISSUED");
        long settled = insertIssuedBillingNote(ticketId, "BN-SE", new BigDecimal("5.00"), "SETTLED");

        assertThat(service.get(ticketId, accountUser).documents().billingNotes())
            .extracting(FinanceDealDto.BillingNoteDoc::id).containsExactlyInAnyOrder(issued, settled);
    }

    @Test
    void anAcceptedQuotationWins_overALaterIssuedOne() {
        long ticketId = createTicket(DealStage.PROCUREMENT);
        insertAcceptedQuotation(ticketId, "QT-ACC", new BigDecimal("100.00"));
        long issued = tickets.createQuotation(ticketId, "QT-ISS", salesRepId, new BigDecimal("999.00")).id();
        tickets.markQuotationStatus(ticketId, issued, "ISSUED");

        assertThat(service.get(ticketId, accountUser).documents().acceptedQuotation().number()).isEqualTo("QT-ACC");
    }

    @Test
    void vatBasisIsExplicitOnEveryAmount() {
        long ticketId = createS15Deal("DEPOSIT_PAID");
        long quotationId = quotationIdOf(ticketId);
        insertQuotationItem(quotationId, 1, "สินค้า", BigDecimal.ONE, "ชุด", new BigDecimal("100.00"), new BigDecimal("100.00"));
        long depositId = depositNotices.createDraft(ticketId,
            new DepositNoticeDraftRequest("x", null, null, null, "R", new BigDecimal("0.50"), List.of(), null),
            List.of(new DepositNoticeItemRequest(1, "สินค้า", BigDecimal.ONE, "ชุด",
                new BigDecimal("100.00"), null, new BigDecimal("100.00"))));
        depositNotices.issue(depositId, salesRepId, "x");
        long riId = remainingInvoices.insertDraft(ticketId, null, depositId, "R", null, LocalDate.now(), List.of(),
            "x", null, null, null, null, new BigDecimal("100"), new BigDecimal("50"), new BigDecimal("50"),
            new BigDecimal("3.5"), new BigDecimal("53.5"), salesRepId, "x");
        remainingInvoices.issue(riId, "RI-V", 1, "RI-V", salesRepId, "x");
        insertIssuedBillingNote(ticketId, "BN-V", new BigDecimal("53.50"), "ISSUED");

        FinanceDealDto dto = service.get(ticketId, accountUser);

        assertThat(dto.money().amountVatBasis()).isEqualTo(FinanceDealDto.INCLUDING_VAT);
        assertThat(dto.items().get(0).vatBasis()).isEqualTo(FinanceDealDto.EXCLUDING_VAT);
        assertThat(dto.documents().acceptedQuotation().vatBasis()).isEqualTo(FinanceDealDto.EXCLUDING_VAT);
        assertThat(dto.documents().depositNotices().get(0).totalPayableVatBasis()).isEqualTo(FinanceDealDto.INCLUDING_VAT);
        assertThat(dto.documents().remainingInvoices().get(0).grandTotalVatBasis()).isEqualTo(FinanceDealDto.INCLUDING_VAT);
        assertThat(dto.documents().billingNotes().get(0).amountVatBasis()).isEqualTo(FinanceDealDto.INCLUDING_VAT);
    }

    // ── #6: the accepted-quotation link must open the document the CUSTOMER received ───────────

    @Test
    void acceptedQuotationDownloadPath_followsTheQuotationsRealOrigin() {
        // DEAL_DIRECT and PRICING_REQUEST -> the deal-quotation PDF route
        for (String origin : List.of("DEAL_DIRECT", "PRICING_REQUEST")) {
            long id = createS15Deal("DEPOSIT_PAID");
            long q = quotationIdOf(id);
            jdbc.update("UPDATE sales.quotation SET origin = :o WHERE quotation_id = :q",
                new MapSqlParameterSource("o", origin).addValue("q", q));
            assertThat(service.get(id, accountUser).documents().acceptedQuotation().downloadPath())
                .as(origin).isEqualTo("/api/deal-quotations/" + q + "/file?format=pdf");
        }
        // origin NULL + a pricing request = the PCR-generated CustomerQuotationService chain
        long pcr = createS15Deal("DEPOSIT_PAID");
        long pcrQ = quotationIdOf(pcr);
        long prId = jdbc.queryForObject("""
            INSERT INTO sales.pricing_request (request_code, ticket_id, recipient_type, status, requested_by)
            VALUES (:c, :t, 'DESIGNER', 'QUOTATION_ACCEPTED', :by) RETURNING pricing_request_id
            """, new MapSqlParameterSource("c", "PCR-PATH-" + pcr).addValue("t", pcr).addValue("by", salesRepId), Long.class);
        jdbc.update("UPDATE sales.quotation SET pricing_request_id = :p WHERE quotation_id = :q",
            new MapSqlParameterSource("p", prId).addValue("q", pcrQ));
        assertThat(service.get(pcr, accountUser).documents().acceptedQuotation().downloadPath())
            .isEqualTo("/api/customer-quotations/" + pcrQ + "/file?format=pdf");
        // origin NULL and NO pricing request = a genuine legacy ticket-native row
        long legacy = createS15Deal("DEPOSIT_PAID");
        long legacyQ = quotationIdOf(legacy);
        assertThat(service.get(legacy, accountUser).documents().acceptedQuotation().downloadPath())
            .isEqualTo("/api/tickets/" + legacy + "/quotations/" + legacyQ + "/file?format=pdf");
    }

    // ── B2: comments (COMMENT events only, every author) ────────────────────────────────────────

    @Test
    void comments_includeEveryAuthorsComment_butNoOtherEventKind() {
        long ticketId = createS15Deal("DEPOSIT_PAID");
        ticketService.comment(ticketId, new th.co.glr.hr.ticket.CommentRequest("ความเห็นจากฝ่ายขาย"), salesRep);
        ticketService.comment(ticketId, new th.co.glr.hr.ticket.CommentRequest("ความเห็นจากผู้บริหาร"), ceoUser);
        // a NON-comment event (a stage change) that must not appear
        tickets.addEvent(ticketId, salesRepId, "พนักงานขาย ทดสอบ",
            th.co.glr.hr.ticket.TicketEventKind.STAGE_CHANGED, "ORDER_RECEIVED", "PROCUREMENT", "ห้ามรั่ว");

        FinanceDealDto dto = service.get(ticketId, accountUser);

        assertThat(dto.comments()).extracting(FinanceDealDto.Comment::message)
            .containsExactly("ความเห็นจากฝ่ายขาย", "ความเห็นจากผู้บริหาร");
        assertThat(dto.comments()).extracting(FinanceDealDto.Comment::authorName).doesNotContainNull();
        assertThat(dto.comments()).allSatisfy(c -> assertThat(c.createdAt()).isNotNull());
    }

    // ── B2: availableActions (same gates as the ticket actions, money actions only) ─────────────

    @Test
    void availableActions_forAccountOnAnS15DepositNoticeIssuedDeal_areTheMoneyActionsOnly() {
        long ticketId = createS15Deal("DEPOSIT_NOTICE_ISSUED");

        List<String> account = service.get(ticketId, accountUser).availableActions().stream()
            .map(FinanceDealDto.Action::action).toList();
        List<String> ceo = service.get(ticketId, ceoUser).availableActions().stream()
            .map(FinanceDealDto.Action::action).toList();

        assertThat(account).contains("DEPOSIT_PAID", "RECORD_PAYMENT").doesNotContain("SET_BILLING");
        // Billing is out of scope for the finance view (owner ruling 2026-09-30); ceo never records money (GLA-118).
        assertThat(ceo).doesNotContain("SET_BILLING", "DEPOSIT_PAID", "RECORD_PAYMENT", "FINAL_PAYMENT");
        java.util.Set<String> money = java.util.Set.of("DEPOSIT_PAID", "RECORD_PAYMENT",
            "FINAL_PAYMENT", "CONFIRM_CLOSE", "REVOKE_CLOSE_CONFIRM", "ADVANCE_STAGE", "UPDATE_STAGE");
        assertThat(account).isSubsetOf(money);
        assertThat(ceo).isSubsetOf(money);
    }

    @Test
    void availableActions_advanceStageIsOnlyEverAMoneyStage() {
        long ticketId = createS15Deal("DEPOSIT_PAID");
        jdbc.update("UPDATE sales.ticket SET sales_stage = 'ORDER_RECEIVED', next_follow_up_at = CURRENT_DATE + 3 WHERE ticket_id = :id",
            new MapSqlParameterSource("id", ticketId));
        ticketService.addActivity(ticketId,
            new th.co.glr.hr.ticket.DealActivityRequest(LocalDate.now(), th.co.glr.hr.ticket.DealActivityKind.CALL, null), salesRep);

        for (UserPrincipal actor : List.of(accountUser, ceoUser)) {
            assertThat(service.get(ticketId, actor).availableActions())
                .filteredOn(a -> "ADVANCE_STAGE".equals(a.action()))
                .extracting(FinanceDealDto.Action::targetStage)
                .allMatch(stage -> DealStage.DEPOSIT_RECEIVED.equals(stage) || DealStage.CLOSED_PAID.equals(stage));
        }
        assertThat(service.get(ticketId, accountUser).availableActions())
            .extracting(FinanceDealDto.Action::targetStage).contains(DealStage.DEPOSIT_RECEIVED);
    }


    private long quotationIdOf(long ticketId) {
        return jdbc.queryForObject("SELECT quotation_id FROM sales.quotation WHERE ticket_id = :id ORDER BY quotation_id LIMIT 1",
            new MapSqlParameterSource("id", ticketId), Long.class);
    }

    private long insertIssuedBillingNote(long ticketId, String docNumber, BigDecimal amount, String status) {
        long customerId = jdbc.queryForObject(
            "INSERT INTO customers.customer (name) VALUES ('ลูกค้า ใบวางบิล ' || :n) RETURNING customer_id",
            new MapSqlParameterSource("n", docNumber), Long.class);
        boolean draft = "DRAFT".equals(status);
        boolean cancelled = "CANCELLED".equals(status);
        long noteId = jdbc.queryForObject("""
            INSERT INTO sales.billing_note (customer_id, type, base_number, version, doc_number, status,
                   bill_date, total_amount, created_by_id, created_by_name, issued_by_id, issued_by_name, issued_at,
                   cancel_reason, cancelled_by_id, cancelled_at)
            VALUES (:c, 'GOODS', :base, 1, :doc, :status, CURRENT_DATE, :amt, :by, 'x', :by, 'x', :issuedAt,
                   :cr, :cb, :ca)
            RETURNING id
            """, new MapSqlParameterSource().addValue("c", customerId)
            .addValue("cr", cancelled ? "ทดสอบ" : null).addValue("cb", cancelled ? salesRepId : null, java.sql.Types.BIGINT)
            .addValue("ca", cancelled ? java.sql.Timestamp.from(java.time.Instant.now()) : null, java.sql.Types.TIMESTAMP)
            .addValue("base", draft ? null : docNumber).addValue("doc", draft ? null : docNumber)
            .addValue("status", status).addValue("amt", amount).addValue("by", salesRepId)
            .addValue("issuedAt", draft ? null : java.sql.Timestamp.from(java.time.Instant.now()),
                java.sql.Types.TIMESTAMP), Long.class);
        jdbc.update("""
            INSERT INTO sales.billing_note_line (billing_note_id, seq, source_type, ticket_id, doc_number, doc_date,
                   amount, note_status)
            VALUES (:n, 1, 'MANUAL', :t, :num, CURRENT_DATE, :amt, :ns)
            """, new MapSqlParameterSource().addValue("n", noteId).addValue("t", ticketId)
            .addValue("num", docNumber).addValue("amt", amount)
            .addValue("ns", draft ? "DRAFT" : ("ISSUED".equals(status) || "SETTLED".equals(status) ? "ISSUED" : "RELEASED")));
        return noteId;
    }
}
