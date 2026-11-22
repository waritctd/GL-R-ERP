package th.co.glr.hr.importrequest;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportSheetModels.BlockPlan;
import th.co.glr.hr.importrequest.ImportSheetModels.Choices;
import th.co.glr.hr.importrequest.ImportSheetModels.DealToAdvance;
import th.co.glr.hr.importrequest.ImportSheetModels.Difference;
import th.co.glr.hr.importrequest.ImportSheetModels.Excluded;
import th.co.glr.hr.importrequest.ImportSheetModels.FactoryChoice;
import th.co.glr.hr.importrequest.ImportSheetModels.IrPlan;
import th.co.glr.hr.importrequest.ImportSheetModels.ParsedSheet;
import th.co.glr.hr.importrequest.ImportSheetModels.Plan;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetBlock;
import th.co.glr.hr.importrequest.ImportSheetModels.SheetIr;
import th.co.glr.hr.importrequest.ImportSheetModels.Skipped;
import th.co.glr.hr.importrequest.ImportSheetModels.Totals;
import th.co.glr.hr.importrequest.ImportSheetRepository.PortalIr;
import th.co.glr.hr.importrequest.ImportSheetRepository.PortalLot;

/** Decides, without writing, what the backfill does with each block and IR (spec 0001, "Existing IRs"). */
@Component
class ImportSheetPlanner {

    static final String CREATE = "CREATE";
    static final String CREATE_MORE = "CREATE_MORE";
    static final String ADOPT = "ADOPT";
    static final String FILL_IN_PLACE = "FILL_IN_PLACE";
    static final String ON_ROUND = "ON_ROUND";
    static final String OTHER_ROUND = "OTHER_ROUND";
    static final String SKIP = "SKIP";

    private static final Set<String> NEEDS_BLOCK_ROUND = Set.of(CREATE, CREATE_MORE, ADOPT);
    private static final Set<String> PURPOSES = Set.of("SAMPLE", "SHOWROOM", "STOCK", "CUSTOMER");
    private static final Pattern MONTH_ORDER = Pattern.compile(
        "^(jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)[a-z]*[-\\s]*\\d{0,4}(\\s*order|\\s+ครั้งที่.*)?$", Pattern.CASE_INSENSITIVE);

    private final ImportSheetRepository sheet;
    private final FactoryConfigRepository factories;
    private final ShipmentRoundRepository rounds;
    private final ObjectMapper json;

    ImportSheetPlanner(ImportSheetRepository sheet, FactoryConfigRepository factories, ShipmentRoundRepository rounds,
                       ObjectMapper json) {
        this.sheet = sheet;
        this.factories = factories;
        this.rounds = rounds;
        this.json = json;
    }

    /** Which factory a block belongs to. {@code id} is null for a factory the commit will create. */
    record ResolvedFactory(Long id, String name, FactoryChoice newChoice) {}

