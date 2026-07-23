package th.co.glr.hr.commission;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.ticket.AttachType;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketRepository;

@Service
public class CommissionService {
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Bangkok");
    private static final Set<String> SALES_ROLES = Set.of("sales");
    // Slice A2 (AUTHZ CHANGE — see docs/agent-handoffs/102_feat-sales-commission-auto-approval.md):
    // sales can no longer submit/create commissions at all — commission creation moved to the
    // accountant's auto-create trigger (createFromDeal) at deal close. sales_manager/ceo keep the
    // ability they already had (e.g. manual/unlinked corrections); account replaces sales as the
    // day-to-day creator role for the JSON/multipart submit() path too, in addition to owning
    // createFromDeal exclusively.
    private static final Set<String> SUBMIT_ROLES = Set.of("account", "sales_manager", "ceo");
    private static final Set<String> CREATE_FROM_DEAL_ROLES = Set.of("account");
    private static final Set<String> MANAGER_ROLES = Set.of("sales_manager");
    private static final Set<String> CEO_ROLES = Set.of("ceo");
    private static final Set<String> PAYROLL_ROLES = Set.of("hr");
    // Who may read the commission list (GET /api/commissions). Matches the frontend's
    // canListCommissionRecords exactly — SALES, SALES_MANAGER, CEO only. NOT hr (reads via the
    // payroll-ready feed, PAYROLL_ROLES) and NOT account (only does createFromDeal, never lists);
    // import/plain-employee/warehouse/qc have no business reason to see commission amounts. Sales
    // is additionally row-scoped to its own rows below; sales_manager/ceo see the full feed.
    private static final Set<String> LIST_VIEWER_ROLES =
        Set.of("sales", "sales_manager", "ceo");
    // feat/commission-manual-adjustments: ONLY sales_manager/ceo may create a manual commission
    // entry (ADJUSTMENT/MANAGER/STOCK_BONUS/INCENTIVE). This is the authorization boundary this branch is required to
    // prove with a real-DB, wrong-way-round integration test per CLAUDE.md -- see
    // ManualCommissionAuthzIntegrationTest.
    private static final Set<String> MANUAL_CREATE_ROLES = Set.of("sales_manager", "ceo");
    private static final Set<String> MANUAL_KINDS = Set.of(
        CommissionKind.ADJUSTMENT, CommissionKind.MANAGER, CommissionKind.STOCK_BONUS, CommissionKind.INCENTIVE);
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
    private final AttachmentRepository attachments;

    public CommissionService(
            CommissionRepository commissions,
            CommissionAttachmentRepository commissionAttachments,
            CommissionCalculator calculator,
            FileStorageService fileStorage,
            AuditService auditService,
            NotificationService notificationService,
            TicketRepository tickets,
            AttachmentRepository attachments) {
        this.commissions = commissions;
        this.commissionAttachments = commissionAttachments;
        this.calculator = calculator;
        this.fileStorage = fileStorage;
        this.auditService = auditService;
        this.notificationService = notificationService;
        this.tickets = tickets;
        this.attachments = attachments;
    }

