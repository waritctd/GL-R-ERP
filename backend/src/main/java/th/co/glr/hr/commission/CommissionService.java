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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if ("sales".equals(actor.role()) && hasDeductionOverride(request)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ฝ่ายขายไม่มีสิทธิ์แก้ไขช่องรายการหักเงิน");
        }
        if (invoiceAttachment == null || invoiceAttachment.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องแนบไฟล์ใบกำกับภาษี");
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
        // Commission-closed-month guard: computed and checked on the DERIVED, normalized
        // first-of-month value that createCommissionRecord below actually writes -- not on the raw
        // caller-supplied invoiceDate. Runs before any write (invoice row, file storage, attachment)
        // so a refused submission leaves nothing behind.
        LocalDate payrollMonth = saleCommissionPayrollMonth(safeRequest.invoiceDate());
        requireCommissionPayrollMonthOpen(payrollMonth, safeRequest.invoiceDate());

        try {
            long invoiceId = commissions.createInvoice(safeRequest);
            // V134 storage-durability fix -- TRANSITIONAL dual-write: bytes go to
            // hr.file_attachment_blob (via saveWithContent below) AND the file still goes to disk
            // (via store, unchanged) because the SAME file_path string is also registered into
            // sales.attachment, which is out of this branch's scope and still disk-only. See
            // CommissionAttachmentRepository#saveWithContent's javadoc for the full rationale and
            // the follow-up this leaves behind.
            FileStorageService.StoredFile storedFile = fileStorage.store(
                "commission-invoice",
                invoiceId,
                invoiceAttachment,
                COMMISSION_INVOICE_MIME_TYPES
            );
            // The disk copy above is NOT part of this transaction — Postgres cannot undo it. Every
            // write below (attachment row, attachInvoiceFile, commission record, audit) can still
            // fail and roll the whole method back, which would leave that file on disk forever with
            // no row referencing it. deleteOnRollback ties it to the transaction's outcome; on the
            // success path it does nothing at all. See its Javadoc for why the write stays here
            // rather than being hoisted out of the transaction (invoiceId is generated above, in
            // this same transaction).
            fileStorage.deleteOnRollback(storedFile);
            long attachmentId = commissionAttachments.saveWithContent(
                invoiceId,
                storedFile.fileName(),
                storedFile.filePath(),
                storedFile.mimeType(),
                storedFile.fileSize(),
                actor.id(),
                invoiceContentBytes(invoiceAttachment)
            );
            commissions.attachInvoiceFile(invoiceId, attachmentId);
            long commissionId = commissions.createCommissionRecord(
                invoiceId,
                safeRequest.sourceTicketId(),
                salesRepId,
                actor.id(),
                payrollMonth,
                calculation,
                linkage.payableSnapshot(),
                linkage.mismatch()
            );
            CommissionRecord created = requireRecord(commissionId);
            auditService.record(actor, "SUBMIT_COMMISSION", "commission_record", commissionId, null, created);
            notifySubmitted(created);
            return created;
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "เลขที่ใบกำกับภาษีนี้มีอยู่ในระบบแล้ว");
        }
    }

    /**
     * V134 storage-durability fix: reads the SAME upload's bytes a second time for the database
     * dual-write, after {@code fileStorage.store} already validated/cap-checked and wrote it to
     * disk. Safe to call twice on one {@code MultipartFile} -- both Spring's real servlet-backed
     * implementation and {@code MockMultipartFile} (used throughout this codebase's tests) support
     * repeated reads, since the upload is already fully spooled (to a temp file or an in-memory
     * buffer) by the time either read happens.
     */
    private byte[] invoiceContentBytes(MultipartFile invoiceAttachment) {
        try {
            return invoiceAttachment.getBytes();
        } catch (java.io.IOException exception) {
            throw new java.io.UncheckedIOException(exception);
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (invoiceAttachment == null || invoiceAttachment.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องแนบไฟล์ใบกำกับภาษี");
        }
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        long salesRepId = ticket.summary().createdById();
        if (commissions.hasActiveCommissionForTicket(ticketId)) {
            throw new ApiException(HttpStatus.CONFLICT, "มีรายการค่าคอมมิชชั่นสำหรับดีลนี้อยู่แล้ว");
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
        // Commission-closed-month guard: same guard and same placement rationale as submit() above
        // -- computed on the DERIVED, normalized first-of-month value, checked before any write
        // (invoice row, file storage, ticket attachment) so a refused create leaves nothing behind,
        // including no spurious invoiceOnFile flip on the ticket.
        LocalDate payrollMonth = saleCommissionPayrollMonth(request.invoiceDate());
        requireCommissionPayrollMonthOpen(payrollMonth, request.invoiceDate());

        try {
            long invoiceId = commissions.createInvoice(request);
            // V134 storage-durability fix -- TRANSITIONAL dual-write, same rationale as #submit
            // above: see CommissionAttachmentRepository#saveWithContent's javadoc.
            FileStorageService.StoredFile storedFile = fileStorage.store(
                "commission-invoice",
                invoiceId,
                invoiceAttachment,
                COMMISSION_INVOICE_MIME_TYPES
            );
            // Same non-transactional disk write as #submit above, with one more row riding on it:
            // this path also registers the SAME file_path into sales.attachment below, so a
            // rollback here strands the one physical file that two separate rows pointed at.
            // Registered once — the file is one file, and deleting it once undoes both references.
            fileStorage.deleteOnRollback(storedFile);
            long attachmentId = commissionAttachments.saveWithContent(
                invoiceId,
                storedFile.fileName(),
                storedFile.filePath(),
                storedFile.mimeType(),
                storedFile.fileSize(),
                actor.id(),
                invoiceContentBytes(invoiceAttachment)
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
                payrollMonth,
                calculation,
                linkage.payableSnapshot(),
                linkage.mismatch()
            );
            CommissionRecord created = requireRecord(commissionId);
            auditService.record(actor, "CREATE_COMMISSION_FROM_DEAL", "commission_record", commissionId, null, created);
            notifySubmitted(created);
            return created;
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "เลขที่ใบกำกับภาษีนี้มีอยู่ในระบบแล้ว");
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
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
        if (!DealStage.CLOSED_PAID.equals(salesStage)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ดีลนี้ยังไม่ถึงขั้นตอนรับชำระเงินครบถ้วน (CLOSED_PAID) จึงยังยื่นค่าคอมมิชชั่นไม่ได้");
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
            throw new ApiException(HttpStatus.CONFLICT, "ไม่สามารถแก้ไขรายการค่าคอมมิชชั่นที่ถูกยกเลิกแล้วได้");
        }
        if (MANUAL_KINDS.contains(existing.kind())) {
            // Manual entries (ADJUSTMENT/MANAGER) have no invoice_details row to edit -- existing
            // .invoiceDetails() is null for them (V84). There is nothing here to update; the
            // manual_amount/manual_reason a manual entry was created with is immutable by design
            // (create a fresh entry, or use the clawback-style correction pattern, if it was wrong).
            throw new ApiException(HttpStatus.CONFLICT, "รายการค่าคอมมิชชั่นแบบกรอกเองไม่มีรายการหักจากใบกำกับภาษีให้แก้ไข");
        }
        // P0 fix (fix/commission-approved-record-immutable): a CLAWBACK record shares its
        // invoice_id with the ORIGINAL sale it reverses (CommissionRepository#createClawback
        // inserts the clawback row with invoice_id = original.invoiceDetails().id()) -- editing one
        // through its own id rewrites that shared invoice_details row, and
        // updateCommissionAmountsForInvoice's `WHERE invoice_id = :invoiceId` then rewrites the
        // ORIGINAL sale's actual_received/commissionable_base too, through a completely different
        // id. That mechanism is why a clawback's amount must never be hand-edited: it is always
        // derived (negated) from the original at creation time -- a KIND invariant, true regardless
        // of status. This check is redundant with the APPROVED check below today (a clawback is
        // always created APPROVED and has no path back to SUBMITTED/MANAGER_APPROVED, so that check
        // alone already stops the write) -- it is checked FIRST not because it is the only thing
        // preventing the corruption above, but so the refusal names the real, permanent reason
        // ("this id is a clawback") instead of a status coincidence a future reader could mistake
        // for "editable again once un-approved", and so the guard survives unchanged if anyone ever
        // adds a review stage before a clawback is finalized.
        if (CommissionKind.CLAWBACK.equals(existing.kind())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "รายการเรียกคืนค่าคอมมิชชั่นคำนวณจากรายการต้นทางโดยอัตโนมัติ ไม่สามารถแก้ไขได้โดยตรง");
        }
        // P0 fix: a fully APPROVED record has already been signed off by the CEO and is in range
        // for payroll (CommissionRepository#findApprovedRecordsByMonth filters status = 'APPROVED'
        // only, feeding #payrollCommissionTotalsByEmployee) -- it may already be reflected in a
        // filed payroll run. There is no route back to SUBMITTED/MANAGER_APPROVED for re-review, so
        // an unguarded edit here would recompute the tier commission from new numbers with no
        // re-approval by anyone, including the CEO who approved the original figure. The sanctioned
        // way to correct an approved record is createClawback, which books a new, separately-
        // audited, separately payroll-month-guarded record instead of mutating one that already
        // cleared review.
        if (CommissionStatus.APPROVED.equals(existing.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "รายการค่าคอมมิชชั่นที่อนุมัติแล้วไม่สามารถแก้ไขได้ กรุณาใช้การเรียกคืนค่าคอมมิชชั่นแทน");
        }
        // Commission-closed-month guard: matches every other write against sales.commission_record
        // (see requireCommissionPayrollMonthOpen's Javadoc -- this is the seventh call site, an
        // omission the guard's own Javadoc used to undercount as "six"). Guards the record's own
        // payrollMonth, the same value managerApprove/ceoApprove guard, even though only APPROVED
        // (already refused above) is counted toward payroll: a SUBMITTED/MANAGER_APPROVED record
        // whose month has since closed can never be approved either (both approval-stage guards
        // would refuse that transition), so silently letting its numbers be edited is dead work at
        // best and a confusing, unaudited-looking edit to a closed month's data at worst.
        requireCommissionPayrollMonthOpen(existing.payrollMonth());
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
        throw new ApiException(HttpStatus.CONFLICT, "รายการค่าคอมมิชชั่นนี้ได้รับการพิจารณาไปแล้ว");
    }

    private CommissionRecord managerApprove(long id, UserPrincipal actor, CommissionRecord existing) {
        requireManager(actor);
        // Commission-closed-month guard (reviewer finding F3): a commission created while its
        // payroll month was still open can still reach approval after that month closes underneath
        // it -- the same seam OvertimeService guards at both its manager- and CEO-approval stages,
        // not only at submit. Guards on the RECORD's own payrollMonth, not today's date.
        requireCommissionPayrollMonthOpen(existing.payrollMonth());
        commissions.managerApprove(id, actor.id());
        CommissionRecord after = requireRecord(id);
        auditService.record(actor, "MANAGER_APPROVE_COMMISSION", "commission_record", id, existing, after);
        notifyManagerApproved(after);
        return after;
    }

    private CommissionRecord ceoApprove(long id, UserPrincipal actor, CommissionRecord existing) {
        requireCeo(actor);
        // Commission-closed-month guard (reviewer finding F3) -- same rationale as managerApprove
        // above: guards the record's own payrollMonth, which may have closed while it waited here.
        requireCommissionPayrollMonthOpen(existing.payrollMonth());
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
        throw new ApiException(HttpStatus.CONFLICT, "รายการค่าคอมมิชชั่นนี้ได้รับการพิจารณาไปแล้ว");
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
            throw new ApiException(HttpStatus.CONFLICT, "เรียกคืนได้เฉพาะค่าคอมมิชชั่นประเภทการขายที่อนุมัติแล้วเท่านั้น");
        }
        if (commissions.hasActiveClawbackFor(id)) {
            throw new ApiException(HttpStatus.CONFLICT, "รายการค่าคอมมิชชั่นนี้มีการเรียกคืนที่ยังดำเนินการอยู่แล้ว");
        }
        // Commission-closed-month guard (reviewer finding F2): createClawback is a fourth INSERT
        // path into sales.commission_record, easy to miss because it always targets the CURRENT
        // month, not a caller-supplied one. But the current month can itself already be PROCESSED
        // (prod marked July 2026 PROCESSED on 23 Jul, mid-month) -- an unguarded clawback filed
        // after that point would take a payroll_month that is already processed, so the negative
        // row is never deducted and it silently reduces a processed month's tier base, the wrong
        // direction financially (the rep keeps money that should have been clawed back).
        LocalDate clawbackPayrollMonth = currentPayrollMonth();
        requireCommissionPayrollMonthOpen(clawbackPayrollMonth);
        long clawbackId = commissions.createClawback(
            original,
            actor.id(),
            clawbackPayrollMonth,
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
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะผู้จัดการฝ่ายขายหรือ CEO เท่านั้นที่สามารถสร้างรายการค่าคอมมิชชั่นแบบกรอกเองได้");
        }
        if (salesRepId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุรหัสพนักงานขาย");
        }
        if (!MANUAL_KINDS.contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "kind ต้องเป็นหนึ่งใน " + String.join(", ", new java.util.TreeSet<>(MANUAL_KINDS)));
        }
        if (amount == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุจำนวนเงิน");
        }
        if (reason == null || reason.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุเหตุผลสำหรับรายการค่าคอมมิชชั่นแบบกรอกเอง");
        }
        // A MANAGER-kind entry represents team/manager commission earned, not a correction --
        // never negative. An ADJUSTMENT may legitimately be negative (a deduction/clawback-style
        // correction), so no sign restriction there.
        if (CommissionKind.MANAGER.equals(kind) && amount.signum() < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "รายการค่าคอมมิชชั่นประเภท MANAGER ต้องไม่ติดลบ");
        }
        LocalDate month = payrollMonth == null ? currentPayrollMonth() : payrollMonth(payrollMonth);
        // Commission-closed-month guard: month here is already the normalized first-of-month value
        // that commissions.createManualCommission below actually writes.
        requireCommissionPayrollMonthOpen(month);
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ฝ่ายขายไม่มีสิทธิ์แก้ไขช่องรายการหักเงิน");
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
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        LocalDate month = payrollMonth == null ? currentPayrollMonth() : payrollMonth(payrollMonth);
        List<RepPayrollCommission> reps = computeRepPayrollCommissions(month);
        List<SalesRepCommissionSummaryDto> repSummaries = new ArrayList<>();
        BigDecimal totalBase = BigDecimal.ZERO;
        BigDecimal totalCommission = BigDecimal.ZERO;
        BigDecimal totalIncentive = BigDecimal.ZERO;
        BigDecimal totalStockBonus = BigDecimal.ZERO;
        for (RepPayrollCommission rep : reps) {
            repSummaries.add(new SalesRepCommissionSummaryDto(
                rep.salesRepId(), rep.salesRepName(), rep.tierCommissionableBase(), rep.totalCommission(),
                rep.manualAdjustmentAmount(), rep.incentiveAmount(), rep.stockBonusAmount()));
            totalBase = totalBase.add(rep.tierCommissionableBase());
            totalCommission = totalCommission.add(rep.totalCommission());
            totalIncentive = totalIncentive.add(rep.incentiveAmount());
            totalStockBonus = totalStockBonus.add(rep.stockBonusAmount());
        }
        repSummaries.sort(Comparator.comparing(SalesRepCommissionSummaryDto::salesRepName, Comparator.nullsLast(String::compareTo)));
        return new PayrollCommissionSummaryDto(
            month, "PAYROLL_READY", totalBase, totalCommission, totalIncentive, totalStockBonus, repSummaries);
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
        // Issue #405 transition safeguard, REWORKED (2026-08-02 review): a rep-month's approved
        // manual INCENTIVE/STOCK_BONUS entries are tracked here as a SUMMED AMOUNT per kind, not
        // just "does one exist" -- because "does one exist" mis-fired on a zero/negative manual
        // entry. A manual entry of a given kind can mean two different things and they must be
        // treated oppositely:
        //   - POSITIVE amount: a hand-typed REPLACEMENT (the pre-#405 way of doing this whole
        //     kind by hand) -- a human decided the figure for that kind that month, so it wins
        //     and the auto-computed limb is suppressed entirely.
        //   - ZERO or NEGATIVE amount: a CORRECTION layered on top of the auto-computed figure
        //     (e.g. "-5,000, overpaid last cycle's incentive") -- the auto limb must still
        //     compute normally, and the correction is added on top via manualAmount below, same
        //     as it already is for ADJUSTMENT. Suppressing the auto limb here would silently turn
        //     a -5,000 correction into a -5,000 total instead of (auto + -5,000).
        // A rep with no manual entry of that kind defaults to ZERO here, which correctly does NOT
        // suppress (grouped.getOrDefault(...).signum() > 0 is false for zero).
        Map<Long, BigDecimal> manualIncentiveTotals = new LinkedHashMap<>();
        Map<Long, BigDecimal> manualStockBonusTotals = new LinkedHashMap<>();
        for (CommissionRecord record : records) {
            if (MANUAL_KINDS.contains(record.kind())) {
                BigDecimal amount = record.manualAmount() == null ? BigDecimal.ZERO : record.manualAmount();
                manualTotals.merge(record.salesRepId(), amount, BigDecimal::add);
                manualOnlyRepNames.putIfAbsent(record.salesRepId(), record.salesRepName());
                if (CommissionKind.INCENTIVE.equals(record.kind())) {
                    manualIncentiveTotals.merge(record.salesRepId(), amount, BigDecimal::add);
                }
                if (CommissionKind.STOCK_BONUS.equals(record.kind())) {
                    manualStockBonusTotals.merge(record.salesRepId(), amount, BigDecimal::add);
                }
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
        // Issue #405: load the INCENTIVE ladder + STOCK_BONUS config ONCE for the whole month
        // (not per rep) -- both are config rows that never vary by rep, and this is also the
        // single place both payrollReadySummary and payrollCommissionTotalsByEmployee (via this
        // shared method) read them from, so the two callers can never diverge.
        List<IncentiveTierConfig> incentiveLadder = commissions.findIncentiveTiers(payrollMonth);
        StockBonusConfig stockBonusConfig = commissions.findStockBonusConfig(payrollMonth).orElse(StockBonusConfig.disabled());
        BigDecimal zero = BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
        List<RepPayrollCommission> results = new ArrayList<>();
        Set<Long> repIds = new HashSet<>();
        for (RepAccumulator rep : grouped.values()) {
            BigDecimal safeWeightedActualReceived = rep.weightedActualReceived.max(BigDecimal.ZERO);
            // Full precision here (not rounded to 2dp) — only the final commission total rounds.
            BigDecimal safeBase = calculator.monthlyTierBase(safeWeightedActualReceived);
            BigDecimal tierCommission = calculator.progressiveCommission(safeBase, tiers);
            BigDecimal displayBase = safeBase.setScale(2, RoundingMode.HALF_UP);
            BigDecimal manualAmount = manualTotals.getOrDefault(rep.salesRepId, BigDecimal.ZERO);
            // Suppress the auto limb only when the rep's summed manual entries of that kind are
            // STRICTLY POSITIVE (a replacement) -- zero/negative (a correction) leaves the auto
            // limb computing normally; see the map-building comment above for the full rationale.
            boolean incentiveReplaced = manualIncentiveTotals.getOrDefault(rep.salesRepId, BigDecimal.ZERO).signum() > 0;
            BigDecimal incentiveAmount = incentiveReplaced
                ? zero
                : calculator.monthlyIncentive(safeBase, incentiveLadder);
            boolean stockBonusReplaced = manualStockBonusTotals.getOrDefault(rep.salesRepId, BigDecimal.ZERO).signum() > 0;
            // Review fix (performance): short-circuit before the repo call (a GROUP BY ticket_id
            // aggregate over the whole sales.ticket_item table) when there is nothing to compute
            // -- config disabled, or the auto limb is replaced by a positive manual entry. Without
            // this, computeRepPayrollCommissions ran that full aggregate once per rep on every
            // payroll run, including every historic month, even though STOCK_BONUS ships
            // config-gated OFF and would resolve to zero anyway.
            BigDecimal stockBonusAmount = (stockBonusReplaced || !stockBonusConfig.enabled())
                ? zero
                : calculator.stockSaleBonus(commissions.sumActiveStockActualReceived(rep.salesRepId, payrollMonth), stockBonusConfig);
            BigDecimal finalCommission = tierCommission.add(incentiveAmount).add(stockBonusAmount).add(manualAmount);
            results.add(new RepPayrollCommission(
                rep.salesRepId, rep.salesRepName, displayBase, manualAmount, incentiveAmount, stockBonusAmount, finalCommission));
            repIds.add(rep.salesRepId);
        }
        // A rep whose ONLY approved commission this month is a manual entry (e.g. a MANAGER
        // commission for someone with no SALE commission yet) still needs a row: tier base/tier
        // commission are zero, the manual amount is the whole total. Issue #405: their auto-computed
        // incentive/stock-bonus are kept explicitly zero too -- a zero tier base already resolves
        // both calculator calls to zero on their own (monthlyIncentive/stockSaleBonus both treat a
        // non-positive base/receipts as zero), but this branch has no safeBase to feed them and
        // zero manual-only reps never earn a SALE-driven auto-computed limb in the first place.
        for (Map.Entry<Long, BigDecimal> entry : manualTotals.entrySet()) {
            if (repIds.contains(entry.getKey())) {
                continue;
            }
            BigDecimal manualAmount = entry.getValue();
            results.add(new RepPayrollCommission(
                entry.getKey(), manualOnlyRepNames.get(entry.getKey()),
                zero, manualAmount, zero, zero, manualAmount));
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
     * incentiveAmount}, {@code stockBonusAmount} (issue #405), plus {@code
     * manualAdjustmentAmount}.
     */
    public record RepPayrollCommission(
        long salesRepId,
        String salesRepName,
        BigDecimal tierCommissionableBase,
        BigDecimal manualAdjustmentAmount,
        BigDecimal incentiveAmount,
        BigDecimal stockBonusAmount,
        BigDecimal totalCommission) {}

    private CommissionRecord requireRecord(long id) {
        return commissions.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการค่าคอมมิชชั่นนี้"));
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
                throw new ApiException(HttpStatus.FORBIDDEN, "พนักงานขายสามารถยื่นค่าคอมมิชชั่นของตนเองเท่านั้น");
            }
            return actor.id();
        }
        return requestedSalesRepId == null ? actor.id() : requestedSalesRepId;
    }

    private void requireManager(UserPrincipal actor) {
        if (!MANAGER_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะผู้จัดการฝ่ายขายเท่านั้นที่สามารถพิจารณาค่าคอมมิชชั่นที่ยื่นเข้ามาได้");
        }
    }

    private void requireCeo(UserPrincipal actor) {
        if (!CEO_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะ CEO เท่านั้นที่สามารถพิจารณาค่าคอมมิชชั่นที่ผู้จัดการฝ่ายขายอนุมัติแล้วได้");
        }
    }

    private void requireManagerOrCeo(UserPrincipal actor) {
        if (!MANAGER_ROLES.contains(actor.role()) && !CEO_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
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

    /**
     * Refuses to write/advance a commission against a payroll month that can never be paid, for
     * either of two distinct reasons -- the person seeing the error needs to tell them apart, so
     * each gets its own Thai message. Mirrors {@code OvertimeService#requirePayrollMonthOpen}
     * exactly (same two facts, same two repository checks, same posture) -- see that method's
     * Javadoc for the full rationale on why a seed-covered month is never {@code PROCESSED} and
     * therefore needs its own check.
     *
     * <p>Owner decision (commission-closed-month-guard plan): REFUSE (409), never roll forward.
     * Rolling a commission's payroll month forward to the next open month would change which month
     * it is attributed to -- that is commission ATTRIBUTION logic, which CLAUDE.md protects as
     * business logic. This guard only ever blocks a write; it never changes where a commission
     * lands. Not a commission math change: no tier, rate, band, weighting, {@code
     * payrollReadySummary} figure, or the M+1 rule is touched here.
     *
     * <p>Call this on the already-normalized, first-of-month value that will actually be
     * written/read -- never on a raw caller-supplied date. Guarding the raw input would let a
     * mid-month date slip past a first-of-month comparison. Seven call sites, all of which write or
     * advance {@code sales.commission_record}: the four creation paths -- {@link
     * #createManualCommission} (on the resolved {@code month}), {@link #submit}/{@link
     * #createFromDeal} (on {@link #saleCommissionPayrollMonth(LocalDate)}'s M+1-derived result --
     * use the {@link #requireCommissionPayrollMonthOpen(LocalDate, LocalDate)} overload there so
     * the refusal names the invoice date too), and {@link #createClawback} (on {@code
     * currentPayrollMonth()} -- a clawback filed after the current month is marked PROCESSED
     * mid-month, as happened on prod 23 Jul 2026, must not silently land in it) -- the two
     * approval stages, {@link #managerApprove(long, UserPrincipal, CommissionRecord)} and {@link
     * #ceoApprove(long, UserPrincipal, CommissionRecord)} (on the existing record's {@code
     * payrollMonth()}): a commission created while its month was open can still be approved after
     * the month closes underneath it, exactly the seam {@code OvertimeService} already guards at
     * both its manager- and CEO-approval stages -- and {@link #updateDeductions} (on the existing
     * record's {@code payrollMonth()}, added by fix/commission-approved-record-immutable: originally
     * missing here entirely, which is how a manager could edit a SUBMITTED/MANAGER_APPROVED
     * record's numbers for a month that could then never be approved into payroll anyway).
     */
    private void requireCommissionPayrollMonthOpen(LocalDate payrollMonth) {
        if (commissions.payrollMonthProcessed(payrollMonth)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "งวดเงินเดือน " + payrollMonthLabel(payrollMonth)
                    + " ได้ประมวลผลไปแล้ว จึงไม่สามารถสร้างหรือพิจารณารายการค่าคอมมิชชั่นในงวดนี้ได้อีก"
            );
        }
        if (commissions.payrollMonthSeedCovered(payrollMonth)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "งวดเงินเดือน " + payrollMonthLabel(payrollMonth)
                    + " ถูกจ่ายนอกระบบไปแล้วและได้ยื่นแบบภาษีแล้ว จึงไม่สามารถสร้างหรือพิจารณารายการค่าคอมมิชชั่นในงวดนี้ได้"
            );
        }
    }

    /**
     * SALE-path variant used only by {@link #submit} and {@link #createFromDeal}: {@code
     * payrollMonth} there is DERIVED from {@code invoiceDate} via the M+1 rule ({@link
     * #saleCommissionPayrollMonth(LocalDate)}), so naming only the derived month left the caller
     * unable to tell why the date they typed produced that refusal (e.g. a 2026-05-15 invoice
     * refused over payroll month 2026-06 -- reviewer finding F4). Names both the invoice date and
     * the derived month; same two underlying checks and messages as the single-argument overload.
     */
    private void requireCommissionPayrollMonthOpen(LocalDate payrollMonth, LocalDate invoiceDate) {
        if (commissions.payrollMonthProcessed(payrollMonth)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "ใบกำกับภาษีลงวันที่ " + invoiceDate + " ตกในงวดเงินเดือน " + payrollMonthLabel(payrollMonth)
                    + " (ตามกฎ M+1) ซึ่งได้ประมวลผลไปแล้ว จึงไม่สามารถสร้างรายการค่าคอมมิชชั่นนี้ได้"
            );
        }
        if (commissions.payrollMonthSeedCovered(payrollMonth)) {
            throw new ApiException(
                HttpStatus.CONFLICT,
                "ใบกำกับภาษีลงวันที่ " + invoiceDate + " ตกในงวดเงินเดือน " + payrollMonthLabel(payrollMonth)
                    + " (ตามกฎ M+1) ซึ่งถูกจ่ายนอกระบบไปแล้วและได้ยื่นแบบภาษีแล้ว จึงไม่สามารถสร้างรายการค่าคอมมิชชั่นนี้ได้"
            );
        }
    }

    /** {@code YYYY-MM}, zero-padded -- the inherited format (bare {@code getMonthValue()}) printed
     * "2026-6" instead of "2026-06" for any month before October (reviewer finding F4). */
    private String payrollMonthLabel(LocalDate payrollMonth) {
        return String.format("%d-%02d", payrollMonth.getYear(), payrollMonth.getMonthValue());
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
