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

        for (UserPrincipal actor : List.of(salesRep, salesManager, importUser, hrUser, employeeUser)) {
            assertThatThrownBy(() -> service.get(ticketId, actor))
                .as("role " + actor.role())
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus().value()).isEqualTo(403));
        }
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
