package th.co.glr.hr.importprogress;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importprogress.ImportProgressDtos.FactoryImportProgressDto;
import th.co.glr.hr.importprogress.ImportProgressDtos.OrderEmailTemplateDto;
import th.co.glr.hr.importprogress.ImportProgressRequests.AdvanceImportStepRequest;
import th.co.glr.hr.importprogress.ImportProgressRequests.UpdateFactoryImportRequest;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Per-factory import progress (S12–S17). Price-free (see {@link ImportStep}), so READ is open to
 * sales as well as import/ceo; WRITE (advancing a step, editing ETA/note, generating the
 * order-email template) stays import/ceo. There is no purchase order — the "email to factory" is a
 * template Import copies and sends themselves, mirroring the factory-quote email hand-off.
 */
@Service
public class ImportProgressService {
    private static final Set<String> READ_ROLES = Set.of("import", "ceo", "sales", "sales_manager");
    private static final Set<String> WRITE_ROLES = Set.of("import", "ceo");

    private final ImportProgressRepository repo;
    private final PricingRequestRepository pricingRequests;
    private final TicketService ticketService;

    public ImportProgressService(ImportProgressRepository repo, PricingRequestRepository pricingRequests,
                                 TicketService ticketService) {
        this.repo = repo;
        this.pricingRequests = pricingRequests;
        this.ticketService = ticketService;
    }

