package th.co.glr.hr.finance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.AttachmentDto;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.commission.CommissionAttachmentRepository;
import th.co.glr.hr.commission.CommissionCalculator;
import th.co.glr.hr.commission.CommissionRecord;
import th.co.glr.hr.commission.CommissionRepository;
import th.co.glr.hr.commission.CommissionService;
import th.co.glr.hr.commission.CommissionStatus;
import th.co.glr.hr.commission.ReviewCommissionRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.ticket.AttachType;
import th.co.glr.hr.ticket.DealStage;

/**
 * The finance page records the commission invoice through the EXISTING
 * {@code CommissionService#createFromDeal} (no new write endpoint) and reads it back on the finance
 * deal DTO. Real Postgres, real services and repositories, real controller behind MockMvc; only the
 * outbound email transport is a Mockito mock (delivery is not covered -- "queued" is).
 *
 * <p>The finance payload must carry the invoice and its approval status and NEVER a commission
 * amount, weight, tier, payroll month or rep.
 */
class FinanceDealCommissionInvoiceIntegrationTest extends FinanceDealTestBase {
    private static final LocalDate INVOICE_DATE = LocalDate.of(2026, 6, 15); // -> payroll month 2026-07-01 (open)

    private static final Set<String> COMMISSION_INVOICE_KEYS = Set.of(
        "invoiceNumber", "invoiceDate", "grossAmount", "bankFees", "suspenseVat", "transportFee", "cutFee",
        "shortfall", "withholdingTax", "overpayment", "fileName", "downloadPath", "approvalStatus",
        "rejectionReason", "recordedAt");

    /** Keys that would leak commission money / weighting / attribution into the finance payload. */
    private static final List<String> COMMISSION_LEAK_FRAGMENTS = List.of(
        "commissionable", "actualreceived", "commissionamount", "estimatedcommission", "effective",
        "multiplier", "weight", "tier", "incentive", "payrollmonth", "salesrep");

    private CommissionRepository commissions;
    private CommissionService commissionService;
    private NotificationEmailService emailService;
    private long managerEmployeeId;
    private UserPrincipal managerUser;

    @BeforeEach
    void wireCommission() {
        commissions = new CommissionRepository(jdbc);
        emailService = mock(NotificationEmailService.class);
        NotificationService notificationService = new NotificationService(
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), emailService);
        commissionService = new CommissionService(
            commissions,
            new CommissionAttachmentRepository(jdbc),
            new CommissionCalculator(),
            new FileStorageService("/tmp/glr-finance-commission-invoice-test-uploads"),
            mock(AuditService.class),
            notificationService,
            tickets,
            attachments,
            new CeoApproverRepository(jdbc));

        // A real SA-division manager, so findSalesManagerApproverEmployeeIds() has someone to notify.
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        managerEmployeeId = employees.create(new UpsertEmployeeRequest(
            null, null, "ผู้จัดการฝ่ายขาย ใบกำกับ", null, null, null, null, null, null, null,
            "fin-inv-mgr@glr.co.th", null, "SA", "SA", "SA",
            "ผู้จัดการฝ่ายขาย", null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        managerUser = principal(managerEmployeeId, "sales_manager");
        assertThat(commissions.findSalesManagerApproverEmployeeIds()).contains(managerEmployeeId);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void accountRecordsInvoice_viaFromDeal_createsSubmittedCommission_andQueuesManagerEmail() {
        long ticketId = closedPaidDeal();

        CommissionRecord created = recordInvoice(ticketId, "INV-FIN-0001");

        assertThat(created.status()).isEqualTo(CommissionStatus.SUBMITTED);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM sales.commission_record WHERE commission_id = :id",
            Map.of("id", created.id()), String.class)).isEqualTo("SUBMITTED");

        List<String> links = jdbc.queryForList("""
            SELECT link FROM hr.notification
             WHERE employee_id = :e AND type = 'COMMISSION_PENDING_MANAGER'
            """, Map.of("e", managerEmployeeId), String.class);
        assertThat(links).containsExactly("/commissions?view=pending");
        // The rep's own "submitted" ping keeps pointing at the list.
        assertThat(jdbc.queryForList("""
            SELECT link FROM hr.notification WHERE employee_id = :e AND type = 'COMMISSION_SUBMITTED'
            """, Map.of("e", salesRepId), String.class)).containsExactly("/commissions");

        // Email queued for that manager (delivery itself is not covered here).
        verify(emailService).send(eq(managerEmployeeId), any(), any(), any(), any(), eq("/commissions?view=pending"));
    }

