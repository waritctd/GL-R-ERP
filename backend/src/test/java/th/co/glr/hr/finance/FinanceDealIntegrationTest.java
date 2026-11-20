package th.co.glr.hr.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.ticket.DealStage;

/** GET /api/finance/deals/{id} against the real service and real Postgres (H1, owner decision A2). Written
 * wrong-way-round: every role that must NOT reach a deal, and every key that must NOT appear in the payload,
 * is asserted before the happy path is. */
class FinanceDealIntegrationTest extends FinanceDealTestBase {

    // ── who may NOT reach it (wrong way round first) ────────────────────────────────────────

    @Test
    void everyRoleExceptAccountAndCeo_isForbidden_evenOnTheirOwnDeal() {
        long ticketId = createTicket(DealStage.PROCUREMENT); // salesRep owns it
        tickets.updatePaymentStatusUnchecked(ticketId, "DEPOSIT_PAID");

        for (UserPrincipal actor : List.of(salesRep, importUser, hrUser, employeeUser)) {
            assertThatThrownBy(() -> service.get(ticketId, actor))
                .as("role " + actor.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        }
    }

    /** v2.3.0 D6/D7: sales_manager (role) and a read-all holder read the finance deal; no actions, no commission data. */
    @Test
    void oversightReaders_readTheFinanceDeal_withNoActions_andFlaggedImportStaysRefused() {
        long ticketId = createS15Deal("DEPOSIT_NOTICE_ISSUED");
        for (UserPrincipal actor : List.of(salesManager, readAllHolder)) {
            var deal = service.get(ticketId, actor);
            assertThat(deal.id()).as(actor.role()).isEqualTo(ticketId);
            assertThat(deal.availableActions()).as(actor.role()).isEmpty();
            assertThat(deal.commissionInvoice()).isNull();
        }
        // D5: a DRAFT delivery round's file is sales' private work: hidden from a flag-only reader, as ever for a sales_manager.
        long draftRound = jdbc.queryForObject("""
            INSERT INTO sales.delivery_request (ticket_id, round_no, status, requested_by)
            VALUES (:t, 1, 'DRAFT', :u) RETURNING delivery_request_id
            """, new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("t", ticketId).addValue("u", salesRepId), Long.class);
        long draftFile = attachments.save(ticketId, null, "slip.pdf", "uploads/slip.pdf", "application/pdf", 10L, "OTHER",
            salesRepId, draftRound, "SALES").id();
        assertThat(service.get(ticketId, readAllHolder).documents().contracts())
            .extracting(FinanceDealDto.FileDoc::id).doesNotContain(draftFile);
        assertThat(service.get(ticketId, salesManager).documents().contracts())
            .extracting(FinanceDealDto.FileDoc::id).contains(draftFile);
        jdbc.update("UPDATE hr.employee SET can_read_all_sales = TRUE WHERE employee_id = :id",
            new org.springframework.jdbc.core.namedparam.MapSqlParameterSource("id", importUser.id()));
        assertThatThrownBy(() -> service.get(ticketId, importUser))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
    }

    // ── who may ─────────────────────────────────────────────────────────────────────────────

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
}
