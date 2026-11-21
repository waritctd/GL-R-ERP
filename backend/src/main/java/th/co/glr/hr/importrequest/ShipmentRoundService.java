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
import th.co.glr.hr.auth.OversightReader;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.ImportRequestService.StepChange;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.AdvanceShipmentRoundRequest;
import th.co.glr.hr.importrequest.ShipmentRoundDtos.DocumentInput;
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
    private Clock clock = Clock.systemUTC();

    public ShipmentRoundService(ShipmentRoundRepository rounds, ImportRequestRepository stored,
                                ImportRequestService irService, TicketRepository tickets, FileStorageService files) {
        this.rounds = rounds;
        this.stored = stored;
        this.irService = irService;
        this.tickets = tickets;
        this.files = files;
    }

    void useClock(Clock clock) {
        this.clock = clock;
    }

    // ── reads ───────────────────────────────────────────────────────────────────────────────────────────

    public ShipmentRoundsResponse list(UserPrincipal actor) {
        requireRead(actor);
        return new ShipmentRoundsResponse(assemble(rounds.roundsWithLots(null)), rounds.waiting());
    }

    public ShipmentRoundDto get(long id, UserPrincipal actor) {
        requireRead(actor);
        return assemble(rounds.roundsWithLots(id)).stream().findFirst().orElseThrow(this::notFound);
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
        validateFacts(facts);
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
        if (facts != null) {
            rounds.applyFacts(id, facts, true);
        }
        if (!dates.isEmpty()) {
            rounds.setStepDates(id, dates);
            Map<Long, List<DerivedInput>> inputs = rounds.derivedInputs(irs.stream().map(IrLock::id).toList()).stream()
                .collect(Collectors.groupingBy(DerivedInput::importRequestId));
            for (IrLock ir : irs) {
                Derived d = derive(ir, inputs.getOrDefault(ir.id(), List.of()));
                if (d.step().equals(ir.importStep()) && d.at() != null && !d.at().equals(ir.importStepAt())) {
                    stored.setStepDate(ir.id(), d.at());
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
        if (ImportRequestStep.RECEIVED.equals(target)) {
            requireNoteWhenIncomplete(request.facts());
        }
        requireNumbers(round, request.documents(), target);
        rounds.advanceRound(id, target, eventDate, actor.id(), actor.name(), request.note(), request.facts());
        rounds.finalizeDocuments(id);
        for (DocumentInput d : request.documents() == null ? List.<DocumentInput>of() : request.documents()) {
            String no = blankToNull(d.docNo());
            if (no != null) {
                rounds.insertDocument(id, requireKind(d.kind()), limit(no, 100, "เลขที่เอกสาร"), null, null, actor.id(), true);
            }
        }

        List<StepChange> changes = recomputeIrSteps(irs, actor, request.note());
        applyAftermath(onActiveDeals(changes, irs), actor, request.note());
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
        if (rounds.undoRound(id, expected, actor.id(), ImportRequestService.STEP_UNDO_WINDOW_SECONDS) == 0) {
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

    record Derived(String step, LocalDate at, Long byId, String byName, String note) {}

    /**
     * An IR's step from its lots: the slowest round's step, CONTACTED while any quantity is on no round, and RECEIVED
     * only when every line's full quantity sits on RECEIVED rounds (which falls out of "slowest" once nothing is
     * unplaced). The date is that step's date on the slowest round; while quantity waits it keeps the IR's own
     * CONTACTED stamp (or its first issue date).
     */
    static Derived derive(IrLock ir, List<DerivedInput> rows) {
        Map<Long, BigDecimal> placed = new LinkedHashMap<>();
        Map<Long, BigDecimal> wanted = new LinkedHashMap<>();
        DerivedInput slowest = null;
        for (DerivedInput r : rows) {
            wanted.put(r.itemId(), r.itemQty());
            placed.merge(r.itemId(), r.lotQty() == null ? BigDecimal.ZERO : r.lotQty(), BigDecimal::add);
            if (r.roundStep() != null && (slowest == null
                || ImportRequestStep.indexOf(r.roundStep()) < ImportRequestStep.indexOf(slowest.roundStep()))) {
                slowest = r;
            }
        }
        boolean unplaced = slowest == null || wanted.entrySet().stream()
            .anyMatch(e -> placed.get(e.getKey()).compareTo(e.getValue()) < 0);
        if (unplaced) {
            LocalDate at = ImportRequestStep.CONTACTED.equals(ir.importStep()) && ir.importStepAt() != null
                ? ir.importStepAt() : ir.firstIssuedDate();
            return ImportRequestStep.CONTACTED.equals(ir.importStep())
                ? new Derived(ImportRequestStep.CONTACTED, at, ir.importStepById(), ir.importStepByName(), ir.importStepNote())
                : new Derived(ImportRequestStep.CONTACTED, at, null, null, null);
        }
        return new Derived(slowest.roundStep(), slowest.roundStepOn() != null ? slowest.roundStepOn() : ir.importStepAt(),
            slowest.stepById(), slowest.stepByName(), slowest.stepNote());
    }

    /** Writes the derived step on every locked IR whose step changed; returns what changed (ticket then IR order). */
    private List<StepChange> recomputeIrSteps(List<IrLock> irs, UserPrincipal actor, String note) {
        Map<Long, List<DerivedInput>> inputs = rounds.derivedInputs(irs.stream().map(IrLock::id).toList()).stream()
            .collect(Collectors.groupingBy(DerivedInput::importRequestId));
        List<StepChange> changes = new ArrayList<>();
        for (IrLock ir : irs) {
            Derived d = derive(ir, inputs.getOrDefault(ir.id(), List.of()));
            if (d.step().equals(ir.importStep())) {
                continue;
            }
            ImportRequestDtos.ImportRequestDto before = irService.requireStored(ir.id());
            boolean own = d.byName() != null;
            stored.setDerivedStep(ir.id(), d.step(), d.at(), own ? d.byId() : actor.id(),
                own ? d.byName() : actor.name(), own ? d.note() : note);
            changes.add(new StepChange(before, ir.importStep(), d.step()));
        }
        changes.sort(Comparator.comparingLong((StepChange c) -> c.ir().ticketId()).thenComparingLong(c -> c.ir().id()));
        return changes;
    }

    /** A deal that is no longer ACTIVE keeps the derived IR step but gets no event, rollup or notice. */
    private static List<StepChange> onActiveDeals(List<StepChange> changes, List<IrLock> irs) {
        Set<Long> dead = irs.stream().filter(i -> !i.ticketActive()).map(IrLock::ticketId).collect(Collectors.toSet());
        return changes.stream().filter(c -> !dead.contains(c.ir().ticketId())).toList();
    }

    private void applyAftermath(List<StepChange> changes, UserPrincipal actor, String note) {
        changes.stream().collect(Collectors.groupingBy(c -> c.ir().ticketId(), java.util.TreeMap::new, Collectors.toList()))
            .forEach((ticketId, list) -> irService.afterStepChanges(ticketId, list, actor, note));
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
        return rounds.roundsWithLots(id).stream().findFirst().orElseThrow(this::notFound);
    }

    private ApiException notFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรอบขนส่ง");
    }

    /** The step date may not predate the first issue of any IR on the round. */
    private static void requireNotBeforeFirstIssue(LocalDate date, List<IrLock> irs) {
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

    private void requireNoteWhenIncomplete(ShipmentFacts f) {
        if (f != null && Boolean.FALSE.equals(f.completePerInvoice())
            && (f.completeNote() == null || f.completeNote().isBlank())) {
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
            && !OversightReader.is(actor, tickets::holdsReadAllSalesGrant)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private static void requireWrite(UserPrincipal actor) {
        if (!ImportRequestService.ADVANCE_STEP_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    /** Lots and files joined on, and the stored advance stamp (undoUntil) turned into the undo deadline. */
    private List<ShipmentRoundDto> assemble(List<ShipmentRoundDto> base) {
        List<Long> ids = base.stream().map(ShipmentRoundDto::id).toList();
        var lots = rounds.lotsByRound(ids);
        var docMap = rounds.documentsByRound(ids);
        return base.stream().map(r -> new ShipmentRoundDto(r.id(), r.factoryId(), r.factoryName(), r.source(), r.importStep(),
            r.contactedOn(), r.orderedOn(), r.pickedUpOn(), r.inTransitOn(), r.awaitingCustomsOn(), r.receivedOn(),
            r.stepByName(), r.stepNote(), r.facts(), lots.getOrDefault(r.id(), List.of()),
            docMap.getOrDefault(r.id(), List.of()),
            r.undoUntil() == null ? null : r.undoUntil().plusSeconds(ImportRequestService.STEP_UNDO_WINDOW_SECONDS),
            r.undoById(), r.undoFromStep())).toList();
    }
}