    Plan plan(ParsedSheet parsed, Choices choices) {
        Map<String, FactoryConfigDto> byName = new HashMap<>();
        factories.findAll().forEach(f -> byName.putIfAbsent(ImportRequestService.normalizeFactoryName(f.factoryName()), f));

        List<Excluded> excluded = new ArrayList<>();
        Set<String> unresolvedBrands = new LinkedHashSet<>();
        Set<String> unresolvedCodes = new LinkedHashSet<>();
        Set<Integer> clashing = clashingBlocks(parsed.blocks(), excluded);

        Map<String, Integer> blocksPerDoc = new HashMap<>();
        for (SheetBlock b : parsed.blocks()) {
            b.irs().forEach(ir -> blocksPerDoc.merge(ir.docNumber(), 1, Integer::sum));
        }

        List<SheetBlock> usable = new ArrayList<>();
        Map<Integer, ResolvedFactory> factoryOf = new HashMap<>();
        for (SheetBlock b : parsed.blocks()) {
            if (b.blockedReason() != null) {
                excluded.add(new Excluded(b.firstRow(), blockName(b), b.blockedReason()));
            } else if (clashing.contains(b.firstRow())) {
                continue; // listed by clashingBlocks
            } else if (b.brands().stream().map(ImportRequestService::normalizeFactoryName).distinct().count() > 1) {
                excluded.add(new Excluded(b.firstRow(), blockName(b), "แถวในกลุ่มนี้ระบุหลายยี่ห้อ: " + String.join(", ", b.brands())));
            } else {
                ResolvedFactory f = resolveFactory(b, byName, choices);
                if (f == null) {
                    String label = b.brands().isEmpty() ? "block:" + b.irs().get(0).docNumber() : b.brands().get(0);
                    unresolvedBrands.add(label);
                    excluded.add(new Excluded(b.firstRow(), blockName(b), "ยังไม่ได้เลือกโรงงานของยี่ห้อ \"" + label + "\""));
                } else {
                    factoryOf.put(b.firstRow(), f);
                    usable.add(b);
                }
            }
        }

        for (SheetBlock b : usable) {
            for (SheetIr ir : b.irs()) {
                splitCodes(ir.saleText()).stream().filter(c -> !choices.repChoices().containsKey(c)).forEach(unresolvedCodes::add);
            }
        }

        List<String> docs = usable.stream().flatMap(b -> b.irs().stream()).map(SheetIr::docNumber).distinct().toList();
        Map<String, PortalIr> portal = sheet.findIrs(docs);
        Map<Long, List<PortalLot>> lots = sheet.lotsOf(portal.values().stream().map(PortalIr::id).toList());
        Map<String, Long> roundByKey = sheet.roundIdsByKey(usable.stream().map(SheetBlock::backfillKey).toList());

        Set<String> newSeen = new HashSet<>();
        List<BlockPlan> blocks = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<Difference> differences = new ArrayList<>();
        for (SheetBlock b : usable) {
            ResolvedFactory f = factoryOf.get(b.firstRow());
            Long blockRound = roundByKey.get(b.backfillKey());
            List<IrPlan> irs = classify(b, f.id(), blockRound, choices, blocksPerDoc, portal, lots, newSeen, Map.of());
            irs.stream().filter(p -> SKIP.equals(p.action()))
                .forEach(p -> skipped.add(new Skipped(p.firstRow(), p.docNumber(), p.reason())));
            String action = blockRound != null ? "UPDATE"
                : irs.stream().anyMatch(p -> NEEDS_BLOCK_ROUND.contains(p.action())) ? "CREATE" : "NONE";
            BlockPlan plan = new BlockPlan(b.firstRow(), b.backfillKey(), b.brands().isEmpty() ? null : b.brands().get(0), f.id(),
                f.name(), action, blockRound, b.facts(), b.proformas(), b.invoices(), b.warnings(), irs);
            blocks.add(plan);
            if (blockRound != null) {
                addDifferences(blockRound, b, differences);
            }
            irs.stream().filter(p -> FILL_IN_PLACE.equals(p.action()))
                .forEach(p -> addDifferences(p.fromRoundId(), b, differences));
            irs.stream().filter(p -> OTHER_ROUND.equals(p.action()))
                .forEach(p -> differences.add(new Difference(blockRound == null ? 0 : blockRound, "ใบขอซื้อ " + p.docNumber(),
                    "อยู่ในกลุ่มแถว " + b.firstRow(), "อยู่รอบอื่นในระบบ")));
        }

        List<DealToAdvance> deals = dealsToAdvance(blocks, portal);
        excluded.sort(java.util.Comparator.comparingInt(Excluded::row));
        Totals totals = totals(blocks, excluded.size(), deals.size());
        String hash = planHash(blocks, excluded, skipped);
        return new Plan(parsed.sheetName(), parsed.rowCount(), blocks, excluded, skipped, differences,
            List.copyOf(unresolvedBrands), List.copyOf(unresolvedCodes), deals, totals, hash);
    }

