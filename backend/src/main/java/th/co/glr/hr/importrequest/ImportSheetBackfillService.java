package th.co.glr.hr.importrequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.EmployeeLoginRecord;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestRepository.NewImportJob;
import th.co.glr.hr.importrequest.ImportSheetModels.BlockPlan;
import th.co.glr.hr.importrequest.ImportSheetModels.Choices;
import th.co.glr.hr.importrequest.ImportSheetModels.Excluded;
import th.co.glr.hr.importrequest.ImportSheetModels.FactoryChoice;
import th.co.glr.hr.importrequest.ImportSheetModels.ImportSheetException;
import th.co.glr.hr.importrequest.ImportSheetModels.IrPlan;
import th.co.glr.hr.importrequest.ImportSheetModels.ParsedSheet;
import th.co.glr.hr.importrequest.ImportSheetModels.Path_;
import th.co.glr.hr.importrequest.ImportSheetModels.Plan;
import th.co.glr.hr.importrequest.ImportSheetModels.Result;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetBlock;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetIr;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetLine;
import th.co.glr.hr.importrequest.ImportSheetModels.Totals;
import th.co.glr.hr.importrequest.ImportSheetRepository.PortalIr;
import th.co.glr.hr.importrequest.ImportSheetRepository.PortalLot;
import th.co.glr.hr.importrequest.ImportSheetRepository.TicketState;
import th.co.glr.hr.importrequest.ShipmentRoundRepository.IrLock;

/**
 * The one time load of the import team's sheet (spec 0001). A dry run writes nothing and leaves a report. A commit refuses
 * to run unless its plan still has the hash of the dry run a person reviewed, then applies each block in its own
 * transaction, so one bad block never stops the rest. It sends no bell and no email.
 */
