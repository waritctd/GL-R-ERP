package th.co.glr.hr.importrequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.DivisionAccessPolicy;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.EmployeeLoginRecord;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.common.ThaiText;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportJobDtos.BackfillRunSummary;
import th.co.glr.hr.importrequest.ImportJobDtos.CreateImportJobRequest;
import th.co.glr.hr.importrequest.ImportJobDtos.ImportJobDto;
import th.co.glr.hr.importrequest.ImportJobDtos.ItemRequest;
import th.co.glr.hr.importrequest.ImportJobDtos.JobHistory;
import th.co.glr.hr.importrequest.ImportJobDtos.JobItem;
import th.co.glr.hr.importrequest.ImportJobDtos.JobLine;
import th.co.glr.hr.importrequest.ImportJobDtos.JobLot;
import th.co.glr.hr.importrequest.ImportJobDtos.NeedsCheck;
import th.co.glr.hr.importrequest.ImportJobDtos.RoundChoice;
import th.co.glr.hr.importrequest.ImportJobDtos.UpdateImportJobRequest;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestItemDto;
import th.co.glr.hr.importrequest.ImportRequestRepository.NewImportJob;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.IrLock;
import th.co.glr.hr.notification.NotificationRepository;

/**
 * งานนำเข้า: import and the CEO create and correct an issued import request that has no sales job (spec 0001). A job that
 * belongs to a sales job is never changed here. Every create and edit leaves a row in the append only audit log.
 */
@Service
public class ImportJobService {

    static final Map<String, String> PURPOSE_LABELS = Map.of("CUSTOMER", "งานลูกค้า", "SAMPLE", "ตัวอย่าง", "SHOWROOM", "โชว์รูม",
        "STOCK", "สต็อก");
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final BigDecimal MAX_QTY = new BigDecimal("9999999999.99");
    private static final Pattern DOC_NUMBER = Pattern.compile("^IR\\d{5,6}(-\\d{1,2})?$");
    private static final Set<String> CLEARABLE = Set.of("requesterId", "depositReceivedOn", "wantedBy");
    private static final String AUDIT_ENTITY = "import_request";

    private final ImportJobRepository jobs;
    private final ImportRequestRepository stored;
    private final ImportRequestService irService;
    private final ShipmentRoundRepository rounds;
    private final ShipmentRoundService roundService;
    private final ImportSheetRepository sheetRuns;
    private final FactoryConfigRepository factories;
    private final EmployeeAuthRepository employees;
    private final NotificationRepository notifications;
    private final AuditService audit;
    private final ObjectMapper json;

    public ImportJobService(ImportJobRepository jobs, ImportRequestRepository stored, ImportRequestService irService,
                            ShipmentRoundRepository rounds, ShipmentRoundService roundService, ImportSheetRepository sheetRuns,
                            FactoryConfigRepository factories, EmployeeAuthRepository employees,
                            NotificationRepository notifications, AuditService audit, ObjectMapper json) {
        this.jobs = jobs;
        this.stored = stored;
        this.irService = irService;
        this.rounds = rounds;
        this.roundService = roundService;
        this.sheetRuns = sheetRuns;
        this.factories = factories;
        this.employees = employees;
        this.notifications = notifications;
        this.audit = audit;
        this.json = json;
    }

    private static boolean canWrite(UserPrincipal actor) {
        return ImportRequestService.ADVANCE_STEP_ROLES.contains(actor.role());
    }

