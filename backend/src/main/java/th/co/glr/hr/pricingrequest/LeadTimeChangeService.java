package th.co.glr.hr.pricingrequest;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteItemDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteStatus;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingrequest.LeadTimeChangeDtos.LeadTimeChangeDto;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRepository.LineWrite;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.CreateLeadTimeChangeRequest;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.LineInput;
import th.co.glr.hr.pricingrequest.LeadTimeChangeRequests.RejectLeadTimeChangeRequest;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * CR-1 (GLA-167), owner rulings R2 + R10 (option C): import raises ONE lead-time change request per
 * factory quote (every ticked line carries its own new min/max); the OWNING sales rep or a sales
 * manager approves or rejects it once, as a whole. Approving writes the new range onto the listed
 * {@code pricing_request_item} rows; until then the old value stands.
 *
 * <p><b>Non-blocking by design:</b> nothing in the pricing chain (receive, mark-ready, costing, CEO
 * decision) reads these rows, so a PENDING change never holds a request back from the CEO.
 *
 * <p><b>Authz (B-R3):</b> create/update/withdraw are import-only. approve/reject are the ticket's
 * owning rep (role {@code sales} AND {@code ticket.created_by = actor}) or role {@code sales_manager};
 * the CEO is deliberately NOT allowed. Reading is import, ceo, the owning rep, sales_manager.
 */
@Service
public class LeadTimeChangeService {
    private static final Set<String> IMPORT_ROLES = Set.of("import");
    private static final Set<String> DEAD_REQUEST_STATUSES = Set.of(
        PricingRequestStatus.CANCELLED, PricingRequestStatus.SUPERSEDED);
    private static final Set<String> DEAD_QUOTE_STATUSES = Set.of(
        FactoryQuoteStatus.CANCELLED, FactoryQuoteStatus.SUPERSEDED, FactoryQuoteStatus.NOT_AVAILABLE);
    private static final int REASON_MAX = 1000;
    // pricing_request_item.lead_time_*_days is SMALLINT; 10 years is well past any real lead time.
    private static final int MAX_DAYS = 3650;

    private final LeadTimeChangeRepository changes;
    private final FactoryQuoteRepository quotes;
    private final PricingRequestRepository pricingRequests;
    private final TicketRepository tickets;
    private final NotificationRepository notifications;

    public LeadTimeChangeService(LeadTimeChangeRepository changes, FactoryQuoteRepository quotes,
                                 PricingRequestRepository pricingRequests, TicketRepository tickets,
                                 NotificationRepository notifications) {
        this.changes = changes;
        this.quotes = quotes;
        this.pricingRequests = pricingRequests;
        this.tickets = tickets;
        this.notifications = notifications;
    }

    @Transactional
    public LeadTimeChangeDto create(long factoryQuoteId, CreateLeadTimeChangeRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        FactoryQuoteDto quote = quotes.find(factoryQuoteId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบเสนอราคาโรงงานนี้"));
        if (!quote.current() || DEAD_QUOTE_STATUSES.contains(quote.status())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ขอเปลี่ยนระยะเวลานำเข้าได้เฉพาะใบเสนอราคาโรงงานฉบับล่าสุดที่ยังไม่ถูกยกเลิก");
        }
        PricingRequestSummaryDto summary = requirePricingRequest(quote.pricingRequestId());
        // the quote row can still be "current" while its request is already dead (status forced or
        // cascade not yet reached it); a dead request or an inactive deal takes no new changes
        if (DEAD_REQUEST_STATUSES.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคานี้ถูกยกเลิกหรือถูกแทนที่แล้ว ไม่สามารถขอเปลี่ยนระยะเวลานำเข้าได้");
        }
        requireActiveDeal(summary.ticketId());
        String reason = validReason(request == null ? null : request.reason());
        List<LineWrite> lines = validLines(quote, summary, request == null ? null : request.lines());
        long changeId;
        try {
            changeId = changes.create(quote.id(), summary.id(), reason, actor.id(), lines);
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT,
                "โรงงาน " + quote.factoryName() + " มีคำขอเปลี่ยนระยะเวลานำเข้าที่รออนุมัติอยู่แล้ว");
        }
        addEvent(summary, actor, PricingRequestEventKind.LEAD_TIME_CHANGE_REQUESTED,
            "Lead-time change requested for " + quote.factoryName() + " (" + lines.size() + " line(s)): " + reason);
        String message = "คำขอราคา " + summary.requestCode() + " ฝ่ายนำเข้าขอเปลี่ยนระยะเวลานำเข้าของโรงงาน "
            + quote.factoryName() + " รอฝ่ายขายอนุมัติ";
        notifications.notifyEmployeeForPricingRequestInAppOnly(summary.ticketCreatedById(), summary.id(),
            PricingRequestEventKind.LEAD_TIME_CHANGE_REQUESTED, message);
        notifications.notifyByRoleForPricingRequestInAppOnly("sales_manager", summary.id(),
            PricingRequestEventKind.LEAD_TIME_CHANGE_REQUESTED, message);
        return requireChange(changeId);
    }

