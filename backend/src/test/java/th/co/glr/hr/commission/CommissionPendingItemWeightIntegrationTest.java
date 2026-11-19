package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.ItemWeightMultiplierRequest;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;

/** The sales-manager "รออนุมัติ" view (CommissionService#listPendingApproval) and the per-item weight
 * adjustment (CommissionService#adjustItemWeights), against real Postgres through the real service and
 * repositories. */
class CommissionPendingItemWeightIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate JUNE_INVOICE = LocalDate.of(2026, 6, 15);   // -> payroll month 2026-07-01
    private TicketRepository tickets;
    private CommissionRepository commissions;
    private CommissionCalculator calculator;
    private CommissionService commissionService;

    private long repId;
    private UserPrincipal repActor;
    private UserPrincipal managerActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal importActor;
    private UserPrincipal hrActor;
    private UserPrincipal employeeActor;

    @BeforeEach
    void wire() {
        tickets = new TicketRepository(jdbc);
        commissions = new CommissionRepository(jdbc);
        calculator = new CommissionCalculator();
        commissionService = new CommissionService(
            commissions,
            new CommissionAttachmentRepository(jdbc),
            calculator,
            new FileStorageService("/tmp/glr-pending-weight-test-uploads"),
            mock(AuditService.class),
            mock(NotificationService.class),
            tickets,
            new AttachmentRepository(jdbc),
            new CeoApproverRepository(jdbc));

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        repId = createEmployee(employees, "พนักงานขาย รออนุมัติ", "pw-rep@glr.co.th", "SL", "เจ้าหน้าที่");
        repActor = principal(repId, "sales");
        managerActor = principal(
            createEmployee(employees, "ผู้จัดการฝ่ายขาย รออนุมัติ", "pw-mgr@glr.co.th", "SA", "ผู้จัดการฝ่ายขาย"),
            "sales_manager");
        ceoActor = principal(createEmployee(employees, "ซีอีโอ รออนุมัติ", "pw-ceo@glr.co.th", "MD", "กรรมการผู้จัดการ"), "ceo");
        accountActor = principal(createEmployee(employees, "บัญชี รออนุมัติ", "pw-acct@glr.co.th", "ACCT", "เจ้าหน้าที่"), "account");
        importActor = principal(createEmployee(employees, "นำเข้า รออนุมัติ", "pw-imp@glr.co.th", "PCIM", "เจ้าหน้าที่"), "import");
        hrActor = principal(createEmployee(employees, "บุคคล รออนุมัติ", "pw-hr@glr.co.th", "HR", "เจ้าหน้าที่"), "hr");
        employeeActor = principal(createEmployee(employees, "พนักงาน รออนุมัติ", "pw-emp@glr.co.th", "OP", "เจ้าหน้าที่"), "employee");
    }

    // GET pending-approval

    @Test
    void pendingList_refusedFor_sales_account_import_hr() {
        createRecord(dealWithItems(itemSpec(10, 4, 1000, 2)), JUNE_INVOICE, "300000.00");

        for (UserPrincipal caller : List.of(repActor, accountActor, importActor, hrActor, employeeActor)) {
            assertThatThrownBy(() -> commissionService.listPendingApproval(caller))
                .as("role %s must be refused", caller.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        }
    }

    // POST {id}/item-weights

    @Test
    void managerAdjust_recomputesEffectiveWeight_exactlyAsCalculatorItemDerivedWeight() {
        long ticketId = dealWithItems(itemSpec(10, 4, 1000, 2), itemSpec(5, 5, 2000, 1));
        long commissionId = createRecord(ticketId, JUNE_INVOICE, "300000.00");
        List<TicketItemDto> before = tickets.findById(ticketId).orElseThrow().items();
        long itemB = before.get(1).id();
        BigDecimal effectiveBefore = effectiveWeightColumn(commissionId);
        assertThat(effectiveBefore).as("fixture: frozen non-null blend before adjusting").isNotNull();
        int recordLevelWeightBefore = commissions.findById(commissionId).orElseThrow().weightMultiplier();

        PendingCommissionDto result = commissionService.adjustItemWeights(
            commissionId, weights(new ItemWeightMultiplierRequest.Line(itemB, 3)), managerActor);

        List<TicketItemDto> after = tickets.findById(ticketId).orElseThrow().items();
        assertThat(after.get(0).weightMultiplier()).as("untouched line keeps its weight").isEqualTo(2);
        assertThat(after.get(1).weightMultiplier()).isEqualTo(3);

        CommissionRecord rec = commissions.findById(commissionId).orElseThrow();
        List<CommissionCalculator.ItemStockWeightInput> inputs = after.stream()
            .map(i -> new CommissionCalculator.ItemStockWeightInput(
                i.qty(), i.qtyFromStock(), i.approvedPrice(), i.proposedPrice(), i.weightMultiplier()))
            .toList();
        BigDecimal expected = new CommissionCalculator().itemDerivedWeight(inputs, rec.actualReceived()).orElseThrow();

        BigDecimal stored = effectiveWeightColumn(commissionId);
        assertThat(stored).isNotNull();
        assertThat(stored).isEqualByComparingTo(expected);
        assertThat(stored).as("recompute must actually move the weight").isNotEqualByComparingTo(effectiveBefore);
        assertThat(rec.weightMultiplier()).as("record-level weight_multiplier is not touched")
            .isEqualTo(recordLevelWeightBefore);
        assertThat(result.commission().id()).isEqualTo(commissionId);
        assertThat(result.effectiveWeight()).isEqualByComparingTo(expected);
        assertThat(result.items()).extracting(PendingCommissionItemDto::weightMultiplier).containsExactly(2, 3);
    }

    // fixtures

    /** qty / qty_from_stock / approved price / starting per-item weight multiplier. */
    private record ItemSpec(int qty, int qtyFromStock, int price, int weight) {}

    private static ItemSpec itemSpec(int qty, int qtyFromStock, int price, int weight) {
        return new ItemSpec(qty, qtyFromStock, price, weight);
    }

    /** A CLOSED_PAID deal owned by the rep, with priced, stock-declared, weighted items. */
    private long dealWithItems(ItemSpec... specs) {
        List<TicketItemRequest> requests = new ArrayList<>();
        int n = 0;
        for (ItemSpec spec : specs) {
            n++;
            requests.add(new TicketItemRequest("Brand" + n, "Model" + n, "สีขาว", null, "60x60", "Factory A",
                BigDecimal.valueOf(spec.qty()), null, "PIECE", null, null, null,
                BigDecimal.valueOf(spec.price()), "THB"));
        }
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีลรออนุมัติ", "NORMAL", "ลูกค้ารออนุมัติ", null, null, null, null, null, requests),
            tickets.nextTicketCode(), repId, "พนักงานขาย รออนุมัติ");
        tickets.updateSalesStage(ticketId, DealStage.CLOSED_PAID);
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        for (int i = 0; i < specs.length; i++) {
            jdbc.update("""
                UPDATE sales.ticket_item
                   SET approved_price = :price, qty_from_stock = :stock, weight_multiplier = :weight
                 WHERE item_id = :id
                """, new MapSqlParameterSource()
                    .addValue("price", BigDecimal.valueOf(specs[i].price()))
                    .addValue("stock", BigDecimal.valueOf(specs[i].qtyFromStock()))
                    .addValue("weight", specs[i].weight())
                    .addValue("id", items.get(i).id()));
        }
        return ticketId;
    }

    /** The production path: the accountant records the invoice, which freezes the blended weight. */
    private long createRecord(long ticketId, LocalDate invoiceDate, String gross) {
        CommissionRecord created = commissionService.createFromDeal(
            ticketId, "INV-PW-" + UUID.randomUUID().toString().substring(0, 8), invoiceDate,
            new BigDecimal(gross), null, null, null, null, null, null, null,
            new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes()),
            accountActor);
        assertThat(created.status()).isEqualTo(CommissionStatus.SUBMITTED);
        return created.id();
    }

    private static ItemWeightMultiplierRequest weights(ItemWeightMultiplierRequest.Line... lines) {
        return new ItemWeightMultiplierRequest(List.of(lines));
    }

    private BigDecimal effectiveWeightColumn(long commissionId) {
        return jdbc.queryForObject(
            "SELECT effective_weight_multiplier FROM sales.commission_record WHERE commission_id = :id",
            Map.of("id", commissionId), BigDecimal.class);
    }

    private long createEmployee(EmployeeRepository employees, String name, String email, String divisionCode,
                                String positionTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, divisionCode, divisionCode, divisionCode,
            positionTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-pw@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