    /** Read the tracker for one pricing request (sales + import/ceo). Read-only — never seeds. */
    public List<FactoryImportProgressDto> listForPricingRequest(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, READ_ROLES);
        requireSummary(pricingRequestId);
        return repo.findByPricingRequest(pricingRequestId);
    }

    /**
     * Import/ceo action that opens the tracker: SEEDS a row per factory of the deal (idempotent —
     * {@code ON CONFLICT DO NOTHING}, so re-opening never resets a step) and returns the list. A
     * POST rather than a GET because it writes.
     */
    @Transactional
    public List<FactoryImportProgressDto> ensureAndList(long pricingRequestId, UserPrincipal actor) {
        requireRole(actor, WRITE_ROLES);
        PricingRequestSummaryDto summary = requireSummary(pricingRequestId);
        repo.ensureRowsForPricingRequest(pricingRequestId, summary.ticketId());
        return repo.findByPricingRequest(pricingRequestId);
    }

    /**
     * The cross-deal import worklist (sales + import/ceo). Read-only. Scoping to a sales rep's own
     * deals is a follow-up (TODO); import/ceo see every deal regardless, and the data is price-free.
     */
    public List<FactoryImportProgressDto> listAll(UserPrincipal actor) {
        requireRole(actor, READ_ROLES);
        return repo.findAll();
    }

    /** The deal-detail view — read-only, no seeding (the pricing-request view owns that). */
    public List<FactoryImportProgressDto> listForTicket(long ticketId, UserPrincipal actor) {
        requireRole(actor, READ_ROLES);
        return repo.findByTicket(ticketId);
    }

    @Transactional
    public FactoryImportProgressDto advanceStep(long id, AdvanceImportStepRequest request, UserPrincipal actor) {
        requireRole(actor, WRITE_ROLES);
        FactoryImportProgressDto row = requireRow(id);
        String target = request.targetStep();
        if (!ImportStep.isValid(target)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ขั้นการนำเข้าไม่ถูกต้อง: " + target);
        }
        if (ImportStep.rank(target) <= ImportStep.rank(row.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เลื่อนขั้นการนำเข้าถอยหลังหรือซ้ำไม่ได้ (ปัจจุบัน " + row.importStep() + " → ขอ " + target + ")");
        }
        repo.advanceStep(id, target, actor.id());
        // Rollup: when this advance brings the LAST factory of the deal to RECEIVED (S17 — all
        // goods at the warehouse), hand off to TicketService to set fulfillment_status=GOODS_RECEIVED
        // and advance the deal to DELIVERY_SCHEDULING (S18), which opens the ส่งมอบสินค้า gate for
        // sales. Authorisation for that cross-aggregate write is this method's own WRITE_ROLES check
        // (import/ceo) — see applyImportProgressRollup's Javadoc. Only fire on the RECEIVED edge so a
        // mid-sequence advance never touches the ticket.
        if (ImportStep.RECEIVED.equals(target)) {
            List<FactoryImportProgressDto> siblings = repo.findByPricingRequest(row.pricingRequestId());
            boolean allReceived = !siblings.isEmpty()
                && siblings.stream().allMatch(r -> ImportStep.RECEIVED.equals(r.importStep()));
            if (allReceived) {
                ticketService.applyImportProgressRollup(row.ticketId(), actor);
            }
        }
        return requireRow(id);
    }

    @Transactional
    public FactoryImportProgressDto updateEtaNote(long id, UpdateFactoryImportRequest request, UserPrincipal actor) {
        requireRole(actor, WRITE_ROLES);
        requireRow(id);
        repo.updateEtaNote(id, request.eta(), request.note(), actor.id());
        return requireRow(id);
    }

    /**
     * Builds the order-email template for one factory from the deal's requested items — Import
     * copies it and sends it themselves (there is no in-app send, mirroring the factory-quote
     * email). Generating it is a write action (import/ceo) even though it persists nothing, so it
     * cannot be a channel for a reader to see item detail they shouldn't.
     */
    public OrderEmailTemplateDto orderEmailTemplate(long pricingRequestId, String factoryName, UserPrincipal actor) {
        requireRole(actor, WRITE_ROLES);
        PricingRequestSummaryDto summary = requireSummary(pricingRequestId);
        List<PricingRequestItemDto> items = pricingRequests.findItems(pricingRequestId).stream()
            .filter(it -> factoryName.equals(firstText(it.resolvedFactoryName(), it.factory())))
            .toList();
        if (items.isEmpty()) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_CONTENT,
                "ไม่พบรายการสินค้าของโรงงาน " + factoryName + " ในคำขอราคานี้");
        }
        String project = firstText(summary.projectName(), summary.customerName());
        String subject = "Purchase order " + summary.requestCode()
            + (project != null ? " - " + project : "");
        return new OrderEmailTemplateDto(factoryName, subject, buildOrderBody(summary, project, factoryName, items));
    }

    private String buildOrderBody(PricingRequestSummaryDto summary, String project, String factoryName,
                                  List<PricingRequestItemDto> items) {
        StringBuilder body = new StringBuilder();
        body.append("Dear ").append(factoryName).append(" Team,\n\n");
        body.append("We would like to place a purchase order for the following item(s):\n\n");
        body.append("Reference: ").append(summary.requestCode());
        if (project != null) {
            body.append("    Project/Customer: ").append(project);
        }
        body.append("\n\n");
        int no = 1;
        for (PricingRequestItemDto item : items) {
            String name = firstText(joinNonBlank(item.catalogBrand(), item.catalogCollection(), item.catalogModel()),
                joinNonBlank(item.brand(), item.model(), item.productDescription()));
            body.append(no++).append(". ").append(name == null ? "(unspecified item)" : name);
            if (item.catalogProductCode() != null && !item.catalogProductCode().isBlank()) {
                body.append("  [Code: ").append(item.catalogProductCode().trim()).append("]");
            }
            body.append("\n");
            String spec = joinWithBar(labelled("Color", item.color()), labelled("Surface", item.texture()),
                labelled("Size", item.size()));
            if (!spec.isBlank()) {
                body.append("   ").append(spec).append("\n");
            }
            body.append("   Qty: ").append(formatQty(item.requestedQty()))
                .append(" ").append(safe(item.requestedUnit()));
            if (item.requestedQtySqm() != null) {
                body.append(" (").append(formatQty(item.requestedQtySqm())).append(" sqm)");
            }
            body.append("\n\n");
        }
        body.append("Please confirm the order, unit price, currency and lead time. Thank you.\n");
        return body.toString();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private PricingRequestSummaryDto requireSummary(long pricingRequestId) {
        return pricingRequests.findSummary(pricingRequestId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
    }

    private FactoryImportProgressDto requireRow(long id) {
        return repo.findById(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบสถานะการนำเข้าของโรงงานนี้"));
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงสถานะการนำเข้ารายโรงงาน");
        }
    }

    private static String firstText(String first, String fallback) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return fallback != null && !fallback.isBlank() ? fallback.trim() : null;
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private static String joinNonBlank(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(part.trim());
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String joinWithBar(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String part : parts) {
            if (part != null && !part.isBlank()) {
                if (sb.length() > 0) {
                    sb.append(" | ");
                }
                sb.append(part);
            }
        }
        return sb.toString();
    }

    private static String labelled(String label, String value) {
        return value == null || value.isBlank() ? null : label + ": " + value.trim();
    }

    private static String formatQty(BigDecimal qty) {
        return qty == null ? "-" : qty.stripTrailingZeros().toPlainString();
    }
}
