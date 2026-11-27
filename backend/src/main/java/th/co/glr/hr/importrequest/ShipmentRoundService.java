package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.OversightReader;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.auth.WarehouseStaff;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.ImportRequestService.StepChange;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.AdvanceShipmentRoundRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.AddLotsRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ChangeLotRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.CreateShipmentRoundRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.DocumentInput;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.LotInput;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.MoveLotRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.DocumentRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.UpdateShipmentRoundRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentFacts;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentRoundDto;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.ShipmentRoundsResponse;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.DerivedInput;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.IrLock;
import th.co.glr.hr.ticket.TicketEventKind;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * Shipment rounds for the import team (slice 2). A round's step is the step of every lot on it; an IR's own
 * import_step is DERIVED from its lots (see {@link #derive}) so every reader of import_step keeps working.
 *
 * <p>Lock order, everywhere: the round rows (ascending id), then the import_request rows (ascending id), then the
 * tickets (ascending id), the same order as {@link ImportRequestService#advanceStep} and {@code undoStep}.
 */
@Service
public class ShipmentRoundService {

    /**
     * Which document kinds must carry a number before a PORTAL round may move to a step (Ploy: "RECEIVED needs at least
     * one proforma number and one invoice number"; no other step is blocked, and a file is never required). Numbers on
     * documents typed in the same advance count. MIGRATED and BACKFILL rounds are never checked.
     */
    static final Map<String, List<String>> REQUIRED_NUMBERS_BY_STEP =
        Map.of(ImportRequestStep.RECEIVED, List.of("PROFORMA", "INVOICE"));

    private static final Map<String, String> KIND_LABEL = Map.of("PROFORMA", "Proforma invoice", "INVOICE", "Invoice");
    private static final Set<String> DOCUMENT_KINDS = Set.of("PROFORMA", "INVOICE", "PACKING_LIST", "BILL_OF_LADING",
        "CERT_ORIGIN", "IMPORT_ENTRY", "OTHER");
    private static final Set<String> TRANSPORT_MODES = Set.of("SEA", "AIR", "LAND");

    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final ShipmentRoundRepository rounds;
    private final ImportRequestRepository stored;
    private final ImportRequestService irService;
    private final TicketRepository tickets;
    private final FileStorageService files;
    private final EmployeeAuthRepository employees;
    private Clock clock = Clock.systemUTC();

    public ShipmentRoundService(ShipmentRoundRepository rounds, ImportRequestRepository stored,
                                ImportRequestService irService, TicketRepository tickets, FileStorageService files,
                                EmployeeAuthRepository employees) {
        this.rounds = rounds;
        this.stored = stored;
        this.irService = irService;
        this.tickets = tickets;
        this.files = files;
        this.employees = employees;
    }

    void useClock(Clock clock) {
        this.clock = clock;
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────────────

    public ShipmentRoundsResponse list(UserPrincipal actor) {
        requireRead(actor);
        return new ShipmentRoundsResponse(assemble(rounds.roundsWithLots(null), actor), rounds.waiting());
    }

    public ShipmentRoundDto get(long id, UserPrincipal actor) {
        requireRead(actor);
        return assemble(rounds.roundsWithLots(id), actor).stream().findFirst().orElseThrow(this::notFound);
    }

    // ── writes ──────────────────────────────────────────────────────────────────────────────────────────

    /** The facts editor: every fact as sent, and corrections of the real date of steps the round has passed. Allowed at
     * every step, RECEIVED included; ends the undo window. A corrected date of the IR's own current step moves its
     * import_step_at too. */
    @Transactional
    public ShipmentRoundDto updateFacts(long id, UpdateShipmentRoundRequest request, UserPrincipal actor) {
        requireWrite(actor);
        ShipmentRoundDto round = lockRound(id);
        ShipmentFacts facts = request == null ? null : request.facts();
        // the editor writes every fact as sent: refuse a save built on a round someone else changed since it was opened
        if (request != null && request.expectedUpdatedAt() != null && !request.expectedUpdatedAt().equals(round.updatedAt())) {
            throw new ApiException(HttpStatus.CONFLICT, "มีผู้อื่นแก้ข้อมูลรอบนี้ กรุณาโหลดใหม่");
        }
        validateFacts(facts);
        if (ImportRequestStep.RECEIVED.equals(round.importStep())) {
            requireNoteWhenIncomplete(facts, null); // the receive action's rule: a round marked incomplete keeps its note
        }
        Map<Long, BigDecimal> receipts = request == null ? Map.of() : validateReceipts(id, request.lotReceipts());
        if (!receipts.isEmpty() && !ImportRequestStep.RECEIVED.equals(round.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT, "แก้จำนวนที่ถึงโกดังได้เมื่อรอบถึงโกดังแล้วเท่านั้น");
        }
        List<IrLock> locked = rounds.lockImportRequestsOnRounds(List.of(id));
        Map<String, LocalDate> dates = request == null || request.stepDates() == null ? Map.of() : request.stepDates();
        for (Map.Entry<String, LocalDate> e : dates.entrySet()) {
            if (!ImportRequestStep.isValid(e.getKey()) || e.getValue() == null
                || ImportRequestStep.indexOf(e.getKey()) > ImportRequestStep.indexOf(round.importStep())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "แก้วันที่ได้เฉพาะขั้นตอนที่รอบนี้ผ่านมาแล้ว");
            }
            if (e.getValue().isAfter(LocalDate.now(BANGKOK))) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ของขั้นตอนต้องไม่เป็นวันที่ในอนาคต");
            }
            requireNotBeforeFirstIssue(e.getValue(), locked);
        }
        List<IrLock> irs = endUndoWindow(id);
        // only the IRs whose receipt really changes: a save on a shared round must not roll up a neighbouring deal
        Set<Long> changed = rounds.lotsByRound(List.of(id)).getOrDefault(id, List.of()).stream()
            .filter(l -> receipts.containsKey(l.lotId())
                && receipts.get(l.lotId()).compareTo(l.receivedQty() != null ? l.receivedQty() : l.qty()) != 0)
            .map(ShipmentRoundDtos.ShipmentLotDto::importRequestId).collect(Collectors.toSet());
        applyReceipts(id, receipts);
        rollUpAfterReceiptChange(irs.stream().filter(i -> changed.contains(i.id())).toList(), actor); // a receipt raised above 0 may complete a deal
        if (facts != null) {
            rounds.applyFacts(id, facts, true);
        }
        if (!dates.isEmpty()) {
            rounds.setStepDates(id, dates);
            Map<Long, List<DerivedInput>> inputs = rounds.derivedInputs(irs.stream().map(IrLock::id).toList()).stream()
                .collect(Collectors.groupingBy(DerivedInput::importRequestId));
            for (IrLock ir : irs) {
                ShipmentStepDerivation.Derived d = ShipmentStepDerivation.derive(ir, inputs.getOrDefault(ir.id(), List.of()));
                if (d.step().equals(ir.importStep())) {
                    refreshStamp(ir, d);
                }
            }
        }
        return get(id, actor);
    }

    @Transactional
    public ShipmentRoundDto advance(long id, AdvanceShipmentRoundRequest request, UserPrincipal actor) {
        requireWrite(actor);
        String target = request == null ? null : request.targetStep();
        if (!ImportRequestStep.isValid(target)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ขั้นตอนไม่ถูกต้อง: " + target);
        }
        if (!ImportRequestStep.isValid(request.expectedStep())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุขั้นตอนปัจจุบันของรอบ (expectedStep)");
        }
        ShipmentRoundDto round = lockRound(id);
        List<IrLock> irs = rounds.lockImportRequestsOnRounds(List.of(id));
        if (!request.expectedStep().equals(round.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT, "ขั้นตอนถูกเปลี่ยนแปลงโดยผู้ใช้อื่น กรุณาโหลดหน้าใหม่");
        }
        if (ImportRequestStep.indexOf(target) <= ImportRequestStep.indexOf(round.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เลื่อนขั้นตอนได้เฉพาะไปข้างหน้าเท่านั้น — แก้ไขข้อมูลของรอบได้ที่ แก้ไข");
        }
        LocalDate eventDate = request.eventDate() != null ? request.eventDate() : LocalDate.now(BANGKOK);
        if (eventDate.isAfter(LocalDate.now(BANGKOK))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ของขั้นตอนต้องไม่เป็นวันที่ในอนาคต");
        }
        requireNotBeforeFirstIssue(eventDate, irs);
        validateFacts(request.facts());
        Map<Long, BigDecimal> receipts = Map.of();
        if (ImportRequestStep.RECEIVED.equals(target)) {
            requireNoteWhenIncomplete(request.facts(), round.facts());
            receipts = validateReceipts(id, request.lotReceipts());
        }
        requireNumbers(round, request.documents(), target);
        rounds.advanceRound(id, target, eventDate, actor.id(), actor.name(), request.note(), request.facts());
        rounds.finalizeDocuments(id);
        applyReceipts(id, receipts);
        for (DocumentInput d : request.documents() == null ? List.<DocumentInput>of() : request.documents()) {
            String no = blankToNull(d.docNo());
            if (no != null) {
                rounds.insertDocument(id, requireKind(d.kind()), limit(no, 100, "เลขที่เอกสาร"), null, null, actor.id(), true);
            }
        }

        List<StepChange> changes = recomputeIrSteps(irs, actor, request.note());
        Set<Long> completed = applyAftermath(onActiveDeals(changes, irs), actor, request.note());
        if (ImportRequestStep.RECEIVED.equals(target)) {
            tellRepsLotsArrived(id, irs, completed);
        }
        return get(id, actor);
    }

    @Transactional
    public ShipmentRoundDto undo(long id, ShipmentRoundDtos.UndoShipmentRoundRequest request, UserPrincipal actor) {
        requireWrite(actor);
        String expected = request == null ? null : request.expectedStep();
        if (!ImportRequestStep.isValid(expected)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุขั้นตอนที่จะเลิกทำ");
        }
        ShipmentRoundDto round = lockRound(id);
        List<IrLock> irs = rounds.lockImportRequestsOnRounds(List.of(id));
        if (round.undoFromStep() == null) {
            throw new ApiException(HttpStatus.CONFLICT, "ไม่มีการเลื่อนขั้นตอนให้เลิกทำ");
        }
        if (request.advanceId() == null || !request.advanceId().equals(round.advanceId())) {
            throw new ApiException(HttpStatus.CONFLICT, "การเลื่อนขั้นตอนนี้ถูกเปลี่ยนไปแล้ว จึงเลิกทำไม่ได้");
        }
        if (!expected.equals(round.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT, "ขั้นตอนถูกเปลี่ยนไปแล้ว จึงเลิกทำไม่ได้");
        }
        if (round.undoById() == null || round.undoById() != actor.id()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "เลิกทำได้เฉพาะผู้ที่เลื่อนขั้นตอนนี้");
        }
        if (ImportRequestStep.RECEIVED.equals(round.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT, "รับสินค้าเข้าคลังแล้ว เลิกทำไม่ได้ — แก้ไขข้อมูลของรอบที่ แก้ไข");
        }
        // lockRound returns the raw advance stamp in undoUntil; the public reads turn it into the deadline.
        if (round.undoUntil() == null
            || clock.instant().isAfter(round.undoUntil().plusSeconds(ImportRequestService.STEP_UNDO_WINDOW_SECONDS))) {
            throw new ApiException(HttpStatus.CONFLICT,
                "เลยเวลาเลิกทำแล้ว (" + ImportRequestService.STEP_UNDO_WINDOW_SECONDS + " วินาที)");
        }
        rounds.deleteDocumentsCreatedByAdvance(id);
        if (rounds.undoRound(id, expected, actor.id(), round.advanceId(), ImportRequestService.STEP_UNDO_WINDOW_SECONDS) == 0) {
            throw new ApiException(HttpStatus.CONFLICT, "มีผู้อื่นเปลี่ยนขั้นตอนนี้ไปก่อน หรือเลยเวลาเลิกทำแล้ว จึงเลิกทำไม่ได้");
        }
        List<StepChange> changes = recomputeIrSteps(irs, actor, null);
        onActiveDeals(changes, irs).stream().collect(Collectors.groupingBy(c -> c.ir().ticketId(), java.util.TreeMap::new,
            Collectors.toList())).forEach((ticketId, list) -> {
                tickets.lockTicketForUpdate(ticketId);
                for (StepChange c : list) {
                    tickets.addEventWithDocument(ticketId, actor.id(), actor.name(),
                        TicketEventKind.IMPORT_STEP_ADVANCED, null, null,
                        "โรงงาน " + c.ir().factoryName() + ": เลิกทำ " + ImportRequestStep.thaiLabel(c.from())
                            + " → " + ImportRequestStep.thaiLabel(c.to()),
                        th.co.glr.hr.ticket.RelatedDocumentType.IMPORT_REQUEST, c.ir().id());
                }
            });
        return get(id, actor);
    }

    // ── planning rounds and moving lots (slice 3) ─────────────────────────────────────────────────────────
    // Rules: same factory only; ISSUED import requests on ACTIVE deals; never more than a line's quantity across all its
    // lots; qty > 0; lots are editable only until the round leaves (before IN_TRANSIT). Lock order: rounds (ascending, both
    // when moving), then the import requests, then the tickets. Every change ends the undo window and recomputes the
    // derived IR steps (a lot joining a round at ORDERED moves its IR; quantity left waiting holds it at CONTACTED).

    private static final int LEFT_INDEX = ImportRequestStep.indexOf(ImportRequestStep.IN_TRANSIT);

    @Transactional
    public ShipmentRoundDto createRound(CreateShipmentRoundRequest request, UserPrincipal actor) {
        requireWrite(actor);
        if (request == null || request.factoryId() == null || request.lots() == null || request.lots().isEmpty()
            || request.lots().stream().anyMatch(java.util.Objects::isNull)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เลือกโรงงานและใบขอซื้ออย่างน้อย 1 ใบ");
        }
        if (request.transportMode() != null && !TRANSPORT_MODES.contains(request.transportMode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ประเภทการขนส่งไม่ถูกต้อง");
        }
        String label = limit(blankToNull(request.label()), 100, "ชื่อรอบ");
        List<IrLock> irs = rounds.lockImportRequests(List.of(),
            request.lots().stream().map(LotInput::importRequestId).filter(java.util.Objects::nonNull).distinct().toList());
        String factoryName = irs.stream().filter(i -> request.factoryId().equals(i.factoryId())).map(IrLock::factoryName)
            .findFirst().orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ไม่พบใบขอซื้อของโรงงานนี้ที่จัดรอบได้"));
        long id = rounds.createPlannedRound(request.factoryId(), factoryName, label, request.transportMode(),
            LocalDate.now(BANGKOK), actor.id(), actor.name());
        placeLots(id, request.factoryId(), request.lots(), irs);
        finishLotChange(irs, List.of(id), actor);
        return get(id, actor);
    }

    @Transactional
    public ShipmentRoundDto addLots(long id, AddLotsRequest request, UserPrincipal actor) {
        requireWrite(actor);
        ShipmentRoundDto round = lockRound(id);
        requireNotLeft(round);
        if (request == null || request.lots() == null || request.lots().isEmpty() || request.lots().stream().anyMatch(java.util.Objects::isNull)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เลือกใบขอซื้ออย่างน้อย 1 ใบ");
        }
        List<IrLock> irs = rounds.lockImportRequests(List.of(id),
            request.lots().stream().map(LotInput::importRequestId).filter(java.util.Objects::nonNull).distinct().toList());
        placeLots(id, round.factoryId(), request.lots(), irs);
        finishLotChange(irs, List.of(id), actor);
        return get(id, actor);
    }

    @Transactional
    public ShipmentRoundDto changeLot(long id, long lotId, ChangeLotRequest request, UserPrincipal actor) {
        requireWrite(actor);
        ShipmentRoundDto round = lockRound(id);
        requireNotLeft(round);
        List<IrLock> irs = rounds.lockImportRequestsOnRounds(List.of(id));
        var lot = requireLot(id, lotId);
        BigDecimal qty = positive(request == null ? null : request.qty());
        var item = rounds.itemQty(lot.itemId()).orElseThrow();
        if (item.placed().subtract(lot.qty()).add(qty).compareTo(item.qty()) > 0) {
            throw new ApiException(HttpStatus.CONFLICT, "จำนวนเกินที่สั่งในรายการนี้ (สั่ง " + item.qty().stripTrailingZeros().toPlainString() + ")");
        }
        rounds.setLotQty(lotId, qty);
        finishLotChange(irs, List.of(id), actor);
        return get(id, actor);
    }

    /** Takes a lot off the round (its quantity goes back to waiting). Returns the round, or null when it was deleted
     * because that was its last lot (a PORTAL round with no documents; any other round refuses instead). */
    @Transactional
    public ShipmentRoundDto removeLot(long id, long lotId, UserPrincipal actor) {
        requireWrite(actor);
        ShipmentRoundDto round = lockRound(id);
        requireNotLeft(round);
        List<IrLock> irs = rounds.lockImportRequestsOnRounds(List.of(id));
        requireLot(id, lotId);
        boolean last = rounds.lotCount(id) == 1;
        if (last) {
            requireRoundMayBeDeleted(round);
        }
        rounds.deleteLot(lotId);
        finishLotChange(irs, List.of(id), actor);
        if (last) {
            rounds.deleteRound(id);
            return null;
        }
        return roundOrNullIfHidden(id, actor);
    }

    /**
     * A lot recorded as exactly 0 received (on a RECEIVED round) goes back to รอจัดรอบ: nothing of it arrived, so it holds no
     * place. Any received quantity keeps the lot. Import/CEO only. Lock order: round, IRs, tickets. A round left with no lot
     * stays, still readable, when it holds data of its own (a received round normally does); else it goes and this returns null.
     * The IRs re-derive silently: a step that moves back writes no event (the lot was never really there).
     */
    @Transactional
    public ShipmentRoundDto returnLotToWaiting(long id, long lotId, UserPrincipal actor) {
        requireWrite(actor);
        ShipmentRoundDto round = lockRound(id);
        List<IrLock> irs = rounds.lockImportRequestsOnRounds(List.of(id));
        requireLot(id, lotId);
        var lot = rounds.lotsByRound(List.of(id)).getOrDefault(id, List.of()).stream().filter(l -> l.lotId() == lotId).findFirst().orElseThrow();
        if (!ImportRequestStep.RECEIVED.equals(round.importStep()) || lot.receivedQty() == null || lot.receivedQty().signum() != 0) {
            throw new ApiException(HttpStatus.CONFLICT, "คืนเข้ารอจัดรอบได้เฉพาะล็อตที่บันทึกว่าถึงโกดัง 0 ในรอบที่ถึงโกดังแล้ว");
        }
        boolean last = rounds.lotCount(id) == 1;
        rounds.flagLineLotReturned(lotId);
        rounds.deleteLot(lotId);
        rounds.clearUndo(id);
        rounds.clearIrUndo(irs.stream().map(IrLock::id).toList());
        // An IR left with no lot never shipped: back to the last step that was true, not "at customs" (silent, as below).
        String backTo = round.orderedOn() != null ? ImportRequestStep.ORDERED : ImportRequestStep.CONTACTED;
        List<IrLock> stillPlaced = new ArrayList<>();
        for (IrLock ir : irs) {
            if (!rounds.roundIdsOf(ir.id()).isEmpty()) {
                stillPlaced.add(ir);
                continue;
            }
            LocalDate on = round.orderedOn() != null ? round.orderedOn() : ir.firstIssuedDate();
            if (on != null && ir.firstIssuedDate() != null && on.isBefore(ir.firstIssuedDate())) {
                on = ir.firstIssuedDate();
            }
            stored.setDerivedStep(ir.id(), backTo, on, actor.id(), actor.name(), null);
        }
        List<StepChange> changes = recomputeIrSteps(stillPlaced, actor, null).stream()
            .filter(c -> ImportRequestStep.indexOf(c.to()) > ImportRequestStep.indexOf(c.from())).toList(); // a move back is silent
        applyAftermath(onActiveDeals(changes, irs), actor, null);
        rollUpAfterReceiptChange(irs.stream().filter(i -> i.id() == lot.importRequestId()).toList(), actor); // the 0 this lot held may have been the last wait
        if (last && !rounds.hasOwnData(id)) {
            rounds.deleteRound(id);
            return null;
        }
        return roundOrNullIfHidden(id, actor);
    }

    /** The normal rollup and notice, once per ACTIVE deal, when a receipt change may have completed it with no step change. */
    private void rollUpAfterReceiptChange(List<IrLock> irs, UserPrincipal actor) {
        java.util.Set<Long> done = new java.util.HashSet<>();
        irs.stream().filter(i -> i.ticketId() != null).sorted(Comparator.comparingLong(IrLock::ticketId)).forEach(i -> {
            if (done.add(i.ticketId()) && th.co.glr.hr.ticket.DealLifecycle.ACTIVE.equals(tickets.lockTicketForUpdate(i.ticketId()))) {
                irService.receiptCorrected(i.ticketId(), i.docNumber(), i.factoryName(), actor);
            }
        });
    }

    /** Locks the rounds, then the import requests, of a deal's ISSUED IRs (the order every writer here uses: rounds, IRs, tickets);
     * the caller then takes the ticket lock. Used by the not-delivered mark. */
    @Transactional
    public List<IrLock> lockTicketIrs(long ticketId) {
        List<Long> roundIds = rounds.roundIdsOfTicket(ticketId);
        rounds.lockRounds(roundIds);
        return rounds.lockImportRequests(roundIds, rounds.issuedIrIdsOfTicket(ticketId));
    }

    /** After a line was marked not delivered (or cleared): the deal's IRs re-derive (an IR with nothing left to receive is done);
     * the normal rollup and its one notice follow when that completes the deal. Locks as lockTicketIrs returned. */
    @Transactional
    public void rederiveAfterNotDelivered(List<IrLock> irs, long ticketItemId, UserPrincipal actor) {
        // an IR with no lots holds its own step: only the IR of the marked line moves, never a neighbour on the deal
        Set<Long> affected = rounds.irsWithLotsOrLine(irs.stream().map(IrLock::id).toList(), ticketItemId);
        List<IrLock> mine = irs.stream().filter(i -> affected.contains(i.id())).toList();
        applyAftermath(onActiveDeals(recomputeIrSteps(mine, actor, null), mine), actor, null);
    }

    /** After a line was linked to a deal line: ONLY that line's own IR re-derives (a shared round also holds other deals' IRs,
     * whose date, operator and note must not be rewritten); the normal rollup and its one notice follow when that completes the
     * deal. Locks as lockTicketIrs returned. */
    @Transactional
    public void rederiveAfterLink(List<IrLock> irs, long importRequestId, UserPrincipal actor) {
        List<IrLock> mine = irs.stream().filter(i -> i.id() == importRequestId).toList();
        applyAftermath(onActiveDeals(recomputeIrSteps(mine, actor, null), mine), actor, null);
    }

    /** The round for the closing read, or null when it is now held only by finished deals (hidden): the change stays
     * committed and the caller leaves the page, as for a deleted round. */
    private ShipmentRoundDto roundOrNullIfHidden(long id, UserPrincipal actor) {
        return rounds.onlyFinishedDeals(id) ? null : get(id, actor);
    }

    /** Moves a whole lot to another round of the same factory that has not left (rolled cargo, a missed container). A
     * lot of the same line already there is merged into. Returns the source round, or null when it was emptied and
     * deleted. */
    @Transactional
    public ShipmentRoundDto moveLot(long id, long lotId, MoveLotRequest request, UserPrincipal actor) {
        requireWrite(actor);
        if (request == null || request.toRoundId() == null || request.toRoundId() == id) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เลือกรอบปลายทาง");
        }
        long to = request.toRoundId();
        rounds.lockRounds(List.of(id, to));
        ShipmentRoundDto src = rounds.roundsWithLots(id).stream().findFirst().orElseThrow(this::notFound);
        ShipmentRoundDto dst = rounds.roundsWithLots(to).stream().findFirst().orElseThrow(this::notFound);
        requireNotLeft(src);
        requireNotLeft(dst);
        if (src.factoryId() == null || !src.factoryId().equals(dst.factoryId())) {
            throw new ApiException(HttpStatus.CONFLICT, "ย้ายล็อตได้เฉพาะระหว่างรอบของโรงงานเดียวกัน");
        }
        List<IrLock> irs = rounds.lockImportRequestsOnRounds(List.of(id, to));
        var lot = requireLot(id, lotId);
        if (irs.stream().noneMatch(i -> i.id() == lot.importRequestId() && i.ticketActive())) {
            throw new ApiException(HttpStatus.CONFLICT, "งานของใบขอซื้อนี้ไม่ได้อยู่ในสถานะใช้งาน ย้ายล็อตไม่ได้");
        }
        boolean last = rounds.lotCount(id) == 1;
        if (last) {
            requireRoundMayBeDeleted(src);
        }
        var there = rounds.lotOnRound(to, lot.itemId());
        if (there.isPresent()) {
            rounds.setLotQty(there.get().lotId(), there.get().qty().add(lot.qty()));
            rounds.deleteLot(lotId);
        } else {
            rounds.moveLotTo(lotId, to);
        }
        finishLotChange(irs, List.of(id, to), actor);
        if (last) {
            rounds.deleteRound(id);
            return null;
        }
        return roundOrNullIfHidden(id, actor);
    }

    /** Adds the lots to the round: checks every rule per line, merging into a lot of the same line already there. */
    private void placeLots(long roundId, Long factoryId, List<LotInput> inputs, List<IrLock> irs) {
        Map<Long, IrLock> byId = irs.stream().collect(Collectors.toMap(IrLock::id, Function.identity()));
        for (LotInput in : inputs) {
            IrLock ir = in.importRequestId() == null ? null : byId.get(in.importRequestId());
            if (ir == null || in.itemId() == null) {
                throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อนี้ไม่ได้อยู่ในสถานะที่จัดรอบได้");
            }
            if (!ir.ticketActive()) {
                throw new ApiException(HttpStatus.CONFLICT, "งานของใบขอซื้อ " + ir.docNumber() + " ไม่ได้อยู่ในสถานะใช้งาน");
            }
            if (factoryId == null || !factoryId.equals(ir.factoryId())) {
                throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อ " + ir.docNumber() + " เป็นของโรงงานอื่น");
            }
            BigDecimal qty = positive(in.qty());
            var item = rounds.itemQty(in.itemId())
                .filter(i -> i.importRequestId() == ir.id())
                .orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST, "รายการสินค้าไม่อยู่ในใบขอซื้อ " + ir.docNumber()));
            if (item.placed().add(qty).compareTo(item.qty()) > 0) {
                throw new ApiException(HttpStatus.CONFLICT, "จำนวนเกินที่เหลือของใบขอซื้อ " + ir.docNumber()
                    + " (เหลือ " + item.qty().subtract(item.placed()).stripTrailingZeros().toPlainString() + ")");
            }
            var there = rounds.lotOnRound(roundId, in.itemId());
            if (there.isPresent()) {
                rounds.setLotQty(there.get().lotId(), there.get().qty().add(qty));
            } else {
                rounds.insertLot(roundId, ir.id(), in.itemId(), qty);
            }
        }
    }

    /** After lots changed: the undo of any earlier advance is gone, and the IR steps follow their lots. */
    private void finishLotChange(List<IrLock> irs, List<Long> roundIds, UserPrincipal actor) {
        roundIds.forEach(rounds::clearUndo);
        rounds.clearIrUndo(irs.stream().map(IrLock::id).toList());
        applyAftermath(onActiveDeals(recomputeIrSteps(irs, actor, null), irs), actor, null);
    }

    private void requireNotLeft(ShipmentRoundDto round) {
        if (ImportRequestStep.indexOf(round.importStep()) >= LEFT_INDEX) {
            throw new ApiException(HttpStatus.CONFLICT,
                "รอบนี้ออกเดินทางแล้ว แก้ไขล็อตไม่ได้ (แก้ข้อมูลและเอกสารของรอบได้ที่ แก้ไข)");
        }
    }

    /** An emptied round of any source may go, but only when it holds nothing of its own (documents, typed facts). */
    private void requireRoundMayBeDeleted(ShipmentRoundDto round) {
        if (rounds.hasOwnData(round.id())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "ล็อตนี้เป็นล็อตสุดท้ายของรอบ และรอบนี้มีเอกสารหรือข้อมูลที่กรอกไว้ (เรือ เที่ยว ตัวแทน ตู้ วันที่ ฯลฯ) "
                    + "กรุณาลบข้อมูลเหล่านั้นหรือย้ายไปรอบใหม่ก่อน");
        }
    }

    private ShipmentRoundRepository.LotPlace requireLot(long roundId, long lotId) {
        return rounds.findLot(lotId).filter(l -> l.roundId() == roundId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบล็อตในรอบนี้"));
    }

    private static BigDecimal positive(BigDecimal qty) {
        BigDecimal q = qty == null ? null : qty.setScale(2, java.math.RoundingMode.HALF_UP);
        if (q == null || q.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "จำนวนต้องมากกว่า 0");
        }
        return q;
    }

    // ── documents (import and the CEO only: a factory invoice shows cost prices and other deals' lines) ──

    @Transactional
    public ShipmentRoundDto addDocument(long id, String kind, String docNo, LocalDate docDate, String note,
                                        MultipartFile file, UserPrincipal actor) {
        requireWrite(actor);
        String k = requireKind(kind);
        lockRound(id);
        String no = limit(blankToNull(docNo), 100, "เลขที่เอกสาร");
        boolean hasFile = file != null && !file.isEmpty();
        if (no == null && !hasFile) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องมีเลขที่เอกสารหรือไฟล์อย่างน้อยหนึ่งอย่าง");
        }
        endUndoWindow(id);
        long docId = rounds.insertDocument(id, k, no, docDate, limit(blankToNull(note), 255, "หมายเหตุ"), actor.id(), false);
        if (hasFile) {
            storeFile(id, docId, file);
        }
        return get(id, actor);
    }

    @Transactional
    public ShipmentRoundDto updateDocument(long id, long docId, DocumentRequest request, UserPrincipal actor) {
        requireWrite(actor);
        ShipmentRoundDto round = lockRound(id);
        var doc = requireDocument(id, docId);
        String no = limit(blankToNull(request.docNo()), 100, "เลขที่เอกสาร");
        if (no == null && doc.filePath() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องมีเลขที่เอกสารหรือไฟล์อย่างน้อยหนึ่งอย่าง");
        }
        requireNumbersKept(round, doc, no == null ? null : requireKind(request.kind()));
        endUndoWindow(id);
        rounds.updateDocument(docId, requireKind(request.kind()), no, request.docDate(),
            limit(blankToNull(request.note()), 255, "หมายเหตุ"));
        return get(id, actor);
    }

    @Transactional
    public ShipmentRoundDto deleteDocument(long id, long docId, UserPrincipal actor) {
        requireWrite(actor);
        ShipmentRoundDto round = lockRound(id);
        var doc = requireDocument(id, docId);
        requireNumbersKept(round, doc, null);
        endUndoWindow(id);
        rounds.deleteDocument(docId);
        files.deleteOnCommit(doc.filePath());
        return get(id, actor);
    }

    @Transactional
    public ShipmentRoundDto attachFile(long id, long docId, MultipartFile file, UserPrincipal actor) {
        requireWrite(actor);
        lockRound(id);
        var doc = requireDocument(id, docId);
        if (file == null || file.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ไฟล์ว่างเปล่า");
        }
        endUndoWindow(id);
        storeFile(id, docId, file);
        files.deleteOnCommit(doc.filePath());
        return get(id, actor);
    }

    @Transactional
    public ShipmentRoundDto removeFile(long id, long docId, UserPrincipal actor) {
        requireWrite(actor);
        lockRound(id);
        var doc = requireDocument(id, docId);
        if (doc.filePath() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์");
        }
        if (doc.docNo() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "เอกสารนี้ไม่มีเลขที่ ลบทั้งรายการแทนการลบไฟล์");
        }
        endUndoWindow(id);
        rounds.clearDocumentFile(docId);
        files.deleteOnCommit(doc.filePath());
        return get(id, actor);
    }

    public ShipmentRoundRepository.DocumentRow file(long id, long docId, UserPrincipal actor) {
        requireWrite(actor);
        var doc = requireDocument(id, docId);
        if (doc.filePath() == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "ไม่พบไฟล์");
        }
        return doc;
    }

    private void storeFile(long roundId, long docId, MultipartFile file) {
        FileStorageService.StoredFile saved = files.store("shipment-rounds", roundId, file, Set.of());
        files.deleteOnRollback(saved);
        rounds.setDocumentFile(docId, saved.fileName(), saved.filePath(), saved.fileSize(), saved.mimeType());
    }

    private ShipmentRoundRepository.DocumentRow requireDocument(long roundId, long docId) {
        return rounds.findDocument(roundId, docId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบเอกสาร"));
    }

    private static String blankToNull(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String limit(String v, int max, String what) {
        if (v != null && v.length() > max) {
            throw new ApiException(HttpStatus.BAD_REQUEST, what + "ยาวเกินไป (ไม่เกิน " + max + " ตัวอักษร)");
        }
        return v;
    }

    // ── derived IR step ─────────────────────────────────────────────────────────────────────────────────

    /** Writes the derived step on every locked IR whose step changed; returns what changed (ticket then IR order). */
    private List<StepChange> recomputeIrSteps(List<IrLock> irs, UserPrincipal actor, String note) {
        if (irs.isEmpty()) {
            return new ArrayList<>();
        }
        Map<Long, List<DerivedInput>> inputs = rounds.derivedInputs(irs.stream().map(IrLock::id).toList()).stream()
            .collect(Collectors.groupingBy(DerivedInput::importRequestId));
        List<StepChange> changes = new ArrayList<>();
        for (IrLock ir : irs) {
            ShipmentStepDerivation.Derived d = ShipmentStepDerivation.derive(ir, inputs.getOrDefault(ir.id(), List.of()));
            if (d.step().equals(ir.importStep())) {
                refreshStamp(ir, d); // same step from another round (regrouped): the stamp follows it, no step-change event
                continue;
            }
            ImportRequestDtos.ImportRequestDto before = irService.requireStored(ir.id());
            boolean own = d.byName() != null;
            stored.setDerivedStep(ir.id(), d.step(), d.at(), own ? d.byId() : actor.id(),
                own ? d.byName() : actor.name(), own ? d.note() : note);
            changes.add(new StepChange(before, ir.importStep(), d.step()));
        }
        changes.sort(Comparator.comparing((StepChange c) -> c.ir().ticketId(), Comparator.nullsLast(Comparator.<Long>naturalOrder()))
            .thenComparingLong(c -> c.ir().id()));
        return changes;
    }

    /** Writes the derived stamp (date, operator, note) of an IR whose step did not change, silently, when it differs. */
    private void refreshStamp(IrLock ir, ShipmentStepDerivation.Derived d) {
        if (d.byName() == null) {
            if (d.at() != null && !d.at().equals(ir.importStepAt())) {
                stored.setStepDate(ir.id(), d.at());
            }
            return;
        }
        if (!java.util.Objects.equals(d.at(), ir.importStepAt()) || !java.util.Objects.equals(d.byId(), ir.importStepById())
            || !java.util.Objects.equals(d.note(), ir.importStepNote())) {
            stored.setDerivedStep(ir.id(), d.step(), d.at(), d.byId(), d.byName(), d.note());
        }
    }

    /** A deal that is no longer ACTIVE keeps the derived IR step but gets no event, rollup or notice; so does a no-project
     * order, which has no deal (no ticket id): its step still follows its lots. */
    private List<StepChange> onActiveDeals(List<StepChange> changes, List<IrLock> irs) {
        if (changes.isEmpty()) {
            return changes;
        }
        // The lifecycle is read under the ticket's own lock (after the round and IR locks, tickets ascending), not from the
        // unlocked flag the IR lock query carried: a resume that commits meanwhile is seen, so no rollup is skipped.
        Set<Long> dead = new java.util.HashSet<>();
        irs.stream().map(IrLock::ticketId).filter(java.util.Objects::nonNull).distinct().sorted()
            .forEach(t -> {
                if (!th.co.glr.hr.ticket.DealLifecycle.ACTIVE.equals(tickets.lockTicketForUpdate(t))) {
                    dead.add(t);
                }
            });
        return changes.stream().filter(c -> c.ir().ticketId() != null && !dead.contains(c.ir().ticketId())).toList();
    }

    /** Returns the deals this completed (every factory received: the rollup ran and told the rep). */
    private Set<Long> applyAftermath(List<StepChange> changes, UserPrincipal actor, String note) {
        Set<Long> completed = new java.util.HashSet<>();
        changes.stream().collect(Collectors.groupingBy(c -> c.ir().ticketId(), java.util.TreeMap::new, Collectors.toList()))
            .forEach((ticketId, list) -> {
                if (irService.afterStepChanges(ticketId, list, actor, note)) {
                    completed.add(ticketId);
                }
            });
        return completed;
    }

    /** One bell per IR of a deal the rep owns when its lot reaches ถึงโกดัง, unless this advance completed the deal (the
     * existing all-received notice already says so: never both). No email. Dead deals hear nothing. */
    private void tellRepsLotsArrived(long roundId, List<IrLock> irs, Set<Long> completed) {
        Map<Long, IrLock> byId = irs.stream().collect(Collectors.toMap(IrLock::id, Function.identity()));
        rounds.lotsByRound(List.of(roundId)).getOrDefault(roundId, List.of()).stream()
            .collect(Collectors.groupingBy(ShipmentRoundDtos.ShipmentLotDto::importRequestId, java.util.TreeMap::new, Collectors.toList()))
            .forEach((irId, lots) -> {
                IrLock ir = byId.get(irId);
                if (ir == null || !ir.ticketActive() || (ir.ticketId() != null && completed.contains(ir.ticketId()))) {
                    return;
                }
                BigDecimal got = lots.stream().map(l -> l.receivedQty() != null ? l.receivedQty() : l.qty())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
                if (got.signum() == 0) {
                    return; // nothing of this IR actually arrived on this round
                }
                BigDecimal planned = lots.stream().map(ShipmentRoundDtos.ShipmentLotDto::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
                boolean oneUnit = lots.stream().map(l -> l.unit() == null ? "" : l.unit().trim()).distinct().count() == 1; // NULL and blank are one value
                String unit = lots.get(0).unit() == null || lots.get(0).unit().isBlank() ? "" : " " + lots.get(0).unit().trim();
                String qtyText = !oneUnit ? lots.size() + " รายการ"
                    : got.compareTo(planned) < 0
                        ? "ถึงโกดัง " + got.stripTrailingZeros().toPlainString() + " จาก " + planned.stripTrailingZeros().toPlainString() + unit
                        : got.stripTrailingZeros().toPlainString() + unit;
                if (ir.ticketId() == null) {
                    if (ir.requesterId() != null) {
                        irService.notifyRequesterOfReceivedLot(ir.requesterId(), ir.docNumber(), ir.factoryName(), qtyText.trim());
                    }
                    return;
                }
                irService.notifyRepOfReceivedLot(ir.ticketId(), ir.docNumber(), ir.factoryName(), qtyText.trim());
            });
    }

    /** Short arrival: 0 up to the lot's quantity, for lots of this round. Returns lotId -> qty (scaled to 2). */
    private Map<Long, BigDecimal> validateReceipts(long roundId, List<ShipmentRoundDtos.LotReceipt> receipts) {
        Map<Long, BigDecimal> out = new LinkedHashMap<>();
        if (receipts == null || receipts.isEmpty()) {
            return out;
        }
        Map<Long, BigDecimal> lotQty = rounds.lotsByRound(List.of(roundId)).getOrDefault(roundId, List.of()).stream()
            .collect(Collectors.toMap(ShipmentRoundDtos.ShipmentLotDto::lotId, ShipmentRoundDtos.ShipmentLotDto::qty));
        for (var r : receipts) {
            BigDecimal qty = r.qty() == null ? null : r.qty().setScale(2, java.math.RoundingMode.HALF_UP);
            BigDecimal ordered = r.lotId() == null ? null : lotQty.get(r.lotId());
            if (ordered == null) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ล็อตไม่อยู่ในรอบนี้");
            }
            if (qty == null || qty.signum() < 0 || qty.compareTo(ordered) > 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST,
                    "จำนวนที่ถึงโกดังต้องอยู่ระหว่าง 0 ถึงจำนวนของล็อต (" + ordered.stripTrailingZeros().toPlainString() + ")");
            }
            out.put(r.lotId(), qty);
        }
        return out;
    }

    private void applyReceipts(long roundId, Map<Long, BigDecimal> receipts) {
        if (receipts.isEmpty()) {
            return;
        }
        Map<Long, BigDecimal> lotQty = rounds.lotsByRound(List.of(roundId)).getOrDefault(roundId, List.of()).stream()
            .collect(Collectors.toMap(ShipmentRoundDtos.ShipmentLotDto::lotId, ShipmentRoundDtos.ShipmentLotDto::qty));
        receipts.forEach((lotId, qty) -> rounds.setLotReceived(lotId, qty.compareTo(lotQty.get(lotId)) == 0 ? null : qty));
        rounds.touch(roundId); // a receipt-only save must move the version the stale-editor check compares
    }

    // ── helpers ─────────────────────────────────────────────────────────────────────────────────────────

    /** An edit or a file change must not be discarded by the undo of an earlier advance. Rounds are locked already;
     * the IR rows are locked next, in order. */
    private List<IrLock> endUndoWindow(long id) {
        List<IrLock> irs = rounds.lockImportRequestsOnRounds(List.of(id));
        rounds.clearUndo(id);
        return irs;
    }

    private ShipmentRoundDto lockRound(long id) {
        rounds.lockRounds(List.of(id));
        return rounds.roundsWithLots(id).stream().findFirst().orElseThrow(() -> rounds.onlyFinishedDeals(id)
            ? new ApiException(HttpStatus.CONFLICT, "รอบนี้มีแต่งานที่ปิดหรือยกเลิกแล้ว จึงเลื่อนขั้นตอนหรือแก้ไขไม่ได้")
            : notFound());
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรอบขนส่ง");
    }

    /** The step date may not predate the first issue of any IR on the round. */
    private static void requireNotBeforeFirstIssue(LocalDate date, List<IrLock> irs) {
        if (date == null) {
            return;
        }
        for (IrLock ir : irs) {
            if (ir.firstIssuedDate() != null && date.isBefore(ir.firstIssuedDate())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "วันที่ของขั้นตอนต้องไม่ก่อนวันที่ออกใบขอซื้อ "
                    + ir.docNumber() + " ครั้งแรก (" + ir.firstIssuedDate() + ")");
            }
        }
    }

    /** Transport mode and the text limits of V239, for the facts editor and the advance alike. */
    private static void validateFacts(ShipmentFacts f) {
        if (f == null) {
            return;
        }
        if (f.transportMode() != null && !TRANSPORT_MODES.contains(f.transportMode())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ประเภทการขนส่งไม่ถูกต้อง");
        }
        limit(f.label(), 100, "ชื่อรอบ");
        limit(f.vessel(), 200, "ชื่อเรือ / เที่ยวบิน / ทะเบียนรถ");
        limit(f.voyage(), 100, "เที่ยว");
        limit(f.forwarder(), 200, "ตัวแทนขนส่ง");
        limit(f.containerNos(), 500, "เลขตู้คอนเทนเนอร์");
        limit(f.port(), 100, "ท่าเรือ");
        limit(f.grNumber(), 100, "เลขที่ใบตรวจรับ");
        limit(f.completeNote(), 500, "หมายเหตุของไม่ครบ");
        limit(f.note(), 1000, "หมายเหตุของรอบ");
    }

    /** A PORTAL round that is RECEIVED must keep a numbered proforma and a numbered invoice: refuses a change to or
     * deletion of a document that would leave none. {@code newKind} is the kind the document keeps with a number, or
     * null when it is deleted or loses its number. Exempt rounds are unaffected. */
    private void requireNumbersKept(ShipmentRoundDto round, ShipmentRoundRepository.DocumentRow doc, String newKind) {
        if (!"PORTAL".equals(round.source()) || !ImportRequestStep.RECEIVED.equals(round.importStep())) {
            return;
        }
        for (String kind : REQUIRED_NUMBERS_BY_STEP.getOrDefault(ImportRequestStep.RECEIVED, List.of())) {
            int left = rounds.numberedDocuments(round.id(), kind)
                - (doc.docNo() != null && kind.equals(doc.kind()) ? 1 : 0) + (kind.equals(newKind) ? 1 : 0);
            if (left == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "รอบนี้ถึงโกดังแล้ว ต้องมีเลขที่ " + KIND_LABEL.get(kind)
                    + " อย่างน้อย 1 ฉบับ จึงลบหรือเปลี่ยนเอกสารนี้ไม่ได้");
            }
        }
    }

    /** The flag and note the round will hold: the payload's where it gives one, else the stored (an advance keeps what it omits). */
    private void requireNoteWhenIncomplete(ShipmentFacts f, ShipmentFacts stored) {
        Boolean flag = f != null && f.completePerInvoice() != null ? f.completePerInvoice() : stored == null ? null : stored.completePerInvoice();
        String note = f != null && f.completeNote() != null ? f.completeNote() : stored == null ? null : stored.completeNote();
        if (Boolean.FALSE.equals(flag) && (note == null || note.isBlank())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ของไม่ครบตามอินวอยซ์ กรุณาพิมพ์หมายเหตุ");
        }
    }

    /** Checked BEFORE anything is written: documents already on the round plus those typed on this advance. */
    private void requireNumbers(ShipmentRoundDto round, List<DocumentInput> typed, String target) {
        if (!"PORTAL".equals(round.source())) {
            return;
        }
        for (String kind : REQUIRED_NUMBERS_BY_STEP.getOrDefault(target, List.of())) {
            boolean typedHere = typed != null && typed.stream()
                .anyMatch(d -> kind.equalsIgnoreCase(d.kind()) && blankToNull(d.docNo()) != null);
            if (!typedHere && rounds.numberedDocuments(round.id(), kind) == 0) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องมีเลขที่ " + KIND_LABEL.get(kind)
                    + " อย่างน้อย 1 ฉบับ ก่อนเลื่อนไปขั้นตอน " + ImportRequestStep.thaiLabel(target));
            }
        }
    }

    private static String requireKind(String kind) {
        String k = kind == null ? "" : kind.toUpperCase();
        if (!DOCUMENT_KINDS.contains(k)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ประเภทเอกสารไม่ถูกต้อง");
        }
        return k;
    }

    private void requireRead(UserPrincipal actor) {
        if (!ImportRequestService.ADVANCE_STEP_ROLES.contains(actor.role())
            && !OversightReader.is(actor, tickets::holdsReadAllSalesGrant)
            && !isWarehouseManager(actor)) { // the outbound leg's planner reads the inbound rounds (documents stay withheld, see assemble)
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private boolean isWarehouseManager(UserPrincipal actor) {
        return WarehouseStaff.is(actor) && WarehouseStaff.managerRecipientIds(employees).contains(actor.id());
    }

    private static void requireWrite(UserPrincipal actor) {
        if (!ImportRequestService.ADVANCE_STEP_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /** Lots and files joined on, and the stored advance stamp (undoUntil) turned into the undo deadline. */
    private List<ShipmentRoundDto> assemble(List<ShipmentRoundDto> base, UserPrincipal actor) {
        // A factory invoice shows cost prices and other deals' lines: only import and the CEO receive the documents
        // (numbers, notes, file names); the sales manager and read-all holders read the round without them.
        boolean documents = ImportRequestService.ADVANCE_STEP_ROLES.contains(actor.role());
        List<Long> ids = base.stream().map(ShipmentRoundDto::id).toList();
        var lots = rounds.lotsByRound(ids);
        var docMap = rounds.documentsByRound(ids);
        return base.stream().map(r -> new ShipmentRoundDto(r.id(), r.factoryId(), r.factoryName(), r.source(), r.importStep(),
            r.contactedOn(), r.orderedOn(), r.pickedUpOn(), r.inTransitOn(), r.awaitingCustomsOn(), r.receivedOn(),
            r.stepByName(), r.stepNote(), r.facts(), lots.getOrDefault(r.id(), List.of()),
            documents ? docMap.getOrDefault(r.id(), List.of()) : List.of(),
            r.undoUntil() == null ? null : r.undoUntil().plusSeconds(ImportRequestService.STEP_UNDO_WINDOW_SECONDS),
            r.undoById(), r.undoFromStep(), r.advanceId(), r.updatedAt())).toList();
    }
}