    /** The same IR number in two blocks under sales codes with nothing in common is probably a typo: both are excluded. */
    private static Set<Integer> clashingBlocks(List<SheetBlock> blocks, List<Excluded> excluded) {
        Map<String, List<SheetBlock>> byDoc = new LinkedHashMap<>();
        Map<String, Map<Integer, Set<String>>> codes = new HashMap<>();
        for (SheetBlock b : blocks) {
            if (b.blockedReason() != null) {
                continue;
            }
            for (SheetIr ir : b.irs()) {
                byDoc.computeIfAbsent(ir.docNumber(), k -> new ArrayList<>()).add(b);
                codes.computeIfAbsent(ir.docNumber(), k -> new HashMap<>())
                    .computeIfAbsent(b.firstRow(), k -> new HashSet<>()).addAll(splitCodes(ir.saleText()));
            }
        }
        Set<Integer> out = new LinkedHashSet<>();
        byDoc.forEach((doc, list) -> {
            Map<Integer, Set<String>> perBlock = codes.get(doc);
            for (int i = 0; i < list.size(); i++) {
                for (int j = i + 1; j < list.size(); j++) {
                    Set<String> a = perBlock.get(list.get(i).firstRow());
                    Set<String> b = perBlock.get(list.get(j).firstRow());
                    if (!a.isEmpty() && !b.isEmpty() && a.stream().noneMatch(b::contains)) {
                        for (SheetBlock x : List.of(list.get(i), list.get(j))) {
                            if (out.add(x.firstRow())) {
                                excluded.add(new Excluded(x.firstRow(), blockName(x), "เลข " + doc
                                    + " อยู่ 2 กลุ่มแถวแต่รหัสเซลส์ต่างกัน (" + String.join("/", a) + " กับ " + String.join("/", b)
                                    + ") น่าจะพิมพ์เลขผิด"));
                            }
                        }
                    }
                }
            }
        });
        return out;
    }

    private static String blockName(SheetBlock b) {
        return b.irs().isEmpty() ? "กลุ่มแถวที่ไม่มีเลข IR" : b.irs().stream().map(SheetIr::docNumber).collect(Collectors.joining(", "));
    }

    private ResolvedFactory resolveFactory(SheetBlock b, Map<String, FactoryConfigDto> byName, Choices choices) {
        String brand = b.brands().isEmpty() ? null : b.brands().get(0);
        FactoryChoice choice = choices.factoryChoices().get(brand == null ? "block:" + b.irs().get(0).docNumber() : brand);
        if (choice == null && brand != null) {
            String normal = ImportRequestService.normalizeFactoryName(brand);
            choice = choices.factoryChoices().entrySet().stream()
                .filter(e -> normal.equals(ImportRequestService.normalizeFactoryName(e.getKey())))
                .map(Map.Entry::getValue).findFirst().orElse(null);
        }
        if (choice != null) {
            if (choice.isNew()) {
                if (brand == null || choice.countryCode() == null || !factories.countryExists(choice.countryCode())) {
                    return null;
                }
                return new ResolvedFactory(null, brand, choice);
            }
            return choice.factoryId() == null ? null
                : factories.findById(choice.factoryId()).map(f -> new ResolvedFactory(f.id(), f.factoryName(), null)).orElse(null);
        }
        if (brand == null) {
            return null;
        }
        FactoryConfigDto known = byName.get(ImportRequestService.normalizeFactoryName(brand));
        return known == null ? null : new ResolvedFactory(known.id(), known.factoryName(), null);
    }

    /**
     * The rule table, first match wins. {@code newSeen} holds the IRs earlier blocks of this plan will create;
     * {@code createdThisRun} is the same during a commit, when those IRs already exist.
     */
    List<IrPlan> classify(SheetBlock b, Long factoryId, Long blockRound, Choices choices, Map<String, Integer> blocksPerDoc,
                          Map<String, PortalIr> portal, Map<Long, List<PortalLot>> lots, Set<String> newSeen,
                          Map<String, Long> createdThisRun) {
        List<IrPlan> out = new ArrayList<>();
        for (SheetIr ir : b.irs()) {
            Long createdId = createdThisRun.get(ir.docNumber());
            if (createdId != null) {
                out.add(plan(ir, CREATE_MORE, null, createdId, null, null, null, choices));
                continue;
            }
            PortalIr p = portal.get(ir.docNumber());
            if (p == null) {
                out.add(plan(ir, newSeen.add(ir.docNumber()) ? CREATE : CREATE_MORE, null, null, null, null, null, choices));
                continue;
            }
            out.add(classifyExisting(ir, p, lots.getOrDefault(p.id(), List.of()), factoryId, blockRound, choices,
                blocksPerDoc.getOrDefault(ir.docNumber(), 1)));
        }
        return out;
    }