    /** Import edits a still-PENDING request: new reason and/or a different set of ticked lines. */
    @Transactional
    public LeadTimeChangeDto update(long changeId, CreateLeadTimeChangeRequest request, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        LeadTimeChangeDto change = requireChange(changeId);
        requirePending(change);
        FactoryQuoteDto quote = quotes.find(change.factoryQuoteId())
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบใบเสนอราคาโรงงานนี้"));
        PricingRequestSummaryDto summary = requirePricingRequest(change.pricingRequestId());
        String reason = validReason(request == null ? null : request.reason());
        List<LineWrite> lines = validLines(quote, summary, request == null ? null : request.lines());
        if (changes.updatePending(changeId, reason, lines) == 0) {
            throw notPending();
        }
        addEvent(summary, actor, PricingRequestEventKind.LEAD_TIME_CHANGE_REQUESTED,
            "Lead-time change edited for " + quote.factoryName() + " (" + lines.size() + " line(s)): " + reason);
        return requireChange(changeId);
    }

    @Transactional
    public LeadTimeChangeDto withdraw(long changeId, UserPrincipal actor) {
        requireRole(actor, IMPORT_ROLES);
        LeadTimeChangeDto change = requireChange(changeId);
        requirePending(change);
        if (changes.withdrawPending(changeId) == 0) {
            throw notPending();
        }
        PricingRequestSummaryDto summary = requirePricingRequest(change.pricingRequestId());
        addEvent(summary, actor, PricingRequestEventKind.LEAD_TIME_CHANGE_WITHDRAWN,
            "Lead-time change withdrawn");
        return requireChange(changeId);
    }

    /** Owning rep or sales manager accepts the WHOLE request: the listed lines take the new values. */
    @Transactional
    public LeadTimeChangeDto approve(long changeId, Integer expectedVersion, UserPrincipal actor) {
        LeadTimeChangeDto change = requireChange(changeId);
        PricingRequestSummaryDto summary = requirePricingRequest(change.pricingRequestId());
        requireDecider(summary, actor);
        int version = requireExpectedVersion(expectedVersion);
        requirePending(change);
        if (DEAD_REQUEST_STATUSES.contains(summary.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "คำขอราคานี้ถูกยกเลิกหรือถูกแทนที่แล้ว ไม่สามารถอนุมัติได้");
        }
        if (changes.decidePending(changeId, LeadTimeChangeStatus.APPROVED, actor.id(), null, version) == 0) {
            throw staleVersion();
        }
        changes.applyLinesToItems(changeId);
        addEvent(summary, actor, PricingRequestEventKind.LEAD_TIME_CHANGE_APPROVED,
            "Lead-time change approved (" + change.lines().size() + " line(s))");
        notifications.notifyByRoleForPricingRequestInAppOnly("import", summary.id(),
            PricingRequestEventKind.LEAD_TIME_CHANGE_APPROVED,
            "คำขอราคา " + summary.requestCode() + " ฝ่ายขายอนุมัติการเปลี่ยนระยะเวลานำเข้าแล้ว");
        return requireChange(changeId);
    }

    @Transactional
    public LeadTimeChangeDto reject(long changeId, RejectLeadTimeChangeRequest request, UserPrincipal actor) {
        LeadTimeChangeDto change = requireChange(changeId);
        PricingRequestSummaryDto summary = requirePricingRequest(change.pricingRequestId());
        requireDecider(summary, actor);
        String reason = request == null || request.reason() == null || request.reason().isBlank()
            ? null : request.reason().trim();
        if (reason == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุเหตุผลที่ไม่อนุมัติ");
        }
        if (reason.length() > REASON_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เหตุผลต้องไม่เกิน " + REASON_MAX + " ตัวอักษร");
        }
        int version = requireExpectedVersion(request.expectedVersion());
        requirePending(change);
        if (changes.decidePending(changeId, LeadTimeChangeStatus.REJECTED, actor.id(), reason, version) == 0) {
            throw staleVersion();
        }
        addEvent(summary, actor, PricingRequestEventKind.LEAD_TIME_CHANGE_REJECTED,
            "Lead-time change rejected: " + reason);
        notifications.notifyByRoleForPricingRequestInAppOnly("import", summary.id(),
            PricingRequestEventKind.LEAD_TIME_CHANGE_REJECTED,
            "คำขอราคา " + summary.requestCode() + " ฝ่ายขายไม่อนุมัติการเปลี่ยนระยะเวลานำเข้า: " + reason);
        return requireChange(changeId);
    }

    public List<LeadTimeChangeDto> listForPricingRequest(long pricingRequestId, UserPrincipal actor) {
        PricingRequestSummaryDto summary = requirePricingRequest(pricingRequestId);
        boolean allowed = IMPORT_ROLES.contains(actor.role()) || "ceo".equals(actor.role())
            || isOwningRep(summary, actor) || "sales_manager".equals(actor.role());
        if (!allowed) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        return changes.findByPricingRequest(pricingRequestId);
    }