    private static void requireWrite(UserPrincipal actor) {
        if (!canWrite(actor)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    // ── read ────────────────────────────────────────────────────────────────────────────────────────────

    /** The same read rule as any no-job import request. */
    public ImportJobDto get(long id, UserPrincipal actor) {
        ImportRequestDto row = irService.get(id, actor);
        if (row.ticketId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อนี้อยู่ในงานขาย แก้ไขในรายละเอียดของงานนั้น");
        }
        return dto(row, actor);
    }

    private ImportJobDto dto(ImportRequestDto row, UserPrincipal actor) {
        var meta = stored.findNoProjectMeta(row.id()).orElseThrow();
        Map<Long, List<JobLot>> lots = jobs.lotsByItem(row.id());
        List<JobItem> items = new ArrayList<>();
        for (ImportRequestItemDto i : row.items()) {
            List<JobLot> own = lots.getOrDefault(i.id(), List.of());
            items.add(new JobItem(i.id(), i.seq(), i.code(), i.size(), i.qty(), i.unit(), i.note(), anyReceived(own), own));
        }
        List<JobHistory> history = new ArrayList<>(jobs.history(row.id()));
        if ("BACKFILL".equals(meta.source())) {
            history.add(new JobHistory(jobs.createdAt(row.id()), "ระบบ", "IMPORT_JOB_BACKFILLED", null, null));
        }
        return new ImportJobDto(row, meta.source(), meta.purpose(), meta.purposeNote(), jobs.requesterOf(row.id()), meta.wantedBy(),
            items, jobs.roundsOf(row.id()), history, canWrite(actor));
    }

    private static boolean anyReceived(List<JobLot> lots) {
        return lots.stream().anyMatch(l -> ImportRequestStep.RECEIVED.equals(l.step()));
    }

    /** Who a job can be named for: active people in the sales division, or holding the quotation right. */
    public List<ImportJobDtos.RepOption> repOptions(UserPrincipal actor) {
        requireWrite(actor);
        return employees.findAllActive().stream()
            .filter(e -> List.of("sales", "sales_manager").contains(DivisionAccessPolicy.roleFor(e)))
            .map(e -> new ImportJobDtos.RepOption(e.employeeId(), e.name())).toList();
    }

    /** What the sheet load left for import to check. Empty when no load has run. */
    public Optional<BackfillRunSummary> latestRun(UserPrincipal actor) {
        requireWrite(actor);
        return sheetRuns.latestFinishedRun().map(r -> {
            List<NeedsCheck> check = new ArrayList<>();
            try {
                JsonNode s = json.readTree(r.summaryJson());
                for (String kind : List.of("excluded", "skipped", "failed")) {
                    for (JsonNode n : s.path(kind)) {
                        check.add(new NeedsCheck(n.path("row").asInt(), n.hasNonNull("what") ? n.get("what").asText()
                            : n.path("docNumber").asText(), n.path("reason").asText()));
                    }
                }
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Unreadable backfill summary", e);
            }
            check.sort(java.util.Comparator.comparingInt(NeedsCheck::row));
            return new BackfillRunSummary(r.finishedAt(), r.roundsCreated(), r.irsCreated(), r.irsAdopted(), r.irsSkipped(),
                r.blocksExcluded(), r.dealsAdvanced(), check);
        });
    }

    // ── create ──────────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public ImportJobDto create(CreateImportJobRequest request, UserPrincipal actor) {
        requireWrite(actor);
        if (request == null) {
            throw bad("ไม่มีข้อมูลงานนำเข้า");
        }
        String purpose = requirePurpose(request.purpose());
        String note = requireNote(request.purposeNote());
        FactoryConfigDto factory = requireFactory(request.factoryId());
        LocalDate today = LocalDate.now(BANGKOK);
        LocalDate received = requireReceived(request.receivedOn(), today);
        String requesterName = requesterName(request.requesterId());
        if (request.lines() == null || request.lines().isEmpty()) {
            throw bad("ใส่สินค้าอย่างน้อย 1 รายการ");
        }
        List<JobLine> lines = request.lines().stream().map(ImportJobService::cleanLine).toList();
        RoundChoice round = request.round();
        String mode = round == null || round.mode() == null ? "NONE" : round.mode();
        if (!Set.of("NONE", "EXISTING", "NEW").contains(mode)) {
            throw bad("เลือกรอบขนส่งไม่ถูกต้อง");
        }
        Long roundId = null;
        if ("EXISTING".equals(mode)) {
            roundId = requireJoinableRound(round.roundId(), factory.id());
        }

        String doc = request.docNumber() == null || request.docNumber().isBlank()
            ? stored.nextDocNumber(today.getYear() + 543) : requireFreeNumber(request.docNumber());
        stored.raiseDocSequence(doc);
        long id = stored.insertImportJob(new NewImportJob(factory.id(), factory.factoryName(), purpose, PURPOSE_LABELS.get(purpose),
            request.requesterId(), requesterName, note, request.wantedBy(),
            request.wantedBy() == null ? null : ThaiText.date(request.wantedBy()), doc, received, request.depositReceivedOn(),
            actor.id(), actor.name(), actor.email(), "IMPORT"));
        List<Long> itemIds = new ArrayList<>();
        for (JobLine l : lines) {
            itemIds.add(stored.appendItem(id, l.code(), l.size(), l.qty(), l.unit(), null));
        }
        if ("NEW".equals(mode)) {
            roundId = rounds.createPlannedRound(factory.id(), factory.factoryName(), null, null, today, actor.id(), actor.name());
        }
        if (roundId != null) {
            for (int i = 0; i < lines.size(); i++) {
                rounds.insertLot(roundId, id, itemIds.get(i), lines.get(i).qty());
            }
            List<IrLock> locked = rounds.lockImportRequests(List.of(roundId), List.of(id));
            roundService.settleImportJob(locked, List.of(roundId), actor);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("docNumber", doc);
        after.put("purpose", purpose);
        after.put("purposeNote", note);
        after.put("factory", factory.factoryName());
        after.put("lines", lines.size());
        audit.record(actor, "IMPORT_JOB_CREATED", AUDIT_ENTITY, id, null, after);
        if (request.requesterId() != null && request.requesterId() != actor.id()) {
            notifications.notifyEmployeeBellOnly(request.requesterId(), "IMPORT_JOB_CREATED",
                "ฝ่ายนำเข้าสร้างงานนำเข้า " + doc + " ให้คุณ", "/my-orders");
        }
        return dto(stored.findById(id).orElseThrow(), actor);
    }

    private Long requireJoinableRound(Long roundId, long factoryId) {
        if (roundId == null) {
            throw bad("เลือกรอบขนส่ง");
        }
        rounds.lockRounds(List.of(roundId));
        var round = rounds.roundsWithLots(roundId).stream().findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.CONFLICT, "ไม่พบรอบขนส่งนี้"));
        if (!Objects.equals(round.factoryId(), factoryId)) {
            throw new ApiException(HttpStatus.CONFLICT, "รอบนี้เป็นของโรงงานอื่น");
        }
        if (ImportRequestStep.RECEIVED.equals(round.importStep())) {
            throw new ApiException(HttpStatus.CONFLICT, "รอบนี้ถึงโกดังแล้ว เลือกรอบอื่นหรือสร้างรอบใหม่");
        }
        return roundId;
    }