    private IrPlan classifyExisting(SheetIr ir, PortalIr p, List<PortalLot> lots, Long factoryId, Long blockRound,
                                    Choices choices, int blockCount) {
        if (!ImportRequestStatus.ISSUED.equals(p.status())) {
            return skip(ir, p, "ใบขอซื้อนี้ไม่ได้อยู่ในสถานะออกแล้ว", choices);
        }
        if (factoryId == null || !factoryId.equals(p.factoryId())) {
            return skip(ir, p, "เลขซ้ำกับใบขอซื้ออื่น (โรงงานไม่ตรง)", choices);
        }
        if (p.ticketId() != null && repIsSomeoneElse(ir, p, choices)) {
            return skip(ir, p, "เลขซ้ำกับใบขอซื้ออื่น (เซลส์ไม่ตรง)", choices);
        }
        boolean sheetMade = "BACKFILL".equals(p.source()) || "IMPORT".equals(p.source());
        if (!sheetMade && blockCount > 1) {
            return skip(ir, p, "อยู่หลายกลุ่มแถวในตาราง ให้แบ่งล็อตเองที่หน้า การขนส่ง", choices);
        }
        if (sheetMade) {
            boolean here = blockRound != null && lots.stream().anyMatch(l -> l.roundId() == blockRound);
            return plan(ir, here ? ON_ROUND : OTHER_ROUND, null, p.id(), p.ticketId(), p.ticketCode(), null, choices);
        }
        Set<Long> roundIds = lots.stream().map(PortalLot::roundId).collect(Collectors.toSet());
        if (lots.stream().anyMatch(l -> "PORTAL".equals(l.roundSource())) || roundIds.size() > 1
            || lots.stream().anyMatch(PortalLot::sharedWithAnotherIr)) {
            return skip(ir, p, "ฝ่ายนำเข้าจัดรอบในระบบแล้ว จัดการต่อที่หน้า การขนส่ง", choices);
        }
        if (lots.isEmpty()) {
            boolean anyWanted = sheet.wantedItems(p.id()).stream().anyMatch(i -> i.wantedQty().signum() > 0);
            return anyWanted ? plan(ir, ADOPT, null, p.id(), p.ticketId(), p.ticketCode(), null, choices)
                : skip(ir, p, "ทุกรายการถูกระบุว่าไม่ส่ง", choices);
        }
        PortalLot lot = lots.get(0);
        if ("BACKFILL".equals(lot.roundSource())) {
            boolean here = blockRound != null && lot.roundId() == blockRound;
            return plan(ir, here ? ON_ROUND : OTHER_ROUND, null, p.id(), p.ticketId(), p.ticketCode(), null, choices);
        }
        boolean untouched = ImportRequestStep.CONTACTED.equals(lot.roundStep()) && !rounds.hasOwnData(lot.roundId());
        return plan(ir, untouched ? ADOPT : FILL_IN_PLACE, null, p.id(), p.ticketId(), p.ticketCode(), lot.roundId(), choices);
    }

    private boolean repIsSomeoneElse(SheetIr ir, PortalIr p, Choices choices) {
        List<String> codes = splitCodes(ir.saleText());
        if (codes.isEmpty() || !codes.stream().allMatch(c -> choices.repChoices().get(c) != null)) {
            return false;
        }
        return codes.stream().map(choices.repChoices()::get)
            .noneMatch(e -> e.equals(p.ownerId()) || e.equals(p.keyedById()));
    }

    private IrPlan skip(SheetIr ir, PortalIr p, String reason, Choices choices) {
        return plan(ir, SKIP, reason, p.id(), p.ticketId(), p.ticketCode(), null, choices);
    }

    private IrPlan plan(SheetIr ir, String action, String reason, Long irId, Long ticketId, String jobCode, Long fromRound,
                        Choices choices) {
        String purpose = choices.purposeChoices().get(ir.docNumber());
        if (purpose == null || !PURPOSES.contains(purpose)) {
            purpose = suggestPurpose(ir.projectText());
        }
        List<String> codes = splitCodes(ir.saleText());
        Long requester = codes.stream().map(choices.repChoices()::get).filter(Objects::nonNull).findFirst().orElse(null);
        return new IrPlan(ir.docNumber(), ir.firstRow(), action, reason, irId, ticketId, jobCode, ir.projectText(), ir.saleText(),
            purpose, requester, ir.saleText(), fromRound, ir.lines());
    }

