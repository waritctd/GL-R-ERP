package th.co.glr.hr.dealparty;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.dealparty.DealPartyDtos.AddOnOrderResult;
import th.co.glr.hr.dealparty.DealPartyDtos.AddPartyRequest;
import th.co.glr.hr.dealparty.DealPartyDtos.BidOutcomeRequest;
import th.co.glr.hr.dealparty.DealPartyDtos.BidOutcomeResult;
import th.co.glr.hr.dealparty.DealPartyDtos.DealPartyDto;
import th.co.glr.hr.dealparty.DealPartyDtos.OpenDealDto;
import th.co.glr.hr.dealparty.DealPartyDtos.OrderDto;
import th.co.glr.hr.dealparty.DealPartyDtos.OrderTotalsDto;
import th.co.glr.hr.dealparty.DealPartyDtos.ProjectOrdersDto;
import th.co.glr.hr.dealparty.DealPartyDtos.PatchPartyRequest;
import th.co.glr.hr.dealquotation.DealQuotationService;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.DealOwnership;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Deal parties (V204, docs/decisions/deal-party-model.md): who takes part in a deal, and the one write that
 * decides the bid — {@link #recordBidOutcome}.
 *
 * <p>Access mirrors the deal's own rules: whoever may view the deal may list its parties (sales owner/keyer,
 * sales_manager, ceo, import); whoever may run the deal (sales owner/keyer, sales_manager, ceo) may add,
 * edit and decide. account is refused like on every ticket route.
 */
@Service
public class DealPartyService {
    private static final Set<String> VIEW_ROLES = Set.of("sales", "sales_manager", "ceo", "import");
    private static final Set<String> MANAGE_ROLES = Set.of("sales", "sales_manager", "ceo");
    private static final Set<String> SEE_ALL_ROLES = Set.of("sales_manager", "ceo");

    private final DealPartyRepository parties;
    private final TicketRepository tickets;
    private final CustomerRepository customers;
    private final ContactRepository contacts;
    private final DealQuotationService dealQuotations;
    private final TicketService ticketService;

    public DealPartyService(DealPartyRepository parties, TicketRepository tickets, CustomerRepository customers,
                            ContactRepository contacts, DealQuotationService dealQuotations,
                            TicketService ticketService) {
        this.parties = parties;
        this.tickets = tickets;
        this.customers = customers;
        this.contacts = contacts;
        this.dealQuotations = dealQuotations;
        this.ticketService = ticketService;
    }

    public List<DealPartyDto> list(long ticketId, UserPrincipal actor) {
        requireViewAccess(requireSummary(ticketId), actor);
        return parties.findByTicket(ticketId);
    }