@Service
class ImportSheetBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ImportSheetBackfillService.class);
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    static final String EVENT_NOTE = "นำเข้าจากตารางของเข้า";

    private final ImportSheetPlanner planner;
    private final ImportSheetRepository sheet;
    private final ImportRequestRepository stored;
    private final ShipmentRoundRepository rounds;
    private final ShipmentRoundService roundService;
    private final FactoryConfigRepository factories;
    private final EmployeeAuthRepository employees;
    private final TransactionTemplate tx;
    private final ObjectMapper json;

    ImportSheetBackfillService(ImportSheetPlanner planner, ImportSheetRepository sheet, ImportRequestRepository stored,
                               ShipmentRoundRepository rounds, ShipmentRoundService roundService,
                               FactoryConfigRepository factories, EmployeeAuthRepository employees,
                               PlatformTransactionManager transactions, ObjectMapper json) {
        this.planner = planner;
        this.sheet = sheet;
        this.stored = stored;
        this.rounds = rounds;
        this.roundService = roundService;
        this.factories = factories;
        this.employees = employees;
        this.tx = new TransactionTemplate(transactions);
        this.json = json.copy().disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    private record Loaded(byte[] bytes, String sha256, ParsedSheet parsed, Choices choices) {}

    private Loaded load(Path file, Path choicesFile) throws IOException {
        if (!file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".xlsx")) {
            throw new ImportSheetException("ต้องเป็นไฟล์ .xlsx");
        }
        if (Files.size(file) > MAX_FILE_BYTES) {
            throw new ImportSheetException("ไฟล์ใหญ่เกิน 10 MB");
        }
        byte[] bytes = Files.readAllBytes(file);
        ParsedSheet parsed = ImportSheetPlanner.renameClashes(ImportSheetParser.parse(new ByteArrayInputStream(bytes), LocalDate.now(BANGKOK)),
            new ImportSheetPlanner.PortalCheck() {
                @Override
                public boolean exists(String docNumber) {
                    return sheet.findIrs(List.of(docNumber)).containsKey(docNumber);
                }

                @Override
                public boolean tiedTo(String docNumber, String backfillKey) {
                    PortalIr ir = sheet.findIrs(List.of(docNumber)).get(docNumber);
                    Long round = sheet.roundIdsByKey(List.of(backfillKey)).get(backfillKey);
                    return ir != null && round != null
                        && sheet.lotsOf(List.of(ir.id())).getOrDefault(ir.id(), List.of()).stream().anyMatch(l -> l.roundId() == round);
                }
            });
        Choices choices = choicesFile == null ? Choices.none() : json.readValue(choicesFile.toFile(), Choices.class);
        return new Loaded(bytes, sha256(bytes), parsed, normalizeRepCodes(choices));
    }

    private static Choices normalizeRepCodes(Choices c) {
        Map<String, Long> reps = new HashMap<>();
        c.repChoices().forEach((k, v) -> reps.put(k.trim().toUpperCase(java.util.Locale.ROOT), v));
        return new Choices(c.factoryChoices(), reps, c.purposeChoices());
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    Result dryRun(Path file, Path choicesFile, Path reportDir) throws IOException {
        Loaded in = load(file, choicesFile);
        Plan plan = planner.plan(in.parsed(), in.choices());
        Path_ paths = writeReport(plan, List.of(), reportDir, "DRY_RUN");
        return new Result("DRY_RUN", null, plan.totals(), plan.planSha256(), List.of(), paths);
    }

    /** Mutable tallies of the run, replaced only once a block's transaction has committed. */
    private static final class Tally {
        int roundsCreated;
        int roundsUpdated;
        int irsCreated;
        int irsAdopted;
        int dealsAdvanced;
        int irsFilled;
        final List<Long> createdRoundIds = new ArrayList<>();
        final List<Long> createdIrIds = new ArrayList<>();
        final List<Map<String, Object>> adopted = new ArrayList<>();
        final List<Map<String, Object>> filledInPlace = new ArrayList<>();
        final List<Map<String, Object>> updatedRounds = new ArrayList<>();
        final List<Map<String, Object>> dealsMoved = new ArrayList<>();
        final List<Long> createdDocumentIds = new ArrayList<>();
        final List<Long> appendedItemIds = new ArrayList<>();
        final List<String> sequenceBumps = new ArrayList<>();

        Tally copy() {
            Tally t = new Tally();
            t.roundsCreated = roundsCreated;
            t.roundsUpdated = roundsUpdated;
            t.irsCreated = irsCreated;
            t.irsAdopted = irsAdopted;
            t.dealsAdvanced = dealsAdvanced;
            t.irsFilled = irsFilled;
            t.createdRoundIds.addAll(createdRoundIds);
            t.createdIrIds.addAll(createdIrIds);
            t.adopted.addAll(adopted);
            t.filledInPlace.addAll(filledInPlace);
            t.updatedRounds.addAll(updatedRounds);
            t.dealsMoved.addAll(dealsMoved);
            t.createdDocumentIds.addAll(createdDocumentIds);
            t.appendedItemIds.addAll(appendedItemIds);
            t.sequenceBumps.addAll(sequenceBumps);
            return t;
        }
    }

    Result commit(Path file, Path choicesFile, Path reportDir, long actorEmployeeId, String expectedPlanSha256) throws IOException {
        Loaded in = load(file, choicesFile);
        Plan plan = planner.plan(in.parsed(), in.choices());
        if (!plan.planSha256().equals(expectedPlanSha256)) {
            writeReport(plan, List.of(), reportDir, "DRY_RUN");
            throw new ImportSheetException("แผนไม่ตรงกับที่ตรวจไว้ (ข้อมูลในระบบหรือไฟล์เปลี่ยนไป) ไม่ได้บันทึกอะไร กรุณาดูรายงานใหม่");
        }
        UserPrincipal actor = actor(actorEmployeeId);
        // the report is the only record of what ran: a folder that cannot be written is found out before anything is written
        Files.createDirectories(reportDir);
        if (!Files.isWritable(reportDir)) {
            throw new ImportSheetException("เขียนรายงานในโฟลเดอร์นี้ไม่ได้ ไม่ได้บันทึกอะไร: " + reportDir);
        }
        long runId = tx.execute(s -> sheet.insertRun(actor.id(), actor.name(), file.getFileName().toString(), in.bytes().length,
            in.sha256(), summaryJson(plan, new Tally(), List.of())));

        Map<String, Long> newFactoryIds = createNewFactories(plan, actor);
        Map<String, SheetBlock> sheetBlocks = new HashMap<>();
        Map<String, Integer> blocksPerDoc = new HashMap<>();
        for (SheetBlock b : in.parsed().blocks()) {
            sheetBlocks.put(b.backfillKey(), b);
            b.irs().forEach(ir -> blocksPerDoc.merge(ir.docNumber(), 1, Integer::sum));
        }

        Tally tally = new Tally();
        Map<String, Long> created = new HashMap<>();
        List<Excluded> failed = new ArrayList<>();
        for (BlockPlan block : plan.blocks()) {
            Tally next = tally.copy();
            Map<String, Long> nextCreated = new HashMap<>(created);
            try {
                tx.executeWithoutResult(s -> {
                    applyBlock(block, sheetBlocks.get(block.backfillKey()), in.choices(), blocksPerDoc, newFactoryIds, nextCreated, next, actor);
                    writeTally(runId, plan, next, failed);
                });
                tally = next;
                created = nextCreated;
            } catch (RuntimeException e) {
                log.error("Backfill block at row {} rolled back", block.firstRow(), e);
                failed.add(new Excluded(block.firstRow(), block.backfillKey(), ImportSheetParser.cut(String.valueOf(e.getMessage()), 300)));
            }
        }
        final Tally last = tally;
        tx.executeWithoutResult(s -> {
            writeTally(runId, plan, last, failed);
            sheet.finishRun(runId);
        });
        Path_ paths;
        try {
            paths = writeReport(plan, failed, reportDir, "COMMIT");
        } catch (IOException e) {
            // the writes are committed: say so with the run id rather than losing the outcome to a report error
            log.error("Backfill run {} committed, but its report could not be written", runId, e);
            paths = new Path_("", "");
        }
        Totals done = new Totals(last.roundsCreated, last.roundsUpdated, last.irsCreated, last.irsAdopted,
            last.irsFilled, plan.totals().irsSkipped(), plan.totals().blocksExcluded() + failed.size(), last.dealsAdvanced);
        return new Result("COMMIT", runId, done, plan.planSha256(), failed, paths);
    }

    private void writeTally(long runId, Plan plan, Tally t, List<Excluded> failed) {
        sheet.updateRun(runId, t.roundsCreated, t.roundsUpdated, t.irsCreated, t.irsAdopted, plan.skipped().size(),
            plan.excluded().size() + failed.size(), t.dealsAdvanced, summaryJson(plan, t, failed));
    }

    private UserPrincipal actor(long employeeId) {
        EmployeeLoginRecord e = employees.findByEmployeeId(employeeId)
            .orElseThrow(() -> new ImportSheetException("ไม่พบพนักงานรหัส " + employeeId));
        return new UserPrincipal(e.employeeId(), e.email(), e.name(), "import", e.employeeId(), true, e.createdAt(), false,
            e.divisionId(), false);
    }

    /** One transaction per factory, so a factory that cannot be created fails only the blocks that need it. */
    private Map<String, Long> createNewFactories(Plan plan, UserPrincipal actor) {
        Map<String, Long> out = new HashMap<>();
        for (BlockPlan b : plan.blocks()) {
            if (b.factoryId() != null) {
                continue;
            }
            String key = ImportRequestService.normalizeFactoryName(b.factoryName());
            if (out.containsKey(key)) {
                continue;
            }
            try {
                // the country the reviewed plan showed, not a fresh read of the choices
                Long id = tx.execute(s -> {
                    factories.lockFactoryName(key);
                    return factories.findByName(b.factoryName()).map(f -> f.id()).orElseGet(() ->
                        factories.createFromImportRequest(b.factoryName(), b.newFactoryCountry(), b.newFactoryCountryNote(), actor.id()));
                });
                out.put(key, id);
            } catch (RuntimeException e) {
                log.error("Backfill could not create factory for row {}", b.firstRow(), e);
            }
        }
        return out;
    }

    private void applyBlock(BlockPlan block, SheetBlock source, Choices choices, Map<String, Integer> blocksPerDoc,
                            Map<String, Long> newFactoryIds, Map<String, Long> created, Tally t, UserPrincipal actor) {
        sheet.lockRun();
        Long resolved = block.factoryId() != null ? block.factoryId()
            : newFactoryIds.get(ImportRequestService.normalizeFactoryName(block.factoryName()));
        if (resolved == null) {
            throw new IllegalStateException("สร้างโรงงานของยี่ห้อนี้ไม่สำเร็จ");
        }
        long factoryId = resolved;
        List<String> docs = source.irs().stream().map(SheetIr::docNumber).toList();
        Long blockRound = sheet.roundIdsByKey(List.of(block.backfillKey())).get(block.backfillKey());
        // a round of another factory is never filled, given documents or settled by this block: the block fails and says so
        if (blockRound != null && !Objects.equals(sheet.roundFactoryId(blockRound), factoryId)) {
            throw new IllegalStateException("รอบที่มีอยู่ของกลุ่มแถวนี้เป็นของโรงงานอื่น");
        }

        // lock in the round service's order (rounds, then IRs), then decide again from what is true under the locks
        Map<String, PortalIr> portal = sheet.findIrs(docs);
        List<Long> existingIds = portal.values().stream().map(PortalIr::id).sorted().toList();
        Set<Long> roundIds = new HashSet<>();
        sheet.lotsOf(existingIds).values().forEach(l -> l.forEach(x -> roundIds.add(x.roundId())));
        if (blockRound != null) {
            roundIds.add(blockRound);
        }
        rounds.lockRounds(roundIds.stream().sorted().toList());
        rounds.lockImportRequests(roundIds.stream().sorted().toList(), existingIds);
        portal = sheet.findIrs(docs);
        Map<Long, List<PortalLot>> lots = sheet.lotsOf(portal.values().stream().map(PortalIr::id).toList());
        Long planFactory = block.factoryId() != null ? block.factoryId() : Long.valueOf(factoryId);
        List<IrPlan> fresh = new ArrayList<>();
        for (IrPlan p : planner.classify(source, planFactory, blockRound, choices, blocksPerDoc, portal, lots, new HashSet<>(), created)) {
            PortalIr made = portal.get(p.docNumber());
            // an IR this run created for another factory is not extended by this block (the dry run says the same)
            boolean foreign = ImportSheetPlanner.CREATE_MORE.equals(p.action()) && created.containsKey(p.docNumber())
                && made != null && !Objects.equals(made.factoryId(), factoryId);
            fresh.add(foreign ? ImportSheetPlanner.asSkip(p, "เลขเดียวกันอยู่กลุ่มแถวของโรงงานอื่น ตรวจเลขใบขอซื้อ") : p);
        }
        requireSamePlan(block.irs(), fresh);

        boolean needsRound = fresh.stream().anyMatch(p -> Set.of(ImportSheetPlanner.CREATE, ImportSheetPlanner.CREATE_MORE,
            ImportSheetPlanner.ADOPT).contains(p.action()));
        if (blockRound == null && needsRound) {
            blockRound = sheet.insertRound(block.backfillKey(), factoryId, block.factoryName(), block.facts(), actor.id(), actor.name());
            t.roundsCreated++;
            t.createdRoundIds.add(blockRound);
        } else if (blockRound != null) {
            Map<String, Object> was = new LinkedHashMap<>();
            was.put("roundId", blockRound);
            was.put("before", sheet.roundSnapshot(blockRound)); // for a rollback: the step and facts as they were before this run
            t.updatedRounds.add(was);
            sheet.fillRound(blockRound, block.facts(), actor.id(), actor.name());
            t.roundsUpdated++;
        }
        if (blockRound != null) {
            addDocuments(blockRound, block, actor, t);
        }

        Set<Long> touchedRounds = new HashSet<>();
        if (blockRound != null) {
            touchedRounds.add(blockRound);
        }
        Set<Long> irIds = new HashSet<>();
        Set<Long> tickets = new HashSet<>();
        for (IrPlan p : fresh) {
            if (p.ticketId() != null) {
                tickets.add(p.ticketId());
            }
        }
        Map<Long, TicketState> before = sheet.ticketStates(tickets);
        for (IrPlan p : fresh) {
            switch (p.action()) {
                case ImportSheetPlanner.CREATE -> {
                    stored.raiseDocSequence(p.docNumber()); // only a number this run really imports moves the portal's counter
                    t.sequenceBumps.add(p.docNumber());
                    SheetIr ir = source.irs().stream().filter(x -> x.docNumber().equals(p.docNumber())).findFirst().orElseThrow();
                    long id = stored.insertImportJob(newJob(p, ir, source, factoryId, block.factoryName(), choices, actor));
                    placeLines(id, blockRound, p.lines());
                    created.put(p.docNumber(), id);
                    t.irsCreated++;
                    t.createdIrIds.add(id);
                    irIds.add(id);
                }
                case ImportSheetPlanner.CREATE_MORE -> {
                    long id = p.importRequestId() != null ? p.importRequestId() : created.get(p.docNumber());
                    List<Long> appended = placeLines(id, blockRound, p.lines());
                    if (p.importRequestId() != null) {
                        t.appendedItemIds.addAll(appended); // lines added to an IR an earlier run made: a targeted undo removes just these
                    }
                    irIds.add(id);
                }
                case ImportSheetPlanner.ADOPT -> {
                    adopt(p, blockRound, lots.getOrDefault(p.importRequestId(), List.of()), t);
                    irIds.add(p.importRequestId());
                    t.irsAdopted++;
                }
                case ImportSheetPlanner.FILL_IN_PLACE -> {
                    Map<String, Object> before2 = new LinkedHashMap<>();
                    before2.put("roundId", p.fromRoundId());
                    before2.put("before", sheet.roundSnapshot(p.fromRoundId()));
                    t.filledInPlace.add(before2);
                    t.irsFilled++;
                    sheet.fillRound(p.fromRoundId(), block.facts(), actor.id(), actor.name());
                    addDocuments(p.fromRoundId(), block, actor, t);
                    touchedRounds.add(p.fromRoundId());
                    irIds.add(p.importRequestId());
                }
                default -> { /* ON_ROUND, OTHER_ROUND, SKIP: nothing of this IR changes */ }
            }
        }

        if (!irIds.isEmpty() || !touchedRounds.isEmpty()) {
            List<Long> ordered = touchedRounds.stream().sorted().toList();
            List<IrLock> locked = rounds.lockImportRequests(ordered, irIds.stream().sorted().toList());
            roundService.settleQuietly(locked, ordered, actor, EVENT_NOTE);
            locked.stream().map(IrLock::ticketId).filter(Objects::nonNull).forEach(tickets::add);
            Map<Long, TicketState> after = sheet.ticketStates(tickets);
            for (Long ticket : tickets) {
                TicketState was = before.get(ticket);
                TicketState now = after.get(ticket);
                if (was != null && now != null && !(Objects.equals(was.stage(), now.stage())
                    && Objects.equals(was.fulfilment(), now.fulfilment()) && Objects.equals(was.payment(), now.payment()))) {
                    t.dealsAdvanced++;
                    Map<String, Object> moved = new LinkedHashMap<>();
                    moved.put("ticketId", ticket);
                    moved.put("code", was.code());
                    moved.put("stageBefore", was.stage());
                    moved.put("fulfilmentBefore", was.fulfilment());
                    moved.put("paymentBefore", was.payment());
                    t.dealsMoved.add(moved);
                }
            }
        }
    }

    private static void requireSamePlan(List<IrPlan> planned, List<IrPlan> fresh) {
        for (int i = 0; i < planned.size(); i++) {
            if (!planned.get(i).action().equals(fresh.get(i).action())
                || !Objects.equals(planned.get(i).fromRoundId(), fresh.get(i).fromRoundId())
                || !Objects.equals(planned.get(i).allocation(), fresh.get(i).allocation())) {
                throw new IllegalStateException("ข้อมูลของ " + planned.get(i).docNumber() + " เปลี่ยนระหว่างนำเข้า");
            }
        }
    }

    private NewImportJob newJob(IrPlan p, SheetIr ir, SheetBlock block, long factoryId, String factoryName, Choices choices, UserPrincipal actor) {
        LocalDate issue = ir.receivedOn() != null ? ir.receivedOn() : earliestDate(block);
        String requesterName = ImportSheetPlanner.splitCodes(ir.saleText()).stream().map(code -> {
            Long employee = choices.repChoices().get(code);
            return employee == null ? code : employees.findByEmployeeId(employee).map(EmployeeLoginRecord::name).orElse(code);
        }).reduce((a, b) -> a + " / " + b).orElse(null);
        return new NewImportJob(factoryId, factoryName, p.purpose(), ImportJobService.PURPOSE_LABELS.get(p.purpose()), p.requesterId(), requesterName,
            ir.projectText(), ir.wantedBy(), ir.wantedText(), p.docNumber(), issue, ir.depositOn(), actor.id(), actor.name(),
            actor.email(), "BACKFILL");
    }

    private static LocalDate earliestDate(SheetBlock block) {
        LocalDate fromFacts = block.facts().columns().values().stream().filter(LocalDate.class::isInstance).map(LocalDate.class::cast)
            .min(LocalDate::compareTo).orElse(null);
        LocalDate fromIrs = block.irs().stream().map(SheetIr::receivedOn).filter(Objects::nonNull).min(LocalDate::compareTo).orElse(null);
        LocalDate today = LocalDate.now(BANGKOK);
        LocalDate earliest = fromFacts == null ? fromIrs : fromIrs == null || fromFacts.isBefore(fromIrs) ? fromFacts : fromIrs;
        // the only date in a block can be a future ETA, and an IR cannot have been issued in the future
        return earliest == null || earliest.isAfter(today) ? today : earliest;
    }

    private List<Long> placeLines(long importRequestId, Long roundId, List<SheetLine> lines) {
        List<Long> items = new ArrayList<>();
        for (SheetLine l : lines) {
            long item = stored.appendItem(importRequestId, l.code(), null, l.qty(), l.unit(), l.note());
            rounds.insertLot(roundId, importRequestId, item, l.qty());
            items.add(item);
        }
        return items;
    }

    private void adopt(IrPlan p, Long blockRound, List<PortalLot> lots, Tally t) {
        if (lots.isEmpty()) {
            sheet.wantedItems(p.importRequestId()).stream().filter(i -> i.wantedQty().signum() > 0)
                .forEach(i -> rounds.insertLot(blockRound, p.importRequestId(), i.itemId(), i.wantedQty()));
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("importRequestId", p.importRequestId());
            entry.put("hadLots", false);
            t.adopted.add(entry);
            return;
        }
        long from = p.fromRoundId();
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("importRequestId", p.importRequestId());
        entry.put("hadLots", true);
        entry.put("deletedRound", sheet.roundSnapshot(from));
        entry.put("lots", lots.stream().map(PortalLot::lotId).toList());
        t.adopted.add(entry);
        lots.forEach(l -> rounds.moveLotTo(l.lotId(), blockRound));
        rounds.clearUndo(blockRound);
        rounds.deleteRound(from);
    }

    private void addDocuments(long roundId, BlockPlan block, UserPrincipal actor, Tally t) {
        for (String no : block.proformas()) {
            if (!sheet.documentExists(roundId, "PROFORMA", no)) {
                t.createdDocumentIds.add(rounds.insertDocument(roundId, "PROFORMA", no, null, null, actor.id(), false));
            }
        }
        for (String no : block.invoices()) {
            if (!sheet.documentExists(roundId, "INVOICE", no)) {
                t.createdDocumentIds.add(rounds.insertDocument(roundId, "INVOICE", no, null, null, actor.id(), false));
            }
        }
    }

    private String summaryJson(Plan plan, Tally t, List<Excluded> failed) {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("createdRoundIds", t.createdRoundIds);
        s.put("createdIrIds", t.createdIrIds);
        s.put("createdDocumentIds", t.createdDocumentIds);
        s.put("appendedItemIds", t.appendedItemIds);
        s.put("adopted", t.adopted);
        s.put("filledInPlace", t.filledInPlace);
        s.put("updatedRounds", t.updatedRounds);
        s.put("dealsAdvanced", t.dealsMoved);
        s.put("sequenceBumps", t.sequenceBumps);
        s.put("skipped", plan.skipped());
        s.put("excluded", plan.excluded());
        s.put("failed", failed);
        s.put("newFactories", plan.unresolvedBrands());
        s.put("renumbered", plan.blocks().stream().flatMap(b -> b.warnings().stream()
            .filter(w -> w.startsWith(ImportSheetPlanner.RENUMBERED)).map(w -> new Excluded(b.firstRow(), b.backfillKey(), w))).toList());
        try {
            return json.writeValueAsString(s);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the run summary", e);
        }
    }

    private Path_ writeReport(Plan plan, List<Excluded> failed, Path reportDir, String mode) throws IOException {
        Files.createDirectories(reportDir);
        Path jsonPath = reportDir.resolve("report.json");
        Path mdPath = reportDir.resolve("report.md");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("mode", mode);
        out.put("plan", plan);
        out.put("failed", failed);
        json.writerWithDefaultPrettyPrinter().writeValue(jsonPath.toFile(), out);
        Files.writeString(mdPath, markdown(plan, failed, mode), StandardCharsets.UTF_8);
        return new Path_(jsonPath.toString(), mdPath.toString());
    }

    private static String markdown(Plan p, List<Excluded> failed, String mode) {
        Totals t = p.totals();
        StringBuilder m = new StringBuilder("# รายงานนำเข้าตารางของเข้า (" + mode + ")\n\n");
        m.append("แท็บ ").append(p.sheetName()).append(" · ").append(p.rowCount()).append(" แถว\n\n");
        m.append("planSha256: `").append(p.planSha256()).append("`\n\n## สรุป\n\n");
        m.append("- รอบขนส่งใหม่: ").append(t.roundsCreated()).append("\n- รอบที่เติมข้อมูล: ").append(t.roundsUpdated())
            .append("\n- ใบขอซื้อใหม่: ").append(t.irsCreated()).append("\n- ใช้ใบขอซื้อเดิม: ").append(t.irsAdopted())
            .append("\n- เติมในรอบเดิม: ").append(t.irsFilled()).append("\n- ข้าม: ").append(t.irsSkipped())
            .append("\n- กลุ่มแถวที่ไม่นำเข้า: ").append(t.blocksExcluded()).append("\n- งานที่จะขยับ: ").append(t.dealsToAdvance()).append("\n");
        if (!p.unresolvedBrands().isEmpty()) {
            m.append("\n## โรงงานที่สร้างใหม่จากชื่อยี่ห้อ (ประเทศยังไม่ระบุ ให้ฝ่ายนำเข้าตรวจ)\n\n");
            p.unresolvedBrands().forEach(b -> m.append("- ").append(b).append("\n"));
        }
        if (!p.unresolvedCodes().isEmpty()) {
            m.append("\n## รหัสเซลส์ที่ยังไม่ได้ระบุ\n\n");
            p.unresolvedCodes().forEach(c -> m.append("- ").append(c).append("\n"));
        }
        if (!p.dealsToAdvance().isEmpty()) {
            m.append("\n## งานที่จะขยับ\n\n");
            p.dealsToAdvance().forEach(d -> m.append("- ").append(d.code()).append("\n"));
        }
        if (!p.excluded().isEmpty()) {
            m.append("\n## ไม่นำเข้า\n\n");
            p.excluded().forEach(e -> m.append("- แถว ").append(e.row()).append(" · ").append(e.what()).append(": ").append(e.reason()).append("\n"));
        }
        if (!p.skipped().isEmpty()) {
            m.append("\n## ข้าม\n\n");
            p.skipped().forEach(e -> m.append("- แถว ").append(e.row()).append(" · ").append(e.docNumber()).append(": ").append(e.reason()).append("\n"));
        }
        if (!failed.isEmpty()) {
            m.append("\n## บันทึกไม่สำเร็จ\n\n");
            failed.forEach(e -> m.append("- แถว ").append(e.row()).append(" · ").append(e.what()).append(": ").append(e.reason()).append("\n"));
        }
        if (!p.differences().isEmpty()) {
            m.append("\n## ค่าที่ตารางกับระบบไม่ตรงกัน (ระบบไม่ถูกแก้)\n\n");
            p.differences().forEach(d -> m.append("- รอบ ").append(d.roundId()).append(" · ").append(d.field()).append(": ตาราง ")
                .append(d.sheet()).append(" / ระบบ ").append(d.portal()).append("\n"));
        }
        m.append("\n## รอบขนส่ง\n\n");
        for (BlockPlan b : p.blocks()) {
            m.append("### แถว ").append(b.firstRow()).append(" · ").append(b.factoryName()).append(" · ").append(b.facts().step())
                .append(" · ").append(b.action()).append("\n\n");
            b.irs().forEach(i -> m.append("- ").append(i.docNumber()).append(" ").append(i.action())
                .append(i.jobCode() != null ? " งาน " + i.jobCode() : "").append(i.reason() != null ? " (" + i.reason() + ")" : "")
                .append(" · ").append(i.purpose()).append(" · ").append(i.lines().size()).append(" รายการ\n"));
            b.warnings().forEach(w -> m.append("- คำเตือน: ").append(w).append("\n"));
            Object note = b.facts().columns().get("note");
            if (note != null) {
                m.append("- หมายเหตุที่จะบันทึก: ").append(String.valueOf(note).replace('\n', ' ')).append("\n");
            }
            m.append("\n");
        }
        return m.toString();
    }
}