    static List<String> splitCodes(String saleText) {
        if (saleText == null || saleText.isBlank()) {
            return List.of();
        }
        return Arrays.stream(saleText.split("/")).map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).distinct().toList();
    }

    static String suggestPurpose(String projectText) {
        String t = projectText == null ? "" : projectText.toLowerCase(Locale.ROOT).trim();
        if (t.contains("showroom") || t.contains("โชว์รูม")) {
            return "SHOWROOM";
        }
        if (t.contains("sample") || t.contains("ตัวอย่าง") || t.contains("present") || t.contains("mock")) {
            return "SAMPLE";
        }
        if (t.contains("stock") || t.contains("สต็อก") || t.contains("warehouse") || t.contains("คลัง")
            || MONTH_ORDER.matcher(t).matches()) {
            return "STOCK";
        }
        return "CUSTOMER";
    }

    private void addDifferences(long roundId, SheetBlock b, List<Difference> out) {
        Map<String, String> current = sheet.roundFacts(roundId);
        b.facts().columns().forEach((column, value) -> {
            String have = current.get(column);
            String want = String.valueOf(value);
            if (have != null && !have.isBlank() && !have.equals(want)) {
                out.add(new Difference(roundId, column, ImportSheetParser.cut(want, 120), ImportSheetParser.cut(have, 120)));
            }
        });
        String step = current.get("import_step");
        if (step != null && ImportRequestStep.indexOf(step) > ImportRequestStep.indexOf(b.facts().step())) {
            out.add(new Difference(roundId, "import_step", b.facts().step(), step));
        }
    }

    /** Jobs whose every issued IR will be received once the plan is applied. */
    private List<DealToAdvance> dealsToAdvance(List<BlockPlan> blocks, Map<String, PortalIr> portal) {
        Set<String> willArrive = new HashSet<>();
        Set<Long> touched = new LinkedHashSet<>();
        for (BlockPlan b : blocks) {
            if (!ImportRequestStep.RECEIVED.equals(b.facts().step())) {
                continue;
            }
            for (IrPlan p : b.irs()) {
                if (p.ticketId() != null && Set.of(ADOPT, FILL_IN_PLACE, ON_ROUND).contains(p.action())) {
                    willArrive.add(p.docNumber());
                    touched.add(p.ticketId());
                }
            }
        }
        var states = sheet.ticketStates(touched);
        var steps = sheet.issuedStepsOfTickets(touched);
        List<DealToAdvance> out = new ArrayList<>();
        for (Long t : touched) {
            var st = states.get(t);
            boolean pending = st != null && "ACTIVE".equals(st.lifecycle()) && st.fulfilment() != null
                && Set.of("IR_ISSUED", "IR_SENT", "SHIPPING").contains(st.fulfilment());
            boolean allIn = steps.getOrDefault(t, List.of()).stream().allMatch(s -> {
                String[] parts = s.split("=", 2);
                return willArrive.contains(parts[0]) || ImportRequestStep.RECEIVED.equals(parts[1]);
            });
            if (pending && allIn) {
                out.add(new DealToAdvance(t, st.code()));
            }
        }
        return out;
    }

    private static Totals totals(List<BlockPlan> blocks, int excluded, int deals) {
        int create = 0;
        int update = 0;
        int irCreate = 0;
        int adopt = 0;
        int fill = 0;
        int skip = 0;
        for (BlockPlan b : blocks) {
            create += "CREATE".equals(b.action()) ? 1 : 0;
            update += "UPDATE".equals(b.action()) ? 1 : 0;
            for (IrPlan p : b.irs()) {
                irCreate += CREATE.equals(p.action()) ? 1 : 0;
                adopt += ADOPT.equals(p.action()) ? 1 : 0;
                fill += FILL_IN_PLACE.equals(p.action()) ? 1 : 0;
                skip += SKIP.equals(p.action()) ? 1 : 0;
            }
        }
        return new Totals(create, update, irCreate, adopt, fill, skip, excluded, deals);
    }

    private String planHash(List<BlockPlan> blocks, List<Excluded> excluded, List<Skipped> skipped) {
        try {
            Map<String, Object> canon = new LinkedHashMap<>();
            canon.put("blocks", blocks);
            canon.put("excluded", excluded);
            canon.put("skipped", skipped);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(canon).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash the backfill plan", e);
        }
    }
}