    @Transactional
    public DealPartyDto add(long ticketId, AddPartyRequest request, UserPrincipal actor) {
        TicketSummaryDto summary = requireSummary(ticketId);
        requireManageAccess(summary, actor);
        tickets.lockTicketForUpdate(ticketId);
        requireActive(requireSummary(ticketId));
        String role = request.role() == null ? null : request.role().trim().toUpperCase();
        if (role == null || !DealPartyDtos.ROLES.contains(role)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "บทบาทไม่ถูกต้อง (DESIGNER / OWNER / BUYER)");
        }
        CustomerDto customer = customers.findById(request.customerId())
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบลูกค้า"));
        Long contactId = requireContactOf(customer.id(), request.contactId());
        if (parties.findByTicketCustomerRole(ticketId, customer.id(), role).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "ลูกค้ารายนี้อยู่ในงานนี้ด้วยบทบาทเดียวกันแล้ว");
        }
        // The first party on a deal (one that pre-dates V204 and had no customer, or whose backfill found none) is
        // the headline by definition; later parties are not until the rep says so.
        boolean headline = parties.findByTicket(ticketId).isEmpty();
        long id = parties.insert(ticketId, customer.id(), contactId, role, DealPartyDtos.STATUS_ACTIVE, headline, actor.id());
        tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.DEAL_PARTY_ADDED, null, null,
            "เพิ่ม" + roleLabel(role) + " " + customer.name());
        return requireParty(id);
    }

    @Transactional
    public DealPartyDto patch(long ticketId, long partyId, PatchPartyRequest request, UserPrincipal actor) {
        TicketSummaryDto summary = requireSummary(ticketId);
        requireManageAccess(summary, actor);
        tickets.lockTicketForUpdate(ticketId);
        requireActive(requireSummary(ticketId));
        DealPartyDto party = requirePartyOnTicket(ticketId, partyId);
        if (request.status() != null) {
            String status = request.status().trim().toUpperCase();
            if (!DealPartyDtos.PATCHABLE_STATUSES.contains(status)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "สถานะนี้ตั้งเองไม่ได้ — ผลประมูลบันทึกผ่าน \"บันทึกผลประมูล\"");
            }
            if (!DealPartyDtos.IN_RUNNING.contains(party.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "ผู้รับเหมารายนี้มีผลแล้ว (" + party.status() + ") จึงเปลี่ยนสถานะไม่ได้");
            }
            if (DealPartyDtos.STATUS_QUOTED.equals(status) && party.latestQuotationId() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "ยังไม่มีใบเสนอราคาของผู้รับเหมารายนี้");
            }
            parties.updateStatus(partyId, status);
        }
        if (request.contactId() != null) {
            parties.updateContact(partyId, requireContactOf(party.customerId(), request.contactId()));
        }
        if (Boolean.TRUE.equals(request.headline())) {
            parties.setHeadline(ticketId, partyId);
        }
        return requireParty(partyId);
    }

    @Transactional
    public void delete(long ticketId, long partyId, UserPrincipal actor) {
        TicketSummaryDto summary = requireSummary(ticketId);
        requireManageAccess(summary, actor);
        tickets.lockTicketForUpdate(ticketId);
        requireActive(requireSummary(ticketId));
        DealPartyDto party = requirePartyOnTicket(ticketId, partyId);
        if (!DealPartyDtos.STATUS_ACTIVE.equals(party.status()) || parties.hasAnyQuotation(partyId)) {
            throw new ApiException(HttpStatus.CONFLICT, "ลบได้เฉพาะผู้เกี่ยวข้องที่ยังไม่มีใบเสนอราคา");
        }
        if (party.headline() && parties.findByTicket(ticketId).size() > 1) {
            throw new ApiException(HttpStatus.CONFLICT, "ตั้งยอดหลักของงานเป็นรายอื่นก่อนลบรายนี้");
        }
        parties.delete(partyId);
    }

    /**
     * "บันทึกผลประมูล" — the one write that decides the bid. One transaction:
     * <ol>
     *   <li>every winner must be a BUYER still in the running whose latest direct quotation is APPROVED;</li>
     *   <li>each extra winner is forked to a child deal ({@code <code>-A}, {@code -B}, …) with its own party and
     *       its quotation chain moved there, then confirmed as an order on the child;</li>
     *   <li>the contractors not named become NOT_AWARDED (with the reason) or stay as they are (KEEP_WAITING);</li>
     *   <li>the primary winner becomes the deal's bill-to and headline, and its quotation is confirmed as the order
     *       through the same path "ยืนยันคำสั่งซื้อ" uses, keeping the still-waiting contractors' documents open;</li>
     *   <li>one BID_OUTCOME_RECORDED event on the primary deal.</li>
     * </ol>
     * A deal that already has an AWARDED party accepts a LATER outcome only for parties still in the running, each
     * forked to a child (there is no second primary).
     */
    @Transactional
    public BidOutcomeResult recordBidOutcome(long ticketId, BidOutcomeRequest request, UserPrincipal actor) {
        TicketSummaryDto summary = requireSummary(ticketId);
        requireManageAccess(summary, actor);
        tickets.lockTicketForUpdate(ticketId);
        summary = requireSummary(ticketId);
        requireActive(summary);

        Map<Long, DealPartyDto> buyers = parties.findByTicket(ticketId).stream()
            .filter(p -> DealPartyDtos.ROLE_BUYER.equals(p.role()))
            .collect(Collectors.toMap(DealPartyDto::id, Function.identity(), (a, b) -> a, java.util.LinkedHashMap::new));
        Set<Long> awardedIds = new LinkedHashSet<>(request.awardedPartyIds());
        if (awardedIds.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เลือกผู้รับเหมาที่ได้งานอย่างน้อย 1 ราย");
        }
        List<DealPartyDto> winners = new ArrayList<>();
        for (Long id : awardedIds) {
            DealPartyDto p = buyers.get(id);
            if (p == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ไม่พบผู้รับเหมารายนี้ในงาน");
            }
            if (!DealPartyDtos.IN_RUNNING.contains(p.status())) {
                throw new ApiException(HttpStatus.CONFLICT, p.customerName() + " มีผลแล้ว (" + p.status() + ")");
            }
            winners.add(p);
        }
        boolean alreadyAwarded = buyers.values().stream().anyMatch(p -> DealPartyDtos.STATUS_AWARDED.equals(p.status()));
        DealPartyDto primary = null;
        if (!alreadyAwarded) {
            Long primaryId = request.primaryPartyId() != null ? request.primaryPartyId() : winners.get(0).id();
            primary = winners.stream().filter(p -> p.id() == primaryId).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ผู้รับเหมาของงานหลักต้องเป็นหนึ่งในผู้ที่ชนะ"));
        }
        // Every winner needs an APPROVED document before anything is written.
        Map<Long, Long> approvedQuotationByParty = new java.util.HashMap<>();
        for (DealPartyDto w : winners) {
            long quotationId = parties.findLatestApprovedDirectQuotationId(w.id())
                .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT,
                    "ใบเสนอราคาของ " + w.customerName() + " ยังไม่อนุมัติ จึงบันทึกว่าได้งานไม่ได้"));
            approvedQuotationByParty.put(w.id(), quotationId);
        }
        String othersOutcome = request.othersOutcome() == null ? DealPartyDtos.OTHERS_NOT_AWARDED
            : request.othersOutcome().trim().toUpperCase();
        if (!DealPartyDtos.OTHERS_NOT_AWARDED.equals(othersOutcome) && !DealPartyDtos.OTHERS_KEEP_WAITING.equals(othersOutcome)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ผลของรายที่ไม่ได้เลือกต้องเป็น NOT_AWARDED หรือ KEEP_WAITING");
        }
        String reason = request.notAwardedReason() == null ? "OTHER_BIDDER" : request.notAwardedReason().trim().toUpperCase();
        if (!DealPartyDtos.NOT_AWARDED_REASONS.contains(reason)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เหตุผลที่ไม่ได้งานไม่ถูกต้อง");
        }
        // A late winner on an already-awarded deal leaves the other buyers alone unless the rep says otherwise.
        boolean touchOthers = !alreadyAwarded || request.othersOutcome() != null;
        List<DealPartyDto> others = buyers.values().stream()
            .filter(p -> touchOthers)
            // Only contractors who actually bid lose; a party that never quoted (ACTIVE) is left as it is.
            .filter(p -> !awardedIds.contains(p.id())
                && (DealPartyDtos.STATUS_QUOTED.equals(p.status()) || DealPartyDtos.STATUS_NEGOTIATING.equals(p.status())))
            .toList();

        // 2. extra winners -> child deals, BEFORE the primary confirmation cancels the deal's other open documents.
        List<Long> childTicketIds = new ArrayList<>();
        List<String> childLines = new ArrayList<>();
        for (DealPartyDto w : winners) {
            if (primary != null && w.id() == primary.id()) {
                continue;
            }
            String code = summary.code() + "-" + childCodeSuffix(parties.countChildren(ticketId, "BID_SPLIT"));
            long childId = parties.forkChild(ticketId, code, w.customerId(), w.customerName(), w.contactId(), actor.id());
            long childPartyId = parties.insert(childId, w.customerId(), w.contactId(), DealPartyDtos.ROLE_BUYER,
                DealPartyDtos.STATUS_AWARDED, true, actor.id());
            parties.moveQuotations(w.id(), childId, childPartyId);
            parties.markAwardedSplit(w.id(), childId);
            tickets.addEvent(childId, actor.id(), actor.name(), TicketEventKind.CREATED, null, "draft", null);
            tickets.addEvent(childId, actor.id(), actor.name(), TicketEventKind.DEAL_SPLIT_FROM_PARENT, null, null,
                "แยกจากงาน " + summary.code() + " — " + w.customerName() + " ได้งานส่วนของตน");
            dealQuotations.confirmOrderFromDirectQuotation(approvedQuotationByParty.get(w.id()), actor, Set.of());
            childTicketIds.add(childId);
            childLines.add(code + " → " + w.customerName());
        }
        // 3. the rest
        Set<Long> keepPartyIds = new LinkedHashSet<>();
        for (DealPartyDto o : others) {
            if (DealPartyDtos.OTHERS_KEEP_WAITING.equals(othersOutcome)) {
                keepPartyIds.add(o.id());
            } else {
                parties.markNotAwarded(o.id(), reason);
            }
        }
        // 4. the primary winner
        if (primary != null) {
            parties.setBillTo(ticketId, primary.customerId(), primary.customerName(), primary.contactId());
            parties.markAwarded(primary.id());
            parties.setHeadline(ticketId, primary.id());
            dealQuotations.confirmOrderFromDirectQuotation(approvedQuotationByParty.get(primary.id()), actor, keepPartyIds);
        }
        // 5. the record
        StringBuilder message = new StringBuilder("บันทึกผลประมูล: ได้งาน ")
            .append(winners.stream().map(DealPartyDto::customerName).collect(Collectors.joining(", ")));
        if (!childLines.isEmpty()) {
            message.append(" · แยกงาน ").append(String.join(", ", childLines));
        }
        if (!others.isEmpty()) {
            message.append(DealPartyDtos.OTHERS_KEEP_WAITING.equals(othersOutcome) ? " · ยังรอผล " : " · ไม่ได้งาน ")
                .append(others.stream().map(DealPartyDto::customerName).collect(Collectors.joining(", ")));
        }
        tickets.addEvent(ticketId, actor.id(), actor.name(), TicketEventKind.BID_OUTCOME_RECORDED, null, null, message.toString());
        return new BidOutcomeResult(ticketId, childTicketIds, requireSummary(ticketId));
    }

    /**
     * สั่งเพิ่ม: a second order under the same project, as a linked deal (child_kind ADD_ON) of the root deal.
     * Only the owner/keyer or an all-sales-documents grant holder; CEO and sales_manager without the grant are refused.
     * The root of a bid-split child is that child itself (a split child is another customer's deal). The number is
     * {@code <root code>-<n>} with n = 2 + the root's add-ons, assigned under the root's row lock.
     */
    @Transactional
    public AddOnOrderResult createAddOnOrder(long ticketId, UserPrincipal actor) {
        TicketSummaryDto source = requireSummary(ticketId);
        if (!(DealOwnership.isOwnerOrKeyer(source, actor.id()) || tickets.holdsAllSalesDocumentsGrant(actor))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เปิดออเดอร์เพิ่มในงานนี้");
        }
        tickets.lockTicketForUpdate(ticketId);
        requireActive(requireSummary(ticketId));
        // An add-on always hangs directly under its root, so one hop is the whole walk.
        // A null parent (FK ON DELETE SET NULL after the root was hard-deleted) makes the add-on its own root.
        long rootId = "ADD_ON".equals(source.childKind()) && source.parentTicketId() != null
            ? source.parentTicketId() : ticketId;
        if (rootId != ticketId) {
            tickets.lockTicketForUpdate(rootId);
        }
        TicketSummaryDto root = requireSummary(rootId);
        String code = root.code() + "-" + (parties.countChildren(rootId, "ADD_ON") + 2);
        long newId = parties.forkAddOn(rootId, code, actor.id());
        parties.findByTicket(rootId).stream().filter(DealPartyDto::headline).findFirst()
            .ifPresent(h -> parties.insert(newId, h.customerId(), h.contactId(), h.role(), DealPartyDtos.STATUS_ACTIVE,
                true, actor.id()));
        // The customer is already buying: the order starts at QUOTE_BUYER (on every route), where a buyer quotation is
        // drafted. Same mechanism as TicketService#create's start stage: write the stage plus the normal STAGE_CHANGED.
        tickets.updateSalesStage(newId, DealStage.QUOTE_BUYER);
        tickets.addEvent(newId, actor.id(), actor.name(), TicketEventKind.CREATED, null, "draft", null);
        tickets.addEvent(newId, actor.id(), actor.name(), TicketEventKind.STAGE_CHANGED, DealStage.LEAD_APPROACH,
            DealStage.QUOTE_BUYER, "เริ่มที่ขั้นเสนอราคาผู้ซื้อ (ออเดอร์เพิ่ม)");
        tickets.addEvent(newId, actor.id(), actor.name(), TicketEventKind.ADD_ON_ORDER_CREATED, null, null,
            "ออเดอร์เพิ่มของ " + root.code());
        tickets.addEvent(rootId, actor.id(), actor.name(), TicketEventKind.ADD_ON_ORDER_CREATED, null, null,
            "เปิดออเดอร์เพิ่ม " + code);
        return new AddOnOrderResult(newId, code, rootId);
    }

    /**
     * GET /api/tickets/{id}/orders: the project's orders (root + ADD_ON children). The requested deal must be
     * readable by the actor (404/403 as its own read); a sibling the actor cannot read is left out. Import gets no
     * money. A lost or cancelled order stays listed but is left out of the totals.
     */
    public ProjectOrdersDto orders(long ticketId, UserPrincipal actor) {
        TicketSummaryDto requested = requireSummary(ticketId);
        requireOrderRead(ticketId, actor);
        long rootId = "ADD_ON".equals(requested.childKind()) && requested.parentTicketId() != null
            ? requested.parentTicketId() : ticketId;
        boolean withMoney = !"import".equals(actor.role());
        List<OrderDto> orders = new ArrayList<>();
        BigDecimal value = BigDecimal.ZERO;
        BigDecimal received = BigDecimal.ZERO;
        BigDecimal outstanding = BigDecimal.ZERO;
        String rootCode = requested.code();
        List<DealPartyRepository.OrderRow> chain = parties.findOrderChain(rootId);
        for (int i = 0; i < chain.size(); i++) {
            DealPartyRepository.OrderRow row = chain.get(i);
            if (row.ticketId() != ticketId && !canReadOrder(row.ticketId(), actor)) {
                continue;
            }
            if (row.ticketId() == rootId) {
                rootCode = row.code();
            }
            BigDecimal owed = row.payable().subtract(row.received()).max(BigDecimal.ZERO);
            boolean dead = DealLifecycle.CLOSED_LOST.equals(row.lifecycle()) || DealLifecycle.CANCELLED.equals(row.lifecycle());
            if (!dead) {
                value = value.add(row.valueExVat());
                received = received.add(row.received());
                outstanding = outstanding.add(owed);
            }
            boolean root = row.ticketId() == rootId;
            orders.add(new OrderDto(row.ticketId(), row.code(),
                root && !"ADD_ON".equals(row.childKind()) ? row.code() + "-1" : row.code(), i + 1,
                row.childKind(), row.salesStage(), row.lifecycle(), row.fulfillmentStatus(),
                withMoney ? row.valueExVat() : null, withMoney ? row.received() : null, withMoney ? owed : null));
        }
        return new ProjectOrdersDto(rootId, rootCode, orders,
            withMoney ? new OrderTotalsDto(value, received, outstanding) : new OrderTotalsDto(null, null, null));
    }

    /** The deal-read rule each role already has: sales/sales_manager/ceo the whole-deal read, import its scoped page,
     * account its finance-view scope. Anyone else (warehouse, qc, employee, hr) is refused. */
    private void requireOrderRead(long ticketId, UserPrincipal actor) {
        switch (actor.role()) {
            case "import" -> {
                if (!tickets.isInImportScope(ticketId) && !tickets.isDeadDealWithIssuedImportRequest(ticketId)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
                }
            }
            case "account" -> {
                if (!tickets.isInAccountScope(ticketId)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
                }
            }
            default -> ticketService.requireDealReadAccess(ticketId, actor);
        }
    }

    private boolean canReadOrder(long ticketId, UserPrincipal actor) {
        try {
            requireOrderRead(ticketId, actor);
            return true;
        } catch (ApiException e) {
            if (e.getStatus() == HttpStatus.FORBIDDEN) {
                return false;
            }
            throw e;
        }
    }

    public List<OpenDealDto> openDealsForProject(String projectName, UserPrincipal actor) {
        if (!MANAGE_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        String key = DealPartyRepository.nameKey(projectName);
        if (key.isEmpty()) {
            return List.of();
        }
        return parties.findOpenDealsForProject(key, actor.id(), SEE_ALL_ROLES.contains(actor.role()));
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────

    private TicketSummaryDto requireSummary(long ticketId) {
        return tickets.findSummaryById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงาน"));
    }

    private DealPartyDto requireParty(long partyId) {
        return parties.findById(partyId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบผู้เกี่ยวข้องในงาน"));
    }

    private DealPartyDto requirePartyOnTicket(long ticketId, long partyId) {
        DealPartyDto party = requireParty(partyId);
        if (party.ticketId() != ticketId) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบผู้เกี่ยวข้องในงาน");
        }
        return party;
    }

    private Long requireContactOf(long customerId, Long contactId) {
        if (contactId == null) {
            return null;
        }
        ContactDto contact = contacts.findById(contactId)
            .filter(c -> c.customerId() == customerId)
            .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "ผู้ติดต่อไม่ใช่ของลูกค้ารายนี้"));
        return contact.id();
    }

    public static String childCodeSuffix(int n) {
        return n < 26 ? String.valueOf((char) ('A' + n)) : String.valueOf(n + 1);
    }

    private static void requireActive(TicketSummaryDto summary) {
        if (!DealLifecycle.ACTIVE.equals(summary.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT, "งานไม่ได้อยู่ในสถานะ ACTIVE (" + summary.lifecycle() + ")");
        }
    }

    private void requireViewAccess(TicketSummaryDto summary, UserPrincipal actor) {
        String role = actor.role();
        if (tickets.holdsAllSalesDocumentsGrant(actor)) {
            return;
        }
        if (!VIEW_ROLES.contains(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if ("sales".equals(role) && !(DealOwnership.isOwnerOrKeyer(summary, actor.id()) || tickets.holdsAllSalesDocumentsGrant(actor))) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private void requireManageAccess(TicketSummaryDto summary, UserPrincipal actor) {
        String role = actor.role();
        boolean owner = ("sales".equals(role) && DealOwnership.isOwnerOrKeyer(summary, actor.id()))
            || tickets.holdsAllSalesDocumentsGrant(actor);
        if (!owner && !"sales_manager".equals(role) && !"ceo".equals(role)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private static String roleLabel(String role) {
        return switch (role) {
            case DealPartyDtos.ROLE_DESIGNER -> "ผู้ออกแบบ";
            case DealPartyDtos.ROLE_OWNER -> "เจ้าของโครงการ";
            default -> "ผู้รับเหมา";
        };
    }
}
