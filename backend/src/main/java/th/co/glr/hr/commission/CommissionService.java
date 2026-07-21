package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketRepository;

@Service
public class CommissionService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> SALES_ROLES = Set.of("sales");
    private static final Set<String> SUBMIT_ROLES = Set.of("sales", "sales_manager", "ceo");
    private static final Set<String> MANAGER_ROLES = Set.of("sales_manager");
    private static final Set<String> CEO_ROLES = Set.of("ceo");
    private static final Set<String> PAYROLL_ROLES = Set.of("hr");
    private static final Set<String> COMMISSION_INVOICE_MIME_TYPES = Set.of(
        "application/pdf",
        "image/jpeg",
        "image/png"
    );

    // Step 9 cross-check threshold: flag (never block) when the hand-typed grossAmount diverges
    // from the linked deal's actual payableAmount by more than this fraction.
    private static final BigDecimal MISMATCH_THRESHOLD = new BigDecimal("0.05");

    private final CommissionRepository commissions;
    private final CommissionAttachmentRepository commissionAttachments;
    private final CommissionCalculator calculator;
    private final FileStorageService fileStorage;
    private final AuditService auditService;
    private final NotificationService notificationService;
    private final TicketRepository tickets;

    public CommissionService(
            CommissionRepository commissions,
            CommissionAttachmentRepository commissionAttachments,
            CommissionCalculator calculator,
            FileStorageService fileStorage,
            AuditService auditService,
            NotificationService notificationService,
            TicketRepository tickets) {
        this.commissions = commissions;
        this.commissionAttachments = commissionAttachments;
        this.calculator = calculator;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.tickets = tickets;
    }

    public List<CommissionRecord> list(LocalDate payrollMonth, UserPrincipal actor) {
        Long salesRepFilter = "sales".equals(actor.role()) ? actor.id() : null;
        return commissions.findRecords(salesRepFilter, payrollMonth);
    }

    @Transactional
    public CommissionRecord submit(SubmitCommissionRequest request, UserPrincipal actor) {
        return submit(request, null, actor);
    }

    @Transactional
    public CommissionRecord submit(SubmitCommissionRequest request, MultipartFile invoiceAttachment, UserPrincipal actor) {
        if (!SUBMIT_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if ("sales".equals(actor.role()) && hasDeductionOverride(request)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Sales cannot edit deduction fields");
        }
        if (invoiceAttachment == null || invoiceAttachment.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tax invoice file is required");
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
        DealLinkage linkage = resolveDealLinkage(safeRequest);

        try {
            long invoiceId = commissions.createInvoice(safeRequest);
            FileStorageService.StoredFile storedFile = fileStorage.store(
                "commission-invoice",
                invoiceId,
                invoiceAttachment,
                COMMISSION_INVOICE_MIME_TYPES
            );
            long attachmentId = commissionAttachments.save(
                invoiceId,
                storedFile.fileName(),
                storedFile.filePath(),
                storedFile.mimeType(),
                storedFile.fileSize(),
                actor.id()
            );
            commissions.attachInvoiceFile(invoiceId, attachmentId);
            long commissionId = commissions.createCommissionRecord(
                invoiceId,
                safeRequest.sourceTicketId(),
                salesRepId,
                actor.id(),
                payrollMonth(safeRequest.invoiceDate()),
                calculation,
                linkage.payableSnapshot(),
                linkage.mismatch()
            );
            CommissionRecord created = requireRecord(commissionId);
            auditService.record(actor, "SUBMIT_COMMISSION", "commission_record", commissionId, null, created);
            notifySubmitted(created);
            return created;
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "Invoice number already exists");
        }
    }

    /**
     * Step 9 gate + cross-check. When {@code sourceTicketId} is null, the commission is unlinked
     * (manual) — behavior stays exactly as it was pre-Step-9: no snapshot, no mismatch. When it is
     * set, the linked deal must have already reached {@link DealStage#CLOSED_PAID} (final payment
     * recorded) or the submission is rejected outright; a divergence between the hand-typed
     * grossAmount and the deal's actual payableAmount is flagged for reviewers but never blocks
     * submission — see {@link #MISMATCH_THRESHOLD}.
     */
    private DealLinkage resolveDealLinkage(SubmitCommissionRequest request) {
        Long ticketId = request.sourceTicketId();
        if (ticketId == null) {
            return new DealLinkage(null, false);
        }
        String salesStage = tickets.findSalesStage(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        if (!DealStage.CLOSED_PAID.equals(salesStage)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "Deal has not reached final payment (CLOSED_PAID); commission cannot be submitted yet.");
        }
        BigDecimal payable = tickets.payableAmount(ticketId);
        boolean mismatch = isMismatch(request.grossAmount(), payable);
        return new DealLinkage(payable, mismatch);
    }

    private boolean isMismatch(BigDecimal grossAmount, BigDecimal payable) {
        BigDecimal gross = grossAmount == null ? BigDecimal.ZERO : grossAmount;
        if (payable == null || payable.signum() == 0) {
            return gross.signum() > 0;
        }
        BigDecimal deviation = gross.subtract(payable).abs()
            .divide(payable.abs(), 6, RoundingMode.HALF_UP);
        return deviation.compareTo(MISMATCH_THRESHOLD) > 0;
    }

    private record DealLinkage(BigDecimal payableSnapshot, boolean mismatch) {}

    @Transactional
    public CommissionRecord updateDeductions(long id, UpdateCommissionDeductionsRequest request, UserPrincipal actor) {
        requireManagerOrCeo(actor);
        CommissionRecord existing = requireRecord(id);
        if (CommissionStatus.VOID.equals(existing.status()) || CommissionStatus.REJECTED.equals(existing.status())) {
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
        CommissionRecord after = requireRecord(id);
        auditService.record(actor, "UPDATE_COMMISSION_DEDUCTIONS", "commission_record", id, existing, after);
        return after;
    }

    @Transactional
    public CommissionRecord approve(long id, UserPrincipal actor) {
        CommissionRecord existing = requireRecord(id);
        if (CommissionStatus.SUBMITTED.equals(existing.status())) {
            return managerApprove(id, actor, existing);
        }
        if (CommissionStatus.MANAGER_APPROVED.equals(existing.status())) {
            return ceoApprove(id, actor, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "Commission record has already been reviewed");
    }

    private CommissionRecord managerApprove(long id, UserPrincipal actor, CommissionRecord existing) {
        requireManager(actor);
        commissions.managerApprove(id, actor.id());
        CommissionRecord after = requireRecord(id);
        auditService.record(actor, "MANAGER_APPROVE_COMMISSION", "commission_record", id, existing, after);
        notifyManagerApproved(after);
        return after;
    }

    private CommissionRecord ceoApprove(long id, UserPrincipal actor, CommissionRecord existing) {
        requireCeo(actor);
        commissions.ceoApprove(id, actor.id());
        CommissionRecord after = requireRecord(id);
        auditService.record(actor, "CEO_APPROVE_COMMISSION", "commission_record", id, existing, after);
        notifyCeoApproved(after);
        return after;
    }

    @Transactional
    public CommissionRecord reject(long id, ReviewCommissionRequest request, UserPrincipal actor) {
        CommissionRecord existing = requireRecord(id);
        if (CommissionStatus.SUBMITTED.equals(existing.status())) {
            return managerReject(id, request, actor, existing);
        }
        if (CommissionStatus.MANAGER_APPROVED.equals(existing.status())) {
            return ceoReject(id, request, actor, existing);
        }
        throw new ApiException(HttpStatus.CONFLICT, "Commission record has already been reviewed");
    }

    private CommissionRecord managerReject(long id, ReviewCommissionRequest request, UserPrincipal actor, CommissionRecord existing) {
        requireManager(actor);
        commissions.managerReject(id, actor.id(), note(request));
        CommissionRecord after = requireRecord(id);
        auditService.record(actor, "REJECT_COMMISSION", "commission_record", id, existing, after);
        notifyRejected(after);
        return after;
    }

    private CommissionRecord ceoReject(long id, ReviewCommissionRequest request, UserPrincipal actor, CommissionRecord existing) {
        requireCeo(actor);
        commissions.ceoReject(id, actor.id(), note(request));
        CommissionRecord after = requireRecord(id);
        auditService.record(actor, "CEO_REJECT_COMMISSION", "commission_record", id, existing, after);
        notifyRejected(after);
        return after;
    }

    @Transactional
    public CommissionRecord createClawback(long id, CreateClawbackRequest request, UserPrincipal actor) {
        requireManagerOrCeo(actor);
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
        CommissionRecord clawback = requireRecord(clawbackId);
        auditService.record(actor, "CREATE_CLAWBACK", "commission_record", clawbackId, null, clawback);
        return clawback;
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

    private void requireManager(UserPrincipal actor) {
        if (!MANAGER_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a sales manager can review submitted commissions");
        }
    }

    private void requireCeo(UserPrincipal actor) {
        if (!CEO_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the CEO can review manager-approved commissions");
        }
    }

    private void requireManagerOrCeo(UserPrincipal actor) {
        if (!MANAGER_ROLES.contains(actor.role()) && !CEO_ROLES.contains(actor.role())) {
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

    private String note(ReviewCommissionRequest request) {
        return request == null || request.reviewerNote() == null || request.reviewerNote().isBlank()
            ? null
            : request.reviewerNote().trim();
    }

    private void notifySubmitted(CommissionRecord record) {
        notificationService.notify(
            record.salesRepId(),
            "COMMISSION_SUBMITTED",
            "ส่งคำขอค่าคอมแล้ว",
            "คำขอค่าคอมใบกำกับ " + record.invoiceDetails().invoiceNumber() + " ถูกส่งให้ผู้จัดการฝ่ายขายตรวจสอบแล้ว",
            "/commissions",
            true
        );
        for (Long managerEmployeeId : commissions.findSalesManagerApproverEmployeeIds()) {
            notificationService.notify(
                managerEmployeeId,
                "COMMISSION_PENDING_MANAGER",
                "มีคำขอค่าคอมรออนุมัติ",
                record.salesRepName() + " ส่งคำขอค่าคอมใบกำกับ " + record.invoiceDetails().invoiceNumber(),
                "/commissions",
                true
            );
        }
    }

    private void notifyManagerApproved(CommissionRecord record) {
        notificationService.notify(
            record.salesRepId(),
            "COMMISSION_MANAGER_APPROVED",
            "ผู้จัดการอนุมัติค่าคอมแล้ว",
            "คำขอค่าคอมใบกำกับ " + record.invoiceDetails().invoiceNumber() + " ผ่านผู้จัดการแล้ว และรอ CEO อนุมัติขั้นสุดท้าย",
            "/commissions",
            true
        );
        for (Long ceoEmployeeId : commissions.findCeoApproverEmployeeIds()) {
            notificationService.notify(
                ceoEmployeeId,
                "COMMISSION_PENDING_CEO",
                "มีคำขอค่าคอมรอ CEO อนุมัติ",
                record.salesRepName() + " มีคำขอค่าคอมใบกำกับ " + record.invoiceDetails().invoiceNumber() + " ที่ผู้จัดการอนุมัติแล้ว",
                "/commissions",
                true
            );
        }
    }

    private void notifyCeoApproved(CommissionRecord record) {
        notificationService.notify(
            record.salesRepId(),
            "COMMISSION_APPROVED",
            "CEO อนุมัติค่าคอมแล้ว",
            "คำขอค่าคอมใบกำกับ " + record.invoiceDetails().invoiceNumber() + " อนุมัติครบถ้วนแล้ว",
            "/commissions",
            true
        );
        if (record.managerApprovedBy() != null) {
            notificationService.notify(
                record.managerApprovedBy(),
                "COMMISSION_APPROVED",
                "CEO อนุมัติค่าคอมแล้ว",
                record.salesRepName() + " ได้รับการอนุมัติค่าคอมใบกำกับ " + record.invoiceDetails().invoiceNumber(),
                "/commissions",
                true
            );
        }
    }

    private void notifyRejected(CommissionRecord record) {
        notificationService.notify(
            record.salesRepId(),
            "COMMISSION_REJECTED",
            "คำขอค่าคอมถูกปฏิเสธ",
            "คำขอค่าคอมใบกำกับ " + record.invoiceDetails().invoiceNumber() + " ถูกปฏิเสธ: "
                + (record.rejectionReason() == null ? "กรุณาติดต่อผู้อนุมัติ" : record.rejectionReason()),
            "/commissions",
            true
        );
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