    @Test
    void financeDto_beforeRecording_offersRecordInvoiceToAccount_notCeo() {
        long ticketId = closedPaidDeal();

        FinanceDealDto asAccount = service.get(ticketId, accountUser);
        assertThat(asAccount.availableActions())
            .filteredOn(a -> "RECORD_INVOICE".equals(a.action()))
            .singleElement()
            .satisfies(a -> assertThat(a.label()).isEqualTo("บันทึกใบกำกับ"));

        // createFromDeal is account-only: the finance view never offers the action to ceo.
        FinanceDealDto asCeo = service.get(ticketId, ceoUser);
        assertThat(asCeo.availableActions()).extracting(FinanceDealDto.Action::action).doesNotContain("RECORD_INVOICE");

        // ...nor to account on a deal that is not CLOSED_PAID yet.
        long earlyDeal = createS15Deal(null);
        assertThat(service.get(earlyDeal, accountUser).availableActions())
            .extracting(FinanceDealDto.Action::action).doesNotContain("RECORD_INVOICE");

        FinanceDealDto.Money money = asAccount.money();
        assertThat(money.amountPayableExVat()).isNotNull();
        assertThat(money.amountPayableExVat()).isEqualByComparingTo(tickets.payableAmountExVat(ticketId));
        assertThat(money.amountPayableExVat()).isEqualByComparingTo("100000.00");
        assertThat(money.amountPayable()).as("VAT applies, so the inclusive payable differs")
            .isNotEqualByComparingTo(money.amountPayableExVat());
        assertThat(asAccount.commissionInvoice()).isNull();
    }

    @Test
    void financeDto_afterRecording_carriesInvoiceDetails_andExactKeySet() throws Exception {
        long ticketId = closedPaidDeal();
        CommissionRecord created = commissionService.createFromDeal(
            ticketId, "INV-FIN-0002", INVOICE_DATE, new BigDecimal("100000.00"),
            new BigDecimal("50.00"), new BigDecimal("70.00"), new BigDecimal("300.00"), new BigDecimal("20.00"),
            new BigDecimal("10.00"), new BigDecimal("1000.00"), new BigDecimal("5.00"), invoiceFile(), accountUser);
        long invoiceAttachmentId = attachments.findByTicketId(ticketId).stream()
            .filter(a -> AttachType.INVOICE.equals(a.attachType())).map(AttachmentDto::id).findFirst().orElseThrow();
        String invoiceFileName = attachments.findById(invoiceAttachmentId).orElseThrow().fileName();

        JsonNode deal = getDealJson(ticketId, accountUser);
        JsonNode invoice = deal.get("commissionInvoice");

        assertThat(invoice).as("deal.commissionInvoice").isNotNull();
        assertThat(invoice.isObject()).isTrue();
        List<String> keys = new ArrayList<>();
        invoice.fieldNames().forEachRemaining(keys::add);
        assertThat(new TreeSet<>(keys)).as("EXACT key set").isEqualTo(new TreeSet<>(COMMISSION_INVOICE_KEYS));
        assertThat(keys).hasSize(15);

        assertThat(invoice.get("invoiceNumber").asText()).isEqualTo("INV-FIN-0002");
        assertThat(invoice.get("invoiceDate").asText()).isEqualTo("2026-06-15");
        assertDecimal(invoice, "grossAmount", "100000.00");
        assertDecimal(invoice, "bankFees", "50.00");
        assertDecimal(invoice, "suspenseVat", "70.00");
        assertDecimal(invoice, "transportFee", "300.00");
        assertDecimal(invoice, "cutFee", "20.00");
        assertDecimal(invoice, "shortfall", "10.00");
        assertDecimal(invoice, "withholdingTax", "1000.00");
        assertDecimal(invoice, "overpayment", "5.00");
        assertThat(invoice.get("fileName").asText()).isEqualTo(invoiceFileName);
        assertThat(invoice.get("downloadPath").asText()).isEqualTo("/api/attachments/" + invoiceAttachmentId + "/file");
        assertThat(invoice.get("approvalStatus").asText()).isEqualTo("SUBMITTED");
        assertThat(invoice.get("rejectionReason").isNull()).isTrue();
        assertThat(invoice.get("recordedAt").isNull()).isFalse();
        assertThat(invoice.get("recordedAt").asText()).isNotBlank();

        assertThat(deal.get("money").get("commissionRecorded").asBoolean()).isTrue();
        List<String> actions = new ArrayList<>();
        deal.get("availableActions").forEach(a -> actions.add(a.get("action").asText()));
        assertThat(actions).doesNotContain("RECORD_INVOICE");
        assertThat(created.status()).isEqualTo(CommissionStatus.SUBMITTED);
    }