    // ── validation ───────────────────────────────────────────────────────────────────────────

    private String validReason(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุเหตุผลที่ขอเปลี่ยนระยะเวลานำเข้า");
        }
        String reason = raw.trim();
        if (reason.length() > REASON_MAX) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เหตุผลต้องไม่เกิน " + REASON_MAX + " ตัวอักษร");
        }
        return reason;
    }

    /**
     * Every ticked line must be an IMPORT line (not a stock line) of THIS factory quote, listed once,
     * with 1 <= min <= max. The old range is snapshotted here, from the item as it is right now.
     */
    private List<LineWrite> validLines(FactoryQuoteDto quote, PricingRequestSummaryDto summary, List<LineInput> inputs) {
        if (inputs == null || inputs.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกอย่างน้อยหนึ่งรายการ");
        }
        Set<Long> onQuote = new HashSet<>();
        for (FactoryQuoteItemDto item : quote.items()) {
            onQuote.add(item.pricingRequestItemId());
        }
        Map<Long, PricingRequestItemDto> itemsById = new HashMap<>();
        for (PricingRequestItemDto item : pricingRequests.findItems(summary.id())) {
            itemsById.put(item.id(), item);
        }
        Set<Long> seen = new HashSet<>();
        List<LineWrite> lines = new ArrayList<>(inputs.size());
        for (LineInput input : inputs) {
            if (input == null || input.pricingRequestItemId() == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "รายการไม่ถูกต้อง");
            }
            long itemId = input.pricingRequestItemId();
            PricingRequestItemDto item = itemsById.get(itemId);
            if (!onQuote.contains(itemId) || item == null || item.stockSource() != null) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "รายการที่ " + itemId + " ไม่ได้อยู่ในใบเสนอราคาโรงงาน " + quote.factoryName());
            }
            if (!seen.add(itemId)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "รายการที่ " + itemId + " ซ้ำกัน");
            }
            Integer min = input.newMinDays();
            Integer max = input.newMaxDays();
            if (min == null || max == null || min < 1 || min > max || max > MAX_DAYS) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "ระยะเวลานำเข้าของรายการที่ " + itemId + " ต้องอยู่ระหว่าง 1 ถึง " + MAX_DAYS
                        + " วัน และค่าต่ำสุดต้องไม่มากกว่าค่าสูงสุด");
            }
            lines.add(new LineWrite(itemId, item.leadTimeMinDays(), item.leadTimeMaxDays(), min, max));
        }
        return lines;
    }

    // ── authz ────────────────────────────────────────────────────────────────────────────────

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /** B-R3: the owning rep or a sales manager -- NOT the CEO, import, account or another rep. */
    private void requireDecider(PricingRequestSummaryDto summary, UserPrincipal actor) {
        if (!isOwningRep(summary, actor) && !"sales_manager".equals(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เฉพาะพนักงานขายเจ้าของดีลหรือผู้จัดการฝ่ายขายเท่านั้นที่อนุมัติได้");
        }
    }

    private boolean isOwningRep(PricingRequestSummaryDto summary, UserPrincipal actor) {
        return "sales".equals(actor.role()) && actor.id() == summary.ticketCreatedById();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private void requirePending(LeadTimeChangeDto change) {
        if (!LeadTimeChangeStatus.PENDING.equals(change.status())) {
            throw notPending();
        }
    }

    private static int requireExpectedVersion(Integer expectedVersion) {
        if (expectedVersion == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุ expectedVersion");
        }
        return expectedVersion;
    }

    /** The row was PENDING when read but the guarded UPDATE matched nothing: import edited it in between. */
    private ApiException staleVersion() {
        return new ApiException(HttpStatus.CONFLICT, "คำขอถูกแก้ไขแล้ว กรุณาตรวจสอบอีกครั้ง");
    }

    private void requireActiveDeal(long ticketId) {
        boolean active = tickets.findById(ticketId)
            .map(t -> DealLifecycle.ACTIVE.equals(t.summary().lifecycle())).orElse(false);
        if (!active) {
            throw new ApiException(HttpStatus.CONFLICT, "ดีลต้นทางต้องอยู่ในสถานะ ACTIVE");
        }
    }

    private ApiException notPending() {
        return new ApiException(HttpStatus.CONFLICT, "คำขอเปลี่ยนระยะเวลานำเข้านี้ไม่ได้อยู่ในสถานะรออนุมัติแล้ว");
    }

    private LeadTimeChangeDto requireChange(long changeId) {
        return changes.find(changeId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอเปลี่ยนระยะเวลานำเข้านี้"));
    }

    private PricingRequestSummaryDto requirePricingRequest(long id) {
        return pricingRequests.findSummary(id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบคำขอราคานี้"));
    }

    private void addEvent(PricingRequestSummaryDto summary, UserPrincipal actor, String kind, String message) {
        pricingRequests.addEvent(summary.id(), summary.ticketId(), actor.id(), actor.name(), kind,
            summary.status(), summary.status(), message, null);
    }
}
