package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

@Service
public class CommissionService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> SALES_ROLES = Set.of("sales", "admin");
    private static final Set<String> APPROVER_ROLES = Set.of("sales_manager", "ceo", "admin");
    private static final Set<String> PAYROLL_ROLES = Set.of("hr", "admin");

    private final CommissionRepository commissions;
    private final CommissionCalculator calculator;

    public CommissionService(CommissionRepository commissions, CommissionCalculator calculator) {
        this.commissions = commissions;
        this.calculator = calculator;
    }

    public List<CommissionRecord> list(LocalDate payrollMonth, UserPrincipal actor) {
        Long salesRepFilter = "sales".equals(actor.role()) ? actor.id() : null;
        return commissions.findRecords(salesRepFilter, payrollMonth);
    }

    @Transactional
    public CommissionRecord submit(SubmitCommissionRequest request, UserPrincipal actor) {
        if (!SALES_ROLES.contains(actor.role()) && !APPROVER_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if ("sales".equals(actor.role()) && hasDeductionOverride(request)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Sales cannot edit deduction fields");
        }

        long salesRepId = resolveSalesRep(request.salesRepId(), actor);
        SubmitCommissionRequest safeRequest = normalizedRequest(request, salesRepId, actor);
        InvoiceCalculation calculation = calculator.calculateInvoice(
            safeRequest.grossAmount(),
            safeRequest.bankFees(),
            safeRequest.suspenseVat(),
            safeRequest.transportFee(),
            safeRequest.cutFee(),
            safeRequest.shortfall()
        );

        try {
            long invoiceId = commissions.createInvoice(safeRequest);
            long commissionId = commissions.createCommissionRecord(
                invoiceId,
                safeRequest.sourceTicketId(),
                salesRepId,
                actor.id(),
                payrollMonth(safeRequest.invoiceDate()),
                calculation
            );
            return requireRecord(commissionId);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "Invoice number already exists");
        }
    }

    @Transactional
    public CommissionRecord updateDeductions(long id, UpdateCommissionDeductionsRequest request, UserPrincipal actor) {
        requireApprover(actor);
        CommissionRecord existing = requireRecord(id);
        if (CommissionStatus.VOID.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Cannot edit a void commission record");
        }
        BigDecimal transportFee = valueOrExisting(request.transportFee(), existing.invoiceDetails().transportFee());
        BigDecimal cutFee = valueOrExisting(request.cutFee(), existing.invoiceDetails().cutFee());
        BigDecimal shortfall = valueOrExisting(request.shortfall(), existing.invoiceDetails().shortfall());
        commissions.updateDeductions(existing.invoiceDetails().id(), transportFee, cutFee, shortfall);

        CommissionRecord refreshed = requireRecord(id);
        InvoiceDetails invoice = refreshed.invoiceDetails();
        InvoiceCalculation calculation = calculator.calculateInvoice(
            invoice.grossAmount(),
            invoice.bankFees(),
            invoice.suspenseVat(),
            invoice.transportFee(),
            invoice.cutFee(),
            invoice.shortfall()
        );
        commissions.updateCommissionAmountsForInvoice(invoice.id(), calculation);
        return requireRecord(id);
    }

    @Transactional
    public CommissionRecord approve(long id, UserPrincipal actor) {
        requireApprover(actor);
        CommissionRecord existing = requireRecord(id);
        if (!CommissionStatus.SUBMITTED.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only submitted commission records can be approved");
        }
        commissions.approve(id, actor.id());
        return requireRecord(id);
    }

    @Transactional
    public CommissionRecord createClawback(long id, CreateClawbackRequest request, UserPrincipal actor) {
        requireApprover(actor);
        CommissionRecord original = requireRecord(id);
        if (!CommissionKind.SALE.equals(original.kind()) || !CommissionStatus.APPROVED.equals(original.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "Only approved sale commissions can be clawed back");
        }
        if (commissions.hasActiveClawbackFor(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "This commission already has an active clawback");
        }
        long clawbackId = commissions.createClawback(
            original,
            actor.id(),
            currentPayrollMonth(),
            request.reason().trim()
        );
        return requireRecord(clawbackId);
    }

    public CommissionSimulationDto simulate(CommissionSimulatorRequest request, UserPrincipal actor) {
        long salesRepId = resolveSalesRep(request.salesRepId(), actor);
        if ("sales".equals(actor.role()) && hasDeductionOverride(request)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Sales cannot edit deduction fields");
        }
        LocalDate month = request.payrollMonth() == null ? currentPayrollMonth() : payrollMonth(request.payrollMonth());
        InvoiceCalculation invoice = calculator.calculateInvoice(
            request.grossAmount(),
            request.bankFees(),
            request.suspenseVat(),
            request.transportFee(),
            request.cutFee(),
            request.shortfall()
        );
        BigDecimal existingBase = commissions.sumActiveMonthlyBase(salesRepId, month);
        BigDecimal projectedBase = existingBase.add(invoice.commissionableBase());
        List<TierConfig> tiers = tiers();
        BigDecimal priorCommission = calculator.progressiveCommission(existingBase, tiers);
        BigDecimal projectedCommission = calculator.progressiveCommission(projectedBase, tiers);
        return new CommissionSimulationDto(
            month,
            invoice.actualReceived(),
            invoice.commissionableBase(),
            existingBase,
            projectedBase,
            projectedCommission,
            projectedCommission.subtract(priorCommission)
        );
    }

    public PayrollCommissionSummaryDto payrollReadySummary(LocalDate payrollMonth, UserPrincipal actor) {
        if (!PAYROLL_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        LocalDate month = payrollMonth == null ? currentPayrollMonth() : payrollMonth(payrollMonth);
        List<CommissionRecord> records = commissions.findApprovedRecordsByMonth(month);
        Map<Long, RepAccumulator> grouped = new LinkedHashMap<>();
        for (CommissionRecord record : records) {
            grouped.computeIfAbsent(record.salesRepId(), id -> new RepAccumulator(record.salesRepId(), record.salesRepName()))
                .add(record.commissionableBase());
        }
        List<TierConfig> tiers = tiers();
        List<SalesRepCommissionSummaryDto> repSummaries = new ArrayList<>();
        BigDecimal totalBase = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        for (RepAccumulator rep : grouped.values()) {
            BigDecimal safeBase = rep.base.max(BigDecimal.ZERO);
            BigDecimal commission = calculator.progressiveCommission(safeBase, tiers);
            repSummaries.add(new SalesRepCommissionSummaryDto(rep.salesRepId, rep.salesRepName, safeBase, commission));
            totalBase = totalBase.add(safeBase);
            totalCommission = totalCommission.add(commission);
        }
        repSummaries.sort(Comparator.comparing(SalesRepCommissionSummaryDto::salesRepName, Comparator.nullsLast(String::compareTo)));
        return new PayrollCommissionSummaryDto(month, "PAYROLL_READY", totalBase, totalCommission, repSummaries);
    }

    private CommissionRecord requireRecord(long id) {
        return commissions.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Commission record not found"));
    }

    private List<TierConfig> tiers() {
        List<TierConfig> configured = commissions.findTiers();
        return configured.isEmpty() ? TierConfig.defaults() : configured;
    }

    private SubmitCommissionRequest normalizedRequest(SubmitCommissionRequest request, long salesRepId, UserPrincipal actor) {
        boolean salesCannotEditDeductions = "sales".equals(actor.role());
        return new SubmitCommissionRequest(
            request.sourceTicketId(),
            salesRepId,
            request.invoiceNumber(),
            request.invoiceDate(),
            request.grossAmount(),
            money(request.bankFees()),
            money(request.suspenseVat()),
            salesCannotEditDeductions ? BigDecimal.ZERO : money(request.transportFee()),
            salesCannotEditDeductions ? BigDecimal.ZERO : money(request.cutFee()),
            salesCannotEditDeductions ? BigDecimal.ZERO : money(request.shortfall())
        );
    }

    private long resolveSalesRep(Long requestedSalesRepId, UserPrincipal actor) {
        if ("sales".equals(actor.role())) {
            if (requestedSalesRepId != null && requestedSalesRepId != actor.id()) {
                throw new ApiException(HttpStatus.FORBIDDEN, "Sales can only submit their own commissions");
            }
            return actor.id();
        }
        return requestedSalesRepId == null ? actor.id() : requestedSalesRepId;
    }

    private void requireApprover(UserPrincipal actor) {
        if (!APPROVER_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }

    private boolean hasDeductionOverride(SubmitCommissionRequest request) {
        return positive(request.transportFee()) || positive(request.cutFee()) || positive(request.shortfall());
    }

    private boolean hasDeductionOverride(CommissionSimulatorRequest request) {
        return positive(request.transportFee()) || positive(request.cutFee()) || positive(request.shortfall());
    }

    private boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private BigDecimal valueOrExisting(BigDecimal value, BigDecimal existing) {
        return value == null ? existing : value;
    }

    private BigDecimal money(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private LocalDate payrollMonth(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    private LocalDate currentPayrollMonth() {
        return LocalDate.now(BUSINESS_ZONE).withDayOfMonth(1);
    }

    private static final class RepAccumulator {
        private final long salesRepId;
        private final String salesRepName;
        private BigDecimal base = BigDecimal.ZERO;

        private RepAccumulator(long salesRepId, String salesRepName) {
            this.salesRepId = salesRepId;
            this.salesRepName = salesRepName;
        }

        private void add(BigDecimal value) {
            base = base.add(value == null ? BigDecimal.ZERO : value);
        }
    }
}
