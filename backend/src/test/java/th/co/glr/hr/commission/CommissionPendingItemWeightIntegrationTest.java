package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.web.multipart.MultipartFile;
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
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * The sales-manager "รออนุมัติ" view ({@link CommissionService#listPendingApproval}) and the
 * per-item weight adjustment ({@link CommissionService#adjustItemWeights}), against real Postgres
 * through the real service and repositories. Records are created the production way
 * ({@code createFromDeal} as the accountant), so {@code effective_weight_multiplier} is frozen by
 * the real freeze, and the estimate / recompute assertions are computed here from the REAL
 * {@link CommissionCalculator} -- this test never re-derives commission math by hand.
 *
 * <p>Every authz case is written wrong-way-round: the caller CANNOT, and nothing changed.
 */
class CommissionPendingItemWeightIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final LocalDate JUNE_INVOICE = LocalDate.of(2026, 6, 15);   // -> payroll month 2026-07-01
    private static final LocalDate JULY_INVOICE = LocalDate.of(2026, 7, 15);   // -> payroll month 2026-08-01

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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // GET pending-approval
    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void pendingList_managerSeesOnlySubmitted_acrossMonths() {
        // Six records for ONE rep across two payroll months. Item shapes differ so each frozen
        // effective weight differs (and the estimate is non-trivial: the rep's month totals add up).
        long r1 = createRecord(dealWithItems(itemSpec(10, 4, 1000, 2), itemSpec(5, 0, 2000, 1)), JUNE_INVOICE, "300000.00"); // Jul, stays SUBMITTED
        long r2 = createRecord(dealWithItems(itemSpec(8, 8, 1500, 3)), JUNE_INVOICE, "250000.00");                          // Jul -> MANAGER_APPROVED
        long r3 = createRecord(dealWithItems(itemSpec(4, 2, 5000, 2), itemSpec(6, 6, 700, 1)), JULY_INVOICE, "400000.00");  // Aug, stays SUBMITTED
        long r4 = createRecord(dealWithItems(itemSpec(3, 3, 9000, 2)), JULY_INVOICE, "280000.00");                          // Aug -> APPROVED
        long r5 = createRecord(dealWithItems(itemSpec(2, 1, 4000, 3)), JUNE_INVOICE, "150000.00");                          // Jul -> REJECTED
        long r6 = createRecord(dealWithItems(itemSpec(12, 6, 800, 2)), JUNE_INVOICE, "320000.00");                          // Jul, stays SUBMITTED

        commissionService.approve(r2, managerActor);
        commissionService.approve(r4, managerActor);
        commissionService.approve(r4, ceoActor);
        commissionService.reject(r5, new ReviewCommissionRequest("ตีกลับเพื่อทดสอบ"), managerActor);
        assertThat(commissions.findById(r2).orElseThrow().status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);
        assertThat(commissions.findById(r4).orElseThrow().status()).isEqualTo(CommissionStatus.APPROVED);
        assertThat(commissions.findById(r5).orElseThrow().status()).isEqualTo(CommissionStatus.REJECTED);

        List<PendingCommissionDto> pending = commissionService.listPendingApproval(managerActor);

        // Exactly the SUBMITTED ones, every payroll month, commission_id ASC.
        assertThat(pending).extracting(p -> p.commission().id()).containsExactly(r1, r3, r6);
        assertThat(pending).allSatisfy(p -> assertThat(p.commission().status()).isEqualTo(CommissionStatus.SUBMITTED));
        assertThat(pending).extracting(p -> p.commission().payrollMonth())
            .containsExactly(LocalDate.of(2026, 7, 1), LocalDate.of(2026, 8, 1), LocalDate.of(2026, 7, 1));
        // CEO may read the same list.
        assertThat(commissionService.listPendingApproval(ceoActor)).extracting(p -> p.commission().id())
            .containsExactly(r1, r3, r6);

        boolean sawNonZeroEstimate = false;
        for (PendingCommissionDto dto : pending) {
            CommissionRecord rec = commissions.findById(dto.commission().id()).orElseThrow();
            TicketDto ticket = tickets.findById(rec.sourceTicketId()).orElseThrow();

            assertThat(dto.ticketCode()).isEqualTo(ticket.summary().code());
            assertThat(dto.customerName()).isEqualTo(ticket.summary().customerName());

            List<PendingCommissionItemDto> expectedItems = ticket.items().stream()
                .map(i -> new PendingCommissionItemDto(i.id(), expectedDescription(i), i.qty(), i.qtyFromStock(),
                    i.weightMultiplier()))
                .toList();
            assertThat(dto.items()).hasSize(expectedItems.size());
            for (int i = 0; i < expectedItems.size(); i++) {
                PendingCommissionItemDto actual = dto.items().get(i);
                PendingCommissionItemDto expected = expectedItems.get(i);
                assertThat(actual.itemId()).isEqualTo(expected.itemId());
                assertThat(actual.description()).isEqualTo(expected.description());
                assertThat(actual.qty()).isEqualByComparingTo(expected.qty());
                assertThat(actual.qtyFromStock()).isEqualByComparingTo(expected.qtyFromStock());
                assertThat(actual.weightMultiplier()).isEqualTo(expected.weightMultiplier());
            }

            assertThat(rec.effectiveWeightMultiplier()).as("freeze produced a blended weight").isNotNull();
            assertThat(dto.effectiveWeight()).isEqualByComparingTo(rec.effectiveWeight());

            BigDecimal weightedReceived = rec.actualReceived().multiply(rec.effectiveWeight());
            assertThat(dto.weightedCommissionableBase())
                .isEqualByComparingTo(calculator.monthlyTierBase(weightedReceived).setScale(2, RoundingMode.HALF_UP));

            // Contract composition, from the real calculator + real repo sums:
            List<TierConfig> tiers = commissions.findTiers().isEmpty() ? TierConfig.defaults() : commissions.findTiers();
            BigDecimal total = commissions.sumActiveWeightedActualReceived(rec.salesRepId(), rec.payrollMonth());
            BigDecimal without = total.subtract(weightedReceived).max(BigDecimal.ZERO);
            BigDecimal expectedEstimate = calculator.progressiveCommission(calculator.monthlyTierBase(total), tiers)
                .subtract(calculator.progressiveCommission(calculator.monthlyTierBase(without), tiers));
            assertThat(dto.estimatedCommission()).isEqualByComparingTo(expectedEstimate);
            sawNonZeroEstimate |= expectedEstimate.signum() > 0;
        }
        assertThat(sawNonZeroEstimate).as("fixture must make at least one estimate non-zero").isTrue();
    }

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

    // ─────────────────────────────────────────────────────────────────────────────────────
    // POST {id}/item-weights
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    @Test
    void managerAdjust_afterManagerApproved_isRefused_andItemWeightsUnchanged() {
        long ticketId = dealWithItems(itemSpec(10, 4, 1000, 2), itemSpec(5, 5, 2000, 1));
        long commissionId = createRecord(ticketId, JUNE_INVOICE, "300000.00");
        long itemB = tickets.findById(ticketId).orElseThrow().items().get(1).id();
        commissionService.approve(commissionId, managerActor);
        assertThat(commissions.findById(commissionId).orElseThrow().status()).isEqualTo(CommissionStatus.MANAGER_APPROVED);
        Snapshot before = snapshot(ticketId, commissionId);

        assertThatThrownBy(() -> commissionService.adjustItemWeights(
                commissionId, weights(new ItemWeightMultiplierRequest.Line(itemB, 3)), managerActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));

        assertThat(snapshot(ticketId, commissionId)).isEqualTo(before);
    }

    @Test
    void adjust_refusedFor_sales_account_import_hr_ceo_andNothingChanges() {
        long ticketId = dealWithItems(itemSpec(10, 4, 1000, 2), itemSpec(5, 5, 2000, 1));
        long commissionId = createRecord(ticketId, JUNE_INVOICE, "300000.00");
        long itemB = tickets.findById(ticketId).orElseThrow().items().get(1).id();
        Snapshot before = snapshot(ticketId, commissionId);

        for (UserPrincipal caller : List.of(repActor, accountActor, importActor, hrActor, ceoActor, employeeActor)) {
            assertThatThrownBy(() -> commissionService.adjustItemWeights(
                    commissionId, weights(new ItemWeightMultiplierRequest.Line(itemB, 3)), caller))
                .as("role %s must be refused", caller.role())
                .isInstanceOfSatisfying(ApiException.class,
                    e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
            assertThat(snapshot(ticketId, commissionId)).as("after %s", caller.role()).isEqualTo(before);
        }
    }

    @Test
    void adjust_itemFromAnotherDeal_isRefused_andNothingChanges() {
        long ticketA = dealWithItems(itemSpec(10, 4, 1000, 2), itemSpec(5, 5, 2000, 1));
        long ticketB = dealWithItems(itemSpec(6, 3, 1200, 2));
        long commissionA = createRecord(ticketA, JUNE_INVOICE, "300000.00");
        long commissionB = createRecord(ticketB, JUNE_INVOICE, "200000.00");
        long foreignItem = tickets.findById(ticketB).orElseThrow().items().get(0).id();
        long ownItem = tickets.findById(ticketA).orElseThrow().items().get(0).id();
        Snapshot beforeA = snapshot(ticketA, commissionA);
        Snapshot beforeB = snapshot(ticketB, commissionB);

        // Mixed request: one legitimate line plus one from another deal -- the whole call is refused,
        // the legitimate line must not have been applied either.
        assertThatThrownBy(() -> commissionService.adjustItemWeights(
                commissionA,
                weights(new ItemWeightMultiplierRequest.Line(ownItem, 3),
                    new ItemWeightMultiplierRequest.Line(foreignItem, 3)),
                managerActor))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));

        assertThat(snapshot(ticketA, commissionA)).isEqualTo(beforeA);
        assertThat(snapshot(ticketB, commissionB)).isEqualTo(beforeB);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────
    // fixtures
    // ─────────────────────────────────────────────────────────────────────────────────────

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

    private static String expectedDescription(TicketItemDto i) {
        List<String> parts = new ArrayList<>();
        for (String p : new String[] {i.brand(), i.model(), i.color(), i.texture(), i.size()}) {
            if (p != null && !p.isBlank()) parts.add(p);
        }
        return String.join(" ", parts).trim();
    }

    private BigDecimal effectiveWeightColumn(long commissionId) {
        return jdbc.queryForObject(
            "SELECT effective_weight_multiplier FROM sales.commission_record WHERE commission_id = :id",
            Map.of("id", commissionId), BigDecimal.class);
    }

    /** Item weights (by sort order) + the frozen effective weight, as stored. Strings so scale compares exactly. */
    private record Snapshot(List<Integer> itemWeights, String effectiveWeight, String status) {}

    private Snapshot snapshot(long ticketId, long commissionId) {
        List<Integer> weightsByItem = tickets.findById(ticketId).orElseThrow().items().stream()
            .map(TicketItemDto::weightMultiplier).toList();
        BigDecimal effective = effectiveWeightColumn(commissionId);
        return new Snapshot(weightsByItem, effective == null ? null : effective.toPlainString(),
            commissions.findById(commissionId).orElseThrow().status());
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