    public List<CommissionRecord> list(LocalDate payrollMonth, UserPrincipal actor) {
        if (!LIST_VIEWER_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
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
            safeRequest.shortfall(),
            safeRequest.withholdingTax(),
            safeRequest.overpayment()
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
                saleCommissionPayrollMonth(safeRequest.invoiceDate()),
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
     * Slice A2 — commission auto-create trigger. Sales does nothing to get commission: when the
     * accountant records the tax invoice at deal close, this creates the commission for the
     * deal's owner directly, in {@code SUBMITTED} status, ready for sales-manager review.
     *
     * <p>{@code salesRepId} is <b>always</b> the deal's {@code createdById} — never taken from the
     * request — so a commission can never be attributed to anyone other than the rep who owns the
     * ticket. {@code grossAmount} defaults from the deal's payable amount when omitted; every other
     * amount defaults to zero, exactly like {@link #submit}. The close-eligibility gate is the
     * exact same one {@link #submit} has always used ({@link #resolveDealLinkage} — CLOSED_PAID or
     * reject) — reused verbatim, not reimplemented, so this trigger can never be looser than the
     * old sales-facing submission was.
     *
     * <p>Uploading the invoice here also satisfies the ticket's three-party close gate: the same
     * physical file is additionally recorded as an {@link AttachType#INVOICE} attachment on the
     * ticket itself, so {@code TicketRepository#hasInvoiceAttachment} — and therefore {@code
     * invoiceOnFile} — becomes true from this one upload.
     */
    @Transactional
    public CommissionRecord createFromDeal(
            long ticketId,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal grossAmount,
            BigDecimal bankFees,
            BigDecimal suspenseVat,
            BigDecimal transportFee,
            BigDecimal cutFee,
            BigDecimal shortfall,
            BigDecimal withholdingTax,
            BigDecimal overpayment,
            MultipartFile invoiceAttachment,
            UserPrincipal actor) {
        if (!CREATE_FROM_DEAL_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        if (invoiceAttachment == null || invoiceAttachment.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "Tax invoice file is required");
        }
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));
        long salesRepId = ticket.summary().createdById();
        if (commissions.hasActiveCommissionForTicket(ticketId)) {
            throw new ApiException(HttpStatus.CONFLICT, "A commission already exists for this deal");
        }
        BigDecimal effectiveGrossAmount = grossAmount != null ? grossAmount : tickets.payableAmount(ticketId);

        SubmitCommissionRequest request = new SubmitCommissionRequest(
            ticketId,
            salesRepId,
            invoiceNumber,
            invoiceDate,
            effectiveGrossAmount,
            money(bankFees),
            money(suspenseVat),
            money(transportFee),
            money(cutFee),
            money(shortfall),
            money(withholdingTax),
            money(overpayment)
        );
        InvoiceCalculation calculation = calculator.calculateInvoice(
            request.grossAmount(),
            request.bankFees(),
            request.suspenseVat(),
            request.transportFee(),
            request.cutFee(),
            request.shortfall(),
            request.withholdingTax(),
            request.overpayment()
        );
        // Re-checks CLOSED_PAID and computes the payable-amount cross-check snapshot — the exact
        // same gate submit() has always enforced. Never loosened for this trigger.
        DealLinkage linkage = resolveDealLinkage(request);

        try {
            long invoiceId = commissions.createInvoice(request);
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
            // Reuses the existing ticket-attachment path so the same upload also satisfies the
            // close gate's invoiceOnFile check — no separate upload flow for the accountant.
            attachments.save(
                ticketId,
                null,
                storedFile.fileName(),
                storedFile.filePath(),
                storedFile.mimeType(),
                storedFile.fileSize(),
                AttachType.INVOICE,
                actor.id()
            );
            long commissionId = commissions.createCommissionRecord(
                invoiceId,
                ticketId,
                salesRepId,
                actor.id(),
                saleCommissionPayrollMonth(request.invoiceDate()),
                calculation,
                linkage.payableSnapshot(),
                linkage.mismatch()
            );
            CommissionRecord created = requireRecord(commissionId);
            auditService.record(actor, "CREATE_COMMISSION_FROM_DEAL", "commission_record", commissionId, null, created);
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
        if (MANUAL_KINDS.contains(existing.kind())) {
            // Manual entries (ADJUSTMENT/MANAGER) have no invoice_details row to edit -- existing
            // .invoiceDetails() is null for them (V84). There is nothing here to update; the
            // manual_amount/manual_reason a manual entry was created with is immutable by design
            // (create a fresh entry, or use the clawback-style correction pattern, if it was wrong).
            throw new ApiException(HttpStatus.CONFLICT, "Manual commission entries have no invoice deductions to edit");
        }
        BigDecimal grossAmount = valueOrExisting(request.grossAmount(), existing.invoiceDetails().grossAmount());
        BigDecimal bankFees = valueOrExisting(request.bankFees(), existing.invoiceDetails().bankFees());
        BigDecimal suspenseVat = valueOrExisting(request.suspenseVat(), existing.invoiceDetails().suspenseVat());
        BigDecimal transportFee = valueOrExisting(request.transportFee(), existing.invoiceDetails().transportFee());
        BigDecimal cutFee = valueOrExisting(request.cutFee(), existing.invoiceDetails().cutFee());
        BigDecimal shortfall = valueOrExisting(request.shortfall(), existing.invoiceDetails().shortfall());
        BigDecimal withholdingTax = valueOrExisting(request.withholdingTax(), existing.invoiceDetails().withholdingTax());
        BigDecimal overpayment = valueOrExisting(request.overpayment(), existing.invoiceDetails().overpayment());
        commissions.updateDeductions(existing.invoiceDetails().id(), grossAmount, bankFees, suspenseVat,
            transportFee, cutFee, shortfall, withholdingTax, overpayment);

        // Commission redesign calc-refine: the tier-base weight multiplier (1/2/3, see
        // CommissionCalculator) is set here as part of the same manager/CEO review step — sales
        // has no route to this endpoint at all (requireManagerOrCeo above), so no separate check
        // is needed to keep sales from setting it.
        int weightMultiplier = valueOrExisting(request.weightMultiplier(), existing.weightMultiplier());
        commissions.updateWeightMultiplier(id, weightMultiplier);

        CommissionRecord refreshed = requireRecord(id);
        InvoiceDetails invoice = refreshed.invoiceDetails();
        // Final commission is ALWAYS recomputed from the stored fields — there is no path that
        // lets a reviewer set the final amount directly.
        InvoiceCalculation calculation = calculator.calculateInvoice(
            invoice.grossAmount(),
            invoice.bankFees(),
            invoice.suspenseVat(),
            invoice.transportFee(),
            invoice.cutFee(),
            invoice.shortfall(),
            invoice.withholdingTax(),
            invoice.overpayment()
        );
        commissions.updateCommissionAmountsForInvoice(invoice.id(), calculation);
        CommissionRecord after = requireRecord(id);
        auditService.record(actor, "UPDATE_COMMISSION_DEDUCTIONS", "commission_record", id, existing, after);
        // The reason is recorded as its own audit entry (not a field on commission_record) —
        // required on every call per Slice A2 (CLAUDE.md "permission changes must ship evidence").
        auditService.record(actor, "UPDATE_COMMISSION_DEDUCTIONS_REASON", "commission_record", id, null,
            Map.of("reason", request.reason()));
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

    /**
     * Manual commission entries (feat/commission-manual-adjustments): a sales_manager/CEO adds a
     * hand-typed, signed {@code amount} for a manual {@code kind} ({@link CommissionKind#ADJUSTMENT},
     * {@link CommissionKind#MANAGER}, {@link CommissionKind#STOCK_BONUS}, or
     * {@link CommissionKind#INCENTIVE}) against {@code salesRepId}'s {@code payrollMonth}. The amount
     * is stored VERBATIM in {@code manual_amount} -- it never goes through {@link
     * CommissionCalculator#calculateInvoice} or any tier/progressive math, and there is no
     * invoice/{@code sourceTicketId} attached.
     *
     * <p>Authz boundary (the thing this branch must prove with a real-DB integration test): only
     * {@code sales_manager}/{@code ceo} may call this -- {@code sales}, any other rep role,
     * {@code account}, {@code import}, and {@code hr} all get 403, with zero rows created.
     *
     * <p>Created by a {@code sales_manager} lands {@code MANAGER_APPROVED} (the manager vouches
     * for it, but it still needs CEO sign-off before it counts toward payroll); created by the
     * {@code ceo} lands {@code APPROVED} directly. From {@code MANAGER_APPROVED}, the existing
     * {@link #approve(long, UserPrincipal)}/{@link #ceoApprove} chain carries it to {@code
     * APPROVED} exactly as it does for a SALE commission -- no parallel approval path exists.
     */
    @Transactional
    public CommissionRecord createManualCommission(
            Long salesRepId, String kind, BigDecimal amount, String reason, LocalDate payrollMonth, UserPrincipal actor) {
        if (!MANUAL_CREATE_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only a sales manager or the CEO may create a manual commission entry");
        }
        if (salesRepId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "salesRepId is required");
        }
        if (!MANUAL_KINDS.contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "kind must be one of " + String.join(", ", new java.util.TreeSet<>(MANUAL_KINDS)));
        }
        if (amount == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "amount is required");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A reason is required for a manual commission entry");
        }
        // A MANAGER-kind entry represents team/manager commission earned, not a correction --
        // never negative. An ADJUSTMENT may legitimately be negative (a deduction/clawback-style
        // correction), so no sign restriction there.
        if (CommissionKind.MANAGER.equals(kind) && amount.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "A MANAGER commission entry cannot be negative");
        }
        LocalDate month = payrollMonth == null ? currentPayrollMonth() : payrollMonth(payrollMonth);
        boolean ceoCreated = CEO_ROLES.contains(actor.role());
        long commissionId = commissions.createManualCommission(
            kind,
            salesRepId,
            actor.id(),
            month,
            amount.setScale(2, RoundingMode.HALF_UP),
            reason.trim(),
            ceoCreated
        );
        CommissionRecord created = requireRecord(commissionId);
        auditService.record(actor, "CREATE_MANUAL_COMMISSION", "commission_record", commissionId, null, created);
        notifyManualCreated(created);
        return created;
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
            request.shortfall(),
            request.withholdingTax(),
            request.overpayment()
        );
        // Commission redesign calc-refine: the monthly TIER BASE is now the full-precision
        // SUM(actual_received x weight_multiplier) / 1.07, not a sum of already-2dp-rounded
        // commissionable_base rows. The invoice being simulated hasn't been persisted (no
        // weight_multiplier choice yet — that only happens later, in the manager-review step), so
        // it is folded into the weighted sum at its default weight of 1 before dividing, rather
        // than adding an already-rounded 2dp commissionableBase onto a full-precision base.
        BigDecimal existingWeightedActualReceived = commissions.sumActiveWeightedActualReceived(salesRepId, month);
        BigDecimal projectedWeightedActualReceived = existingWeightedActualReceived.add(invoice.actualReceived());
        BigDecimal existingBase = calculator.monthlyTierBase(existingWeightedActualReceived);
        BigDecimal projectedBase = calculator.monthlyTierBase(projectedWeightedActualReceived);
        List<TierConfig> tiers = tiers();
        BigDecimal priorCommission = calculator.progressiveCommission(existingBase, tiers);
        BigDecimal projectedCommission = calculator.progressiveCommission(projectedBase, tiers);
        return new CommissionSimulationDto(
            month,
            invoice.actualReceived(),
            invoice.commissionableBase(),
            existingBase.setScale(2, RoundingMode.HALF_UP),
            projectedBase.setScale(2, RoundingMode.HALF_UP),
            projectedCommission,
            projectedCommission.subtract(priorCommission)
        );
    }

    public PayrollCommissionSummaryDto payrollReadySummary(LocalDate payrollMonth, UserPrincipal actor) {
        if (!PAYROLL_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
        LocalDate month = payrollMonth == null ? currentPayrollMonth() : payrollMonth(payrollMonth);
        List<RepPayrollCommission> reps = computeRepPayrollCommissions(month);
        List<SalesRepCommissionSummaryDto> repSummaries = new ArrayList<>();
        BigDecimal totalBase = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        for (RepPayrollCommission rep : reps) {
            repSummaries.add(new SalesRepCommissionSummaryDto(
                rep.salesRepId(), rep.salesRepName(), rep.tierCommissionableBase(), rep.totalCommission(), rep.manualAdjustmentAmount()));
            totalBase = totalBase.add(rep.tierCommissionableBase());
            totalCommission = totalCommission.add(rep.totalCommission());
        }
        repSummaries.sort(Comparator.comparing(SalesRepCommissionSummaryDto::salesRepName, Comparator.nullsLast(String::compareTo)));
        return new PayrollCommissionSummaryDto(month, "PAYROLL_READY", totalBase, totalCommission, repSummaries);
    }

    /**
     * Commission-payroll weighted-base + manual-entries fix (2026-07-23): single source of truth
     * for "how much commission does payroll owe this rep for this month" -- {@link
     * #payrollReadySummary} (what HR sees) and {@code PayrollService#commissionPayByEmployee}
     * (what payroll actually pays, via {@link #payrollCommissionTotalsByEmployee}) both build on
     * this exact aggregation so the two paths can never diverge again. Real payroll pays each
     * rep's FULL commission = weighted tier commission + their approved manual entries
     * (ADJUSTMENT/MANAGER/STOCK_BONUS/INCENTIVE) -- e.g. the owner-reconciled "jennet" case: tier
     * 67,849.23 + a 15,000 INCENTIVE = 82,849.23 paid.
     *
     * @param payrollMonth must already be normalized to the 1st of the month -- this queries
     *                     {@link CommissionRepository#findApprovedRecordsByMonth} with an exact
     *                     match, it does not re-normalize.
     */
    private List<RepPayrollCommission> computeRepPayrollCommissions(LocalDate payrollMonth) {
        List<CommissionRecord> records = commissions.findApprovedRecordsByMonth(payrollMonth);
        Map<Long, RepAccumulator> grouped = new LinkedHashMap<>();
        // Manual entries (ADJUSTMENT/MANAGER/STOCK_BONUS/INCENTIVE, feat/commission-manual-adjustments)
        // never feed the tier calc -- accumulated separately here and added to each rep's FINAL
        // commission total only, on top of the tier commission, never into commissionableBase
        // itself. Only APPROVED records reach this point at all (findApprovedRecordsByMonth), so a
        // manual entry still sitting at MANAGER_APPROVED correctly does not count yet.
        Map<Long, BigDecimal> manualTotals = new LinkedHashMap<>();
        Map<Long, String> manualOnlyRepNames = new LinkedHashMap<>();
        for (CommissionRecord record : records) {
            if (MANUAL_KINDS.contains(record.kind())) {
                BigDecimal amount = record.manualAmount() == null ? BigDecimal.ZERO : record.manualAmount();
                manualTotals.merge(record.salesRepId(), amount, BigDecimal::add);
                manualOnlyRepNames.putIfAbsent(record.salesRepId(), record.salesRepName());
                continue;
            }
            // Commission redesign calc-refine: accumulate the WEIGHTED actual-received (real cash
            // x weight_multiplier), not the already-2dp-rounded commissionable_base column — see
            // CommissionRepository#sumActiveWeightedActualReceived for the same change at the
            // single-rep query path (simulate()).
            grouped.computeIfAbsent(record.salesRepId(), id -> new RepAccumulator(record.salesRepId(), record.salesRepName()))
                .add(record.actualReceived(), record.weightMultiplier());
        }
        List<TierConfig> tiers = tiers();
        List<RepPayrollCommission> results = new ArrayList<>();
        Set<Long> repIds = new HashSet<>();
        for (RepAccumulator rep : grouped.values()) {
            BigDecimal safeWeightedActualReceived = rep.weightedActualReceived.max(BigDecimal.ZERO);
            // Full precision here (not rounded to 2dp) — only the final commission total rounds.
            BigDecimal safeBase = calculator.monthlyTierBase(safeWeightedActualReceived);
            BigDecimal tierCommission = calculator.progressiveCommission(safeBase, tiers);
            BigDecimal displayBase = safeBase.setScale(2, RoundingMode.HALF_UP);
            BigDecimal manualAmount = manualTotals.getOrDefault(rep.salesRepId, BigDecimal.ZERO);
            BigDecimal finalCommission = tierCommission.add(manualAmount);
            results.add(new RepPayrollCommission(rep.salesRepId, rep.salesRepName, displayBase, manualAmount, finalCommission));
            repIds.add(rep.salesRepId);
        }
        // A rep whose ONLY approved commission this month is a manual entry (e.g. a MANAGER
        // commission for someone with no SALE commission yet) still needs a row: tier base/tier
        // commission are zero, the manual amount is the whole total.
        for (Map.Entry<Long, BigDecimal> entry : manualTotals.entrySet()) {
            if (repIds.contains(entry.getKey())) {
                continue;
            }
            BigDecimal manualAmount = entry.getValue();
            results.add(new RepPayrollCommission(
                entry.getKey(), manualOnlyRepNames.get(entry.getKey()),
                BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP), manualAmount, manualAmount));
        }
        return results;
    }

    /**
     * Map view of {@link #computeRepPayrollCommissions} for callers -- {@code
     * PayrollService#commissionPayByEmployee} -- that only need the final payable total per rep,
     * not the full base/manual breakdown {@link #payrollReadySummary} displays.
     *
     * @param payrollMonth must already be normalized to the 1st of the month (payroll always
     *                     passes an already-normalized month here; see {@code
     *                     PayrollService#normalizeMonth}).
     */
    public Map<Long, BigDecimal> payrollCommissionTotalsByEmployee(LocalDate payrollMonth) {
        Map<Long, BigDecimal> totals = new LinkedHashMap<>();
        for (RepPayrollCommission rep : computeRepPayrollCommissions(payrollMonth)) {
            totals.put(rep.salesRepId(), rep.totalCommission());
        }
        return totals;
    }

    /**
     * Per-rep breakdown shared between {@link #payrollReadySummary} and {@link
     * #payrollCommissionTotalsByEmployee} -- {@code totalCommission} is the number payroll must
     * pay: {@code tierCommissionableBase} run through the tier table, plus {@code
     * manualAdjustmentAmount}.
     */
    public record RepPayrollCommission(
        long salesRepId,
        String salesRepName,
        BigDecimal tierCommissionableBase,
        BigDecimal manualAdjustmentAmount,
        BigDecimal totalCommission) {}

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
        // withholdingTax/overpayment (Slice A1) are treated like bankFees/suspenseVat, not like
        // transportFee/cutFee/shortfall: they come off the invoice/payment document itself
        // (WHT certificate, overpayment received) rather than being a managerial deduction, so
        // whoever submits the invoice may enter them. ASSUMPTION flagged for owner confirmation
        // in the handoff — if WHT should instead be manager-gated like the three deduction
        // fields, move it into the salesCannotEditDeductions branch.
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
            salesCannotEditDeductions ? BigDecimal.ZERO : money(request.shortfall()),
            money(request.withholdingTax()),
            money(request.overpayment())
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

    private int valueOrExisting(Integer value, int existing) {
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

    /**
     * Null-safe description of a commission record for notification text. Manual entries (kind
     * ADJUSTMENT/MANAGER, V84) have no invoice -- {@code record.invoiceDetails()} is {@code null}
     * for them (see {@code CommissionRepository#mapInvoiceDetails}), so every notify* helper below
     * must go through this rather than calling {@code invoiceDetails().invoiceNumber()} directly.
     */
    private String describeRecord(CommissionRecord record) {
        if (record.invoiceDetails() != null) {
            return "ใบกำกับ " + record.invoiceDetails().invoiceNumber();
        }
        String kindLabel = CommissionKind.MANAGER.equals(record.kind()) ? "ค่าคอมผู้จัดการ" : "รายการปรับปรุงค่าคอม";
        return kindLabel + (record.manualReason() == null ? "" : " (" + record.manualReason() + ")");
    }

    private void notifySubmitted(CommissionRecord record) {
        String description = describeRecord(record);
        notificationService.notify(
            record.salesRepId(),
            "COMMISSION_SUBMITTED",
            "ส่งคำขอค่าคอมแล้ว",
            "คำขอค่าคอม" + description + " ถูกส่งให้ผู้จัดการฝ่ายขายตรวจสอบแล้ว",
            "/commissions",
            true
        );
        for (Long managerEmployeeId : commissions.findSalesManagerApproverEmployeeIds()) {
            notificationService.notify(
                managerEmployeeId,
                "COMMISSION_PENDING_MANAGER",
                "มีคำขอค่าคอมรออนุมัติ",
                record.salesRepName() + " ส่งคำขอค่าคอม" + description,
                "/commissions",
                true
            );
        }
    }

    private void notifyManagerApproved(CommissionRecord record) {
        String description = describeRecord(record);
        notificationService.notify(
            record.salesRepId(),
            "COMMISSION_MANAGER_APPROVED",
            "ผู้จัดการอนุมัติค่าคอมแล้ว",
            "คำขอค่าคอม" + description + " ผ่านผู้จัดการแล้ว และรอ CEO อนุมัติขั้นสุดท้าย",
            "/commissions",
            true
        );
        for (Long ceoEmployeeId : commissions.findCeoApproverEmployeeIds()) {
            notificationService.notify(
                ceoEmployeeId,
                "COMMISSION_PENDING_CEO",
                "มีคำขอค่าคอมรอ CEO อนุมัติ",
                record.salesRepName() + " มีคำขอค่าคอม" + description + " ที่ผู้จัดการอนุมัติแล้ว",
                "/commissions",
                true
            );
        }
    }

    private void notifyCeoApproved(CommissionRecord record) {
        String description = describeRecord(record);
        notificationService.notify(
            record.salesRepId(),
            "COMMISSION_APPROVED",
            "CEO อนุมัติค่าคอมแล้ว",
            "คำขอค่าคอม" + description + " อนุมัติครบถ้วนแล้ว",
            "/commissions",
            true
        );
        if (record.managerApprovedBy() != null) {
            notificationService.notify(
                record.managerApprovedBy(),
                "COMMISSION_APPROVED",
                "CEO อนุมัติค่าคอมแล้ว",
                record.salesRepName() + " ได้รับการอนุมัติค่าคอม" + description,
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
            "คำขอค่าคอม" + describeRecord(record) + " ถูกปฏิเสธ: "
                + (record.rejectionReason() == null ? "กรุณาติดต่อผู้อนุมัติ" : record.rejectionReason()),
            "/commissions",
            true
        );
    }

    private void notifyManualCreated(CommissionRecord record) {
        String description = describeRecord(record);
        boolean fullyApproved = CommissionStatus.APPROVED.equals(record.status());
        notificationService.notify(
            record.salesRepId(),
            fullyApproved ? "COMMISSION_APPROVED" : "COMMISSION_MANAGER_APPROVED",
            fullyApproved ? "CEO เพิ่มค่าคอมให้" : "มีการเพิ่มค่าคอมให้คุณ",
            description + " จำนวน " + record.manualAmount() + " บาท ถูกเพิ่มเข้าค่าคอมของคุณแล้ว"
                + (fullyApproved ? "" : " และรอ CEO อนุมัติขั้นสุดท้าย"),
            "/commissions",
            true
        );
        if (!fullyApproved) {
            for (Long ceoEmployeeId : commissions.findCeoApproverEmployeeIds()) {
                notificationService.notify(
                    ceoEmployeeId,
                    "COMMISSION_PENDING_CEO",
                    "มีรายการค่าคอมแบบ manual รออนุมัติ",
                    record.salesRepName() + " มี" + description + " ที่ผู้จัดการเพิ่มแล้ว รอ CEO อนุมัติ",
                    "/commissions",
                    true
                );
            }
        }
    }

    private LocalDate payrollMonth(LocalDate date) {
        return date.withDayOfMonth(1);
    }

    /**
     * FLAG-10 (2026-07-23, owner-confirmed against reconciled real May 2026 accountant data):
     * commission on money received in month M is paid in payroll month M+1, flat -- {@code
     * invoiceDate} is the received date. Used ONLY at the two SALE-commission creation sites
     * ({@link #submit} / {@link #createFromDeal}). Do NOT use this for the HR-picked manual-entry
     * month ({@link #createManualCommission}) or the {@link #payrollReadySummary} query month --
     * both of those are already expressed in target-payroll-month terms and must go through the
     * plain {@link #payrollMonth(LocalDate)} normalizer instead, or a manual entry would be
     * shifted an extra, wrong month.
     */
    private LocalDate saleCommissionPayrollMonth(LocalDate invoiceDate) {
        return invoiceDate.withDayOfMonth(1).plusMonths(1);
    }

    private LocalDate currentPayrollMonth() {
        return LocalDate.now(BUSINESS_ZONE).withDayOfMonth(1);
    }

    private static final class RepAccumulator {
        private final long salesRepId;
        private final String salesRepName;
        // Commission redesign calc-refine: the tier-base aggregate, at full precision (real cash
        // x weight_multiplier per record, summed — VAT division happens once, later).
        private BigDecimal weightedActualReceived = BigDecimal.ZERO;
        // Real cash received, unweighted — kept separate from weightedActualReceived so "real
        // cash received" and "weighted tier base" never get conflated. Not currently surfaced in
        // PayrollCommissionSummaryDto (no consumer yet); tracked here for a future team-commission
        // slice, same rationale as CommissionRepository#sumActiveActualReceived.
        private BigDecimal unweightedActualReceived = BigDecimal.ZERO;

        private RepAccumulator(long salesRepId, String salesRepName) {
            this.salesRepId = salesRepId;
            this.salesRepName = salesRepName;
        }

        private void add(BigDecimal actualReceived, int weightMultiplier) {
            BigDecimal received = actualReceived == null ? BigDecimal.ZERO : actualReceived;
            unweightedActualReceived = unweightedActualReceived.add(received);
            weightedActualReceived = weightedActualReceived.add(received.multiply(BigDecimal.valueOf(weightMultiplier)));
        }
    }
}