    // ── edit the job ────────────────────────────────────────────────────────────────────────────────────

    /** The job's rounds, then its import requests, locked in the order the round service uses. */
    private record Locked(ImportRequestDto row, List<IrLock> irs, List<Long> roundIds) {}

    private Locked lockJob(long id) {
        List<Long> roundIds = rounds.roundIdsOf(id);
        rounds.lockRounds(roundIds);
        List<IrLock> irs = rounds.lockImportRequests(roundIds, List.of(id));
        ImportRequestDto row = stored.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนำเข้านี้"));
        if (row.ticketId() != null) {
            throw new ApiException(HttpStatus.CONFLICT, "ใบขอซื้อนี้อยู่ในงานขาย แก้ไขในรายละเอียดของงานนั้น");
        }
        if (!ImportRequestStatus.ISSUED.equals(row.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "งานนำเข้านี้ถูกยกเลิกแล้ว");
        }
        return new Locked(row, irs, roundIds);
    }

    @Transactional
    public ImportJobDto update(long id, UpdateImportJobRequest request, UserPrincipal actor) {
        requireWrite(actor);
        if (request == null) {
            throw bad("ไม่มีข้อมูลที่จะแก้ไข");
        }
        Locked job = lockJob(id);
        ImportRequestDto row = job.row();
        var meta = stored.findNoProjectMeta(id).orElseThrow();
        List<String> clear = request.clear() == null ? List.of() : request.clear();
        if (!CLEARABLE.containsAll(clear)) {
            throw bad("ล้างข้อมูลช่องนี้ไม่ได้");
        }
        boolean rawSalesOrder = "PORTAL".equals(meta.source());
        if (rawSalesOrder && (clear.contains("requesterId") || clear.contains("wantedBy"))) {
            throw bad("คำขอที่ฝ่ายขายสั่งต้องมีผู้ขอและวันที่ต้องการของ");
        }

        String purpose = request.purpose() == null ? meta.purpose() : requirePurpose(request.purpose());
        String note = request.purposeNote() == null ? meta.purposeNote() : requireNote(request.purposeNote());

        Long requester = clear.contains("requesterId") ? null : request.requesterId() != null ? request.requesterId() : jobs.requesterOf(id);
        String requesterName = row.requestedByName();
        if (clear.contains("requesterId")) {
            requesterName = null;
        } else if (request.requesterId() != null) {
            requesterName = requesterName(request.requesterId());
        }

        LocalDate received = row.firstIssuedDate();
        // the form resends this date on every save: a sheet date that was never touched is not re-judged
        if (request.receivedOn() != null && !request.receivedOn().equals(row.firstIssuedDate())) {
            received = requireReceived(request.receivedOn(), LocalDate.now(BANGKOK));
            LocalDate earliest = jobs.earliestStepDate(id);
            if (earliest != null && received.isAfter(earliest)) {
                throw bad("วันที่ได้รับใบขอซื้อต้องไม่หลังวันที่ของขั้นตอนแรกในรอบขนส่ง (" + ThaiText.date(earliest) + ")");
            }
        }
        LocalDate deposit = clear.contains("depositReceivedOn") ? null
            : request.depositReceivedOn() != null ? request.depositReceivedOn() : row.depositReceivedDate();
        LocalDate wanted = clear.contains("wantedBy") ? null : request.wantedBy() != null ? request.wantedBy() : meta.wantedBy();
        String wantedText = Objects.equals(wanted, meta.wantedBy()) ? row.requiredByNote()
            : wanted == null ? null : ThaiText.date(wanted);

        String doc = row.docNumber();
        if (request.docNumber() != null && !normalizeDoc(request.docNumber()).equals(row.docNumber())) {
            doc = requireFreeNumber(request.docNumber());
            stored.raiseDocSequence(doc);
        }

        long factoryId = row.factoryId();
        String factoryName = row.factoryName();
        if (request.factoryId() != null && request.factoryId() != factoryId) {
            if (!job.roundIds().isEmpty()) {
                throw new ApiException(HttpStatus.CONFLICT, "งานนี้อยู่ในรอบขนส่งแล้ว เอาออกจากรอบก่อนจึงเปลี่ยนโรงงานได้");
            }
            FactoryConfigDto f = requireFactory(request.factoryId());
            factoryId = f.id();
            factoryName = f.factoryName();
        }

        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        diff(before, after, "purpose", meta.purpose(), purpose);
        diff(before, after, "purposeNote", meta.purposeNote(), note);
        diff(before, after, "requester", row.requestedByName(), requesterName);
        diff(before, after, "receivedOn", row.firstIssuedDate(), received);
        diff(before, after, "depositReceivedOn", row.depositReceivedDate(), deposit);
        diff(before, after, "wantedBy", meta.wantedBy(), wanted);
        diff(before, after, "docNumber", row.docNumber(), doc);
        diff(before, after, "factory", row.factoryName(), factoryName);
        if (!Objects.equals(requester, jobs.requesterOf(id)) && !after.containsKey("requester")) {
            // two people can share a display name: the id is what changed
            before.put("requester", row.requestedByName());
            after.put("requester", requesterName);
        }
        if (after.isEmpty() && before.isEmpty()) {
            return dto(row, actor);
        }

        jobs.updateHeader(id, new ImportJobRepository.JobHeader(purpose, PURPOSE_LABELS.get(purpose), note, requester, requesterName,
            received, deposit, wanted, wantedText, doc, factoryId, factoryName));
        if (!Objects.equals(received, row.firstIssuedDate()) && !job.roundIds().isEmpty()) {
            roundService.settleImportJob(rounds.lockImportRequests(job.roundIds(), List.of(id)), job.roundIds(), actor);
        }
        audit.record(actor, "IMPORT_JOB_UPDATED", AUDIT_ENTITY, id, before, after);
        return dto(stored.findById(id).orElseThrow(), actor);
    }