    @Test
    void financeDto_neverLeaksCommissionAmountOrWeightKeys() throws Exception {
        long ticketId = closedPaidDeal();
        recordInvoice(ticketId, "INV-FIN-0003");

        for (UserPrincipal caller : List.of(accountUser, ceoUser)) {
            JsonNode tree = getDealJson(ticketId, caller);
            assertThat(tree.path("commissionInvoice").isObject()).as("fixture: the invoice section is present").isTrue();
            assertThat(forbiddenKeysIn(tree)).as("shared forbidden keys for %s", caller.role()).isEmpty();

            List<String> keys = new ArrayList<>();
            collectKeys(tree, keys);
            for (String key : keys) {
                String lower = key.toLowerCase(Locale.ROOT);
                for (String fragment : COMMISSION_LEAK_FRAGMENTS) {
                    assertThat(lower).as("key '%s' must not contain '%s'", key, fragment).doesNotContain(fragment);
                }
            }
        }
    }

    @Test
    void financeDto_statusLine_tracksSubmittedManagerApprovedApproved() {
        long ticketId = closedPaidDeal();
        CommissionRecord created = recordInvoice(ticketId, "INV-FIN-0004");
        assertThat(invoiceStatus(ticketId)).isEqualTo("SUBMITTED");

        commissionService.approve(created.id(), managerUser);
        assertThat(invoiceStatus(ticketId)).isEqualTo("MANAGER_APPROVED");

        commissionService.approve(created.id(), ceoUser);
        assertThat(invoiceStatus(ticketId)).isEqualTo("APPROVED");
        // Still no re-record offer once approved, and the reason stays absent.
        FinanceDealDto approved = service.get(ticketId, accountUser);
        assertThat(approved.commissionInvoice().rejectionReason()).isNull();
        assertThat(approved.availableActions()).extracting(FinanceDealDto.Action::action)
            .doesNotContain("RECORD_INVOICE");
    }

    @Test
    void financeDto_rejected_carriesReason() {
        long ticketId = closedPaidDeal();
        CommissionRecord created = recordInvoice(ticketId, "INV-FIN-0005");

        commissionService.reject(created.id(), new ReviewCommissionRequest("หลักฐานใบกำกับไม่ครบ"), managerUser);

        FinanceDealDto dto = service.get(ticketId, accountUser);
        assertThat(dto.commissionInvoice()).isNotNull();
        assertThat(dto.commissionInvoice().approvalStatus()).isEqualTo("REJECTED");
        assertThat(dto.commissionInvoice().rejectionReason()).isEqualTo("หลักฐานใบกำกับไม่ครบ");
        // No ACTIVE commission any more, so the account can record again.
        assertThat(dto.money().commissionRecorded()).isFalse();
        assertThat(dto.availableActions()).filteredOn(a -> "RECORD_INVOICE".equals(a.action())).hasSize(1);
        // ...and ceo still cannot.
        assertThat(service.get(ticketId, ceoUser).availableActions()).extracting(FinanceDealDto.Action::action)
            .doesNotContain("RECORD_INVOICE");
    }

    // ── fixtures ────────────────────────────────────────────────────────────────────────────

    /** CLOSED_PAID (S20, inside account's scope) with an accepted 100,000.00 pre-VAT quotation. */
    private long closedPaidDeal() {
        long ticketId = createTicket(DealStage.CLOSED_PAID);
        insertAcceptedQuotation(ticketId, "QT-FCI-" + ticketId, new BigDecimal("100000.00"));
        return ticketId;
    }

    private CommissionRecord recordInvoice(long ticketId, String invoiceNumber) {
        return commissionService.createFromDeal(
            ticketId, invoiceNumber, INVOICE_DATE, new BigDecimal("100000.00"),
            null, null, null, null, null, null, null, invoiceFile(), accountUser);
    }

    private static MockMultipartFile invoiceFile() {
        return new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes());
    }

    private String invoiceStatus(long ticketId) {
        FinanceDealDto.CommissionInvoice invoice = service.get(ticketId, accountUser).commissionInvoice();
        assertThat(invoice).isNotNull();
        return invoice.approvalStatus();
    }

    private JsonNode getDealJson(long ticketId, UserPrincipal actor) throws Exception {
        String body = financeMvc.perform(get("/api/finance/deals/{id}", ticketId).session(session(actor)))
            .andReturn().getResponse().getContentAsString();
        JsonNode root = json.readTree(body);
        assertThat(root.has("deal")).as("response body: %s", body).isTrue();
        return root.get("deal");
    }

    private static void assertDecimal(JsonNode node, String field, String expected) {
        assertThat(node.has(field)).as(field).isTrue();
        assertThat(node.get(field).decimalValue()).as(field).isEqualByComparingTo(expected);
    }
}