    private static void diff(Map<String, Object> before, Map<String, Object> after, String field, Object was, Object now) {
        if (!Objects.equals(was, now)) {
            before.put(field, was == null ? null : String.valueOf(was));
            after.put(field, now == null ? null : String.valueOf(now));
        }
    }

    // ── items ───────────────────────────────────────────────────────────────────────────────────────────

    @Transactional
    public ImportJobDto addItem(long id, ItemRequest request, UserPrincipal actor) {
        requireWrite(actor);
        Locked job = lockJob(id);
        JobLine line = cleanLine(request == null ? null : new JobLine(request.code(), request.size(), request.qty(), request.unit()));
        stored.appendItem(id, line.code(), line.size(), line.qty(), line.unit(), null);
        if (!job.roundIds().isEmpty()) {
            roundService.settleImportJob(job.irs(), job.roundIds(), actor);
        }
        Map<String, Object> after = new LinkedHashMap<>();
        after.put("item", line.code());
        after.put("qty", line.qty().toPlainString());
        after.put("unit", line.unit());
        audit.record(actor, "IMPORT_JOB_ITEM_ADDED", AUDIT_ENTITY, id, null, after);
        return dto(stored.findById(id).orElseThrow(), actor);
    }

    @Transactional
    public ImportJobDto updateItem(long id, long itemId, ItemRequest request, UserPrincipal actor) {
        requireWrite(actor);
        if (request == null) {
            throw bad("ไม่มีข้อมูลที่จะแก้ไข");
        }
        Locked job = lockJob(id);
        ImportRequestItemDto item = requireItem(job.row(), itemId);
        List<JobLot> lots = jobs.lotsByItem(id).getOrDefault(itemId, List.of());
        JobLine line = cleanLine(new JobLine(request.code() != null ? request.code() : item.code(),
            request.size() != null ? request.size() : item.size(), request.qty() != null ? request.qty() : item.qty(),
            request.unit() != null ? request.unit() : item.unit()));

        boolean qtyChanged = line.qty().compareTo(item.qty()) != 0;
        if (qtyChanged) {
            if (anyReceived(lots)) {
                throw new ApiException(HttpStatus.CONFLICT, "ของรายการนี้ถึงโกดังแล้ว แก้จำนวนไม่ได้");
            }
            BigDecimal placed = lots.stream().map(JobLot::qty).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (lots.size() == 1 && lots.get(0).qty().compareTo(item.qty()) == 0) {
                rounds.setLotQty(lots.get(0).lotId(), line.qty());
            } else if (line.qty().compareTo(placed) < 0) {
                throw new ApiException(HttpStatus.CONFLICT, "จำนวนต้องไม่น้อยกว่าที่จัดลงรอบแล้ว ("
                    + placed.stripTrailingZeros().toPlainString() + ")");
            }
        }
        jobs.updateItem(id, itemId, line.code(), line.size(), line.qty(), line.unit());
        if (qtyChanged && !job.roundIds().isEmpty()) {
            roundService.settleImportJob(job.irs(), job.roundIds(), actor);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        Map<String, Object> after = new LinkedHashMap<>();
        diff(before, after, "code", item.code(), line.code());
        diff(before, after, "size", item.size(), line.size());
        if (qtyChanged) {
            before.put("qty", item.qty().stripTrailingZeros().toPlainString());
            after.put("qty", line.qty().stripTrailingZeros().toPlainString());
        }
        diff(before, after, "unit", item.unit(), line.unit());
        if (!after.isEmpty() || !before.isEmpty()) {
            before.put("item", item.code());
            audit.record(actor, "IMPORT_JOB_ITEM_UPDATED", AUDIT_ENTITY, id, before, after);
        }
        return dto(stored.findById(id).orElseThrow(), actor);
    }

    @Transactional
    public ImportJobDto removeItem(long id, long itemId, UserPrincipal actor) {
        requireWrite(actor);
        Locked job = lockJob(id);
        ImportRequestItemDto item = requireItem(job.row(), itemId);
        List<JobLot> lots = jobs.lotsByItem(id).getOrDefault(itemId, List.of());
        if (anyReceived(lots)) {
            throw new ApiException(HttpStatus.CONFLICT, "ของรายการนี้ถึงโกดังแล้ว ลบไม่ได้");
        }
        if (jobs.itemCount(id) <= 1) {
            throw new ApiException(HttpStatus.CONFLICT, "งานนำเข้าต้องมีสินค้าอย่างน้อย 1 รายการ");
        }
        // a round this would empty goes too, unless it holds a document or typed facts: those are never dropped silently
        List<Long> emptied = new ArrayList<>();
        for (JobLot l : lots) {
            if (rounds.lotCount(l.roundId()) == 1) {
                if (rounds.hasOwnData(l.roundId())) {
                    throw new ApiException(HttpStatus.CONFLICT,
                        "รายการนี้เป็นล็อตสุดท้ายของรอบ และรอบนี้มีเอกสารหรือข้อมูลที่กรอกไว้ กรุณาจัดการที่หน้ารอบขนส่งก่อน");
                }
                emptied.add(l.roundId());
            }
        }
        lots.forEach(l -> rounds.deleteLot(l.lotId()));
        jobs.deleteItem(id, itemId);
        emptied.forEach(rounds::deleteRound);
        List<Long> stillThere = job.roundIds().stream().filter(r -> !emptied.contains(r)).toList();
        if (!job.roundIds().isEmpty()) {
            roundService.settleImportJob(job.irs(), stillThere, actor);
        }
        Map<String, Object> before = new LinkedHashMap<>();
        before.put("item", item.code());
        before.put("qty", item.qty().stripTrailingZeros().toPlainString());
        before.put("unit", item.unit());
        audit.record(actor, "IMPORT_JOB_ITEM_REMOVED", AUDIT_ENTITY, id, before, null);
        return dto(stored.findById(id).orElseThrow(), actor);
    }

    private static ImportRequestItemDto requireItem(ImportRequestDto row, long itemId) {
        return row.items().stream().filter(i -> i.id() == itemId).findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการสินค้านี้"));
    }

    // ── validation ──────────────────────────────────────────────────────────────────────────────────────

    private static String requirePurpose(String purpose) {
        if (purpose == null || !PURPOSE_LABELS.containsKey(purpose)) {
            throw bad("เลือกประเภทงานนำเข้า");
        }
        return purpose;
    }

    private static String requireNote(String raw) {
        String note = raw == null ? "" : raw.trim();
        if (note.isEmpty()) {
            throw bad("ระบุโครงการหรือลูกค้า");
        }
        if (note.length() > 255) {
            throw bad("โครงการ / ลูกค้า ยาวเกิน 255 ตัวอักษร");
        }
        return note;
    }

    private FactoryConfigDto requireFactory(Long factoryId) {
        FactoryConfigDto f = factoryId == null ? null : factories.findById(factoryId).orElse(null);
        if (f == null) {
            throw bad("เลือกโรงงาน");
        }
        return f;
    }

    private static LocalDate requireReceived(LocalDate received, LocalDate today) {
        if (received == null) {
            throw bad("ระบุวันที่ได้รับใบขอซื้อ");
        }
        if (received.isAfter(today)) {
            throw bad("วันที่ได้รับใบขอซื้อต้องไม่เป็นวันในอนาคต");
        }
        return received;
    }

    private String requesterName(Long requesterId) {
        if (requesterId == null) {
            return null;
        }
        EmployeeLoginRecord e = employees.findByEmployeeId(requesterId).filter(EmployeeLoginRecord::active)
            .orElseThrow(() -> bad("ไม่พบพนักงานฝ่ายขายที่เลือก"));
        return e.name();
    }

    private static String normalizeDoc(String raw) {
        return raw.trim().toUpperCase(Locale.ROOT);
    }

    private String requireFreeNumber(String raw) {
        String doc = normalizeDoc(raw);
        if (!DOC_NUMBER.matcher(doc).matches()) {
            throw bad("เลขที่ใบขอซื้อต้องเป็นรูปแบบ IR69412 หรือ IR69412-1");
        }
        if (stored.docNumberExists(doc)) {
            throw new ApiException(HttpStatus.CONFLICT, "เลขที่ใบขอซื้อนี้ถูกใช้แล้ว");
        }
        return doc;
    }

    private static JobLine cleanLine(JobLine l) {
        if (l == null) {
            throw bad("ใส่ข้อมูลสินค้า");
        }
        String code = l.code() == null ? "" : l.code().trim();
        String unit = l.unit() == null ? "" : l.unit().trim();
        String size = l.size() == null || l.size().isBlank() ? null : l.size().trim();
        if (code.isEmpty() || code.length() > 255) {
            throw bad("ระบุรหัสหรือชื่อสินค้าของทุกรายการ");
        }
        if (l.qty() == null || l.qty().signum() <= 0) {
            throw bad("จำนวนต้องมากกว่า 0 ทุกรายการ");
        }
        if (l.qty().stripTrailingZeros().scale() > 2) {
            throw bad("จำนวนมีทศนิยมได้ไม่เกิน 2 ตำแหน่ง");
        }
        if (l.qty().compareTo(MAX_QTY) > 0) {
            throw bad("จำนวนต้องไม่เกิน 9,999,999,999.99");
        }
        if (unit.isEmpty() || unit.length() > 30) {
            throw bad("ระบุหน่วยของทุกรายการ");
        }
        if (size != null && size.length() > 80) {
            throw bad("ขนาดยาวเกิน 80 ตัวอักษร");
        }
        return new JobLine(code, size, l.qty(), unit);
    }

    private static ApiException bad(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, message);
    }
}
