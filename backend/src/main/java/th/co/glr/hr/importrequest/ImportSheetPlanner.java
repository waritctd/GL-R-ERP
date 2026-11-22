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
        Map<String, List<FactoryConfigDto>> byCanonical = new HashMap<>();
        Set<String> ambiguousExact = new HashSet<>();
        factories.findAll().forEach(f -> {
            if (byName.putIfAbsent(ImportRequestService.normalizeFactoryName(f.factoryName()), f) != null) {
                ambiguousExact.add(ImportRequestService.normalizeFactoryName(f.factoryName())); // two masters read the same: no silent pick
            }
            byCanonical.computeIfAbsent(ImportRequestService.normalizeFactoryName(canonicalBrand(f.factoryName())), k -> new ArrayList<>()).add(f);
        });

        List<Excluded> excluded = new ArrayList<>();
        Set<String> unresolvedBrands = new LinkedHashSet<>();
        Set<String> unresolvedCodes = new LinkedHashSet<>();

        Map<String, Integer> blocksPerDoc = new HashMap<>();
        for (SheetBlock b : parsed.blocks()) {
            b.irs().forEach(ir -> blocksPerDoc.merge(ir.docNumber(), 1, Integer::sum));
        }

        List<SheetBlock> usable = new ArrayList<>();
        Map<Integer, ResolvedFactory> factoryOf = new HashMap<>();
        for (SheetBlock b : parsed.blocks()) {
            if (b.blockedReason() != null) {
                excluded.add(new Excluded(b.firstRow(), blockName(b), b.blockedReason()));
            } else if (b.brands().stream().map(x -> ImportRequestService.normalizeFactoryName(canonicalBrand(x))).distinct().count() > 1) {
                excluded.add(new Excluded(b.firstRow(), blockName(b), "แถวในกลุ่มนี้ระบุหลายยี่ห้อ: " + String.join(", ", b.brands())));
            } else {
                ResolvedFactory f = resolveFactory(b, byName, byCanonical, ambiguousExact, choices);
                if (f == null) {
                    String label = b.brands().isEmpty() ? "block:" + b.irs().get(0).docNumber() : b.brands().get(0);
                    excluded.add(new Excluded(b.firstRow(), blockName(b), b.brands().isEmpty()
                        ? "ไม่ได้ระบุยี่ห้อ จึงไม่รู้ว่าเป็นโรงงานไหน"
                        : "ยี่ห้อ \"" + label + "\" ที่เลือกไว้ใช้ไม่ได้ (โรงงานหรือประเทศไม่ถูกต้อง) หรือตรงกับโรงงานหลายแห่ง"));
                } else {
                    if (f.id() == null) {
                        unresolvedBrands.add(f.name());
                    }
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
        Map<String, String> docFactory = new HashMap<>();
        List<BlockPlan> blocks = new ArrayList<>();
        List<Skipped> skipped = new ArrayList<>();
        List<Difference> differences = new ArrayList<>();
        for (SheetBlock b : usable) {
            ResolvedFactory f = factoryOf.get(b.firstRow());
            Long blockRound = roundByKey.get(b.backfillKey());
            String factoryKey = f.id() != null ? "id:" + f.id() : "name:" + ImportRequestService.normalizeFactoryName(f.name());
            List<IrPlan> irs = rejectForeignFactory(classify(b, f.id(), blockRound, choices, blocksPerDoc, portal, lots, newSeen, Map.of()),
                factoryKey, docFactory);
            irs.stream().filter(p -> SKIP.equals(p.action()))
                .forEach(p -> skipped.add(new Skipped(p.firstRow(), p.docNumber(), p.reason())));
            String action = blockRound != null ? "UPDATE"
                : irs.stream().anyMatch(p -> NEEDS_BLOCK_ROUND.contains(p.action())) ? "CREATE" : "NONE";
            BlockPlan plan = new BlockPlan(b.firstRow(), b.backfillKey(), b.brands().isEmpty() ? null : b.brands().get(0), f.id(),
                f.name(), action, blockRound, b.facts(), b.proformas(), b.invoices(), b.warnings(), irs,
                f.newChoice() == null ? null : f.newChoice().countryCode(), f.newChoice() == null ? null : f.newChoice().countryOther());
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
        String hash = planHash(blocks, excluded, skipped, deals);
        return new Plan(parsed.sheetName(), parsed.rowCount(), blocks, excluded, skipped, differences,
            List.copyOf(unresolvedBrands), List.copyOf(unresolvedCodes), deals, totals, hash);
    }

    /** An IR this run creates cannot be extended by a block of another factory: its lines would sit on that block's round. */
    private static List<IrPlan> rejectForeignFactory(List<IrPlan> irs, String factoryKey, Map<String, String> docFactory) {
        List<IrPlan> out = new ArrayList<>();
        for (IrPlan p : irs) {
            if (CREATE.equals(p.action())) {
                docFactory.put(p.docNumber(), factoryKey);
            } else if (CREATE_MORE.equals(p.action()) && docFactory.containsKey(p.docNumber())
                && !factoryKey.equals(docFactory.get(p.docNumber()))) {
                p = asSkip(p, "เลขเดียวกันอยู่กลุ่มแถวของโรงงานอื่น ตรวจเลขใบขอซื้อ");
            }
            out.add(p);
        }
        return out;
    }

    static IrPlan asSkip(IrPlan p, String reason) {
        return new IrPlan(p.docNumber(), p.firstRow(), SKIP, reason, p.importRequestId(), p.ticketId(), p.jobCode(), p.projectText(),
            p.saleText(), p.purpose(), p.requesterId(), p.requesterName(), p.fromRoundId(), p.lines(), p.receivedOn(), p.depositOn(),
            p.wantedBy(), p.wantedText(), p.allocation());
    }

    private static IrPlan withAllocation(IrPlan p, String allocation) {
        return new IrPlan(p.docNumber(), p.firstRow(), p.action(), p.reason(), p.importRequestId(), p.ticketId(), p.jobCode(), p.projectText(),
            p.saleText(), p.purpose(), p.requesterId(), p.requesterName(), p.fromRoundId(), p.lines(), p.receivedOn(), p.depositOn(),
            p.wantedBy(), p.wantedText(), allocation);
    }

    /** What an ADOPT of an IR with no lots will place: the reviewed quantities, so a later edit cannot change the write unseen. */
    private String allocation(long importRequestId) {
        return sheet.wantedItems(importRequestId).stream().filter(i -> i.wantedQty().signum() > 0)
            .map(i -> i.itemId() + ":" + i.wantedQty().stripTrailingZeros().toPlainString()).collect(Collectors.joining(","));
    }

    /** Marks a block warning the run summary lists for import to check. */
    static final String RENUMBERED = "เปลี่ยนเลขใบขอซื้อ";
    static final String UNKNOWN_COUNTRY_NOTE = "ยังไม่ระบุ (ให้ฝ่ายนำเข้าตรวจ)";

    /**
     * The same IR number in two blocks under sales codes with nothing in common is probably a typed number. The later
     * block is kept, under a number of its own (IR69401-90 and up) so its goods show on การขนส่ง, and the change is a warning
     * on that block for import to correct.
     */
    /** What the portal already holds, so a renumbered IR never lands on a number somebody else uses. */
    interface PortalCheck {
        boolean exists(String docNumber);

        boolean tiedTo(String docNumber, String backfillKey);
    }

    static ParsedSheet renameClashes(ParsedSheet parsed, PortalCheck portal) {
        Set<String> taken = new HashSet<>();
        parsed.blocks().forEach(b -> b.irs().forEach(ir -> taken.add(ir.docNumber())));
        Map<String, Set<String>> anchorCodes = new HashMap<>();
        Map<Integer, Map<String, String>> renames = new HashMap<>();
        for (SheetBlock b : parsed.blocks()) {
            if (b.blockedReason() != null) {
                continue;
            }
            for (SheetIr ir : b.irs()) {
                Set<String> codes = new HashSet<>(splitCodes(ir.saleText()));
                Set<String> anchor = anchorCodes.get(ir.docNumber());
                if (anchor == null) {
                    anchorCodes.put(ir.docNumber(), codes);
                } else if (!anchor.isEmpty() && !codes.isEmpty() && anchor.stream().noneMatch(codes::contains)) {
                    String base = ir.docNumber().replaceAll("-\\d{1,2}$", "");
                    String fresh = null;
                    // a number an earlier run gave this very block is reused, so a repeat load adds nothing: look for it first
                    for (int n = 90; n <= 99 && fresh == null; n++) {
                        String candidate = base + "-" + n;
                        if (!taken.contains(candidate) && portal.exists(candidate) && portal.tiedTo(candidate, b.backfillKey())) {
                            fresh = candidate;
                        }
                    }
                    for (int n = 90; n <= 99 && fresh == null; n++) {
                        String candidate = base + "-" + n;
                        if (!taken.contains(candidate) && !portal.exists(candidate)) {
                            fresh = candidate;
                        }
                    }
                    if (fresh != null) {
                        taken.add(fresh);
                        renames.computeIfAbsent(b.firstRow(), k -> new HashMap<>()).put(ir.docNumber(), fresh);
                    }
                }
            }
        }
        if (renames.isEmpty()) {
            return parsed;
        }
        List<SheetBlock> out = new ArrayList<>();
        for (SheetBlock b : parsed.blocks()) {
            Map<String, String> change = renames.get(b.firstRow());
            if (change == null) {
                out.add(b);
                continue;
            }
            List<SheetIr> irs = new ArrayList<>();
            List<String> warnings = new ArrayList<>(b.warnings());
            for (SheetIr ir : b.irs()) {
                String fresh = change.get(ir.docNumber());
                if (fresh == null) {
                    irs.add(ir);
                    continue;
                }
                irs.add(new SheetIr(fresh, ir.firstRow(), ir.saleText(), ir.projectText(), ir.receivedOn(), ir.depositOn(), ir.wantedBy(),
                    ir.wantedText(), ir.lines()));
                warnings.add(RENUMBERED + ": ตารางเขียน " + ir.docNumber() + " ซ้ำกับกลุ่มแถวอื่นที่รหัสเซลส์ต่างกัน ใช้ " + fresh + " ไปก่อน");
            }
            out.add(new SheetBlock(b.firstRow(), b.backfillKey(), b.brands(), b.facts(), b.proformas(), b.invoices(), irs, warnings,
                b.blockedReason()));
        }
        return new ParsedSheet(parsed.sheetName(), parsed.rowCount(), out);
    }

    /** A brand written with a trailing "Group" is the same factory as the plain name. */
    static String canonicalBrand(String brand) {
        return brand == null ? null : brand.strip().replaceAll("(?i)\\s+group$", "");
    }

    private static String blockName(SheetBlock b) {
        return b.irs().isEmpty() ? "กลุ่มแถวที่ไม่มีเลข IR" : b.irs().stream().map(SheetIr::docNumber).collect(Collectors.joining(", "));
    }

    private ResolvedFactory resolveFactory(SheetBlock b, Map<String, FactoryConfigDto> byName,
                                           Map<String, List<FactoryConfigDto>> byCanonical, Set<String> ambiguousExact, Choices choices) {
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
                if (brand == null || choice.countryCode() == null || !factories.countryExists(choice.countryCode())
                    || ("ZZ".equals(choice.countryCode()) && (choice.countryOther() == null || choice.countryOther().isBlank()))) {
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
        String canonical = canonicalBrand(brand);
        // an exact name first, then the same name with or without "Group" on either side; two candidates is not a guess to make
        if (ambiguousExact.contains(ImportRequestService.normalizeFactoryName(brand))) {
            return null;
        }
        FactoryConfigDto known = byName.get(ImportRequestService.normalizeFactoryName(brand));
        if (known == null) {
            List<FactoryConfigDto> same = byCanonical.getOrDefault(ImportRequestService.normalizeFactoryName(canonical), List.of());
            if (same.size() > 1) {
                return null;
            }
            known = same.isEmpty() ? null : same.get(0);
        }
        if (known != null) {
            return new ResolvedFactory(known.id(), known.factoryName(), null);
        }
        // nobody has picked a factory for this brand: it becomes a factory of its own so the goods show on การขนส่ง now,
        // and import completes the country afterwards
        return new ResolvedFactory(null, canonical, new FactoryChoice(null, true, "ZZ", UNKNOWN_COUNTRY_NOTE));
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
            // an earlier run committed another block of this IR and rolled this one back: this block never ran, so its lines are still owed
            if (!here && blockRound == null && "BACKFILL".equals(p.source()) && blockCount > 1 && Objects.equals(p.factoryId(), factoryId)) {
                return plan(ir, CREATE_MORE, null, p.id(), null, null, null, choices);
            }
            return plan(ir, here ? ON_ROUND : OTHER_ROUND, null, p.id(), p.ticketId(), p.ticketCode(), null, choices);
        }
        Set<Long> roundIds = lots.stream().map(PortalLot::roundId).collect(Collectors.toSet());
        if (lots.stream().anyMatch(l -> "PORTAL".equals(l.roundSource())) || roundIds.size() > 1
            || lots.stream().anyMatch(PortalLot::sharedWithAnotherIr)) {
            return skip(ir, p, "ฝ่ายนำเข้าจัดรอบในระบบแล้ว จัดการต่อที่หน้า การขนส่ง", choices);
        }
        if (lots.isEmpty()) {
            boolean anyWanted = sheet.wantedItems(p.id()).stream().anyMatch(i -> i.wantedQty().signum() > 0);
            return anyWanted ? withAllocation(plan(ir, ADOPT, null, p.id(), p.ticketId(), p.ticketCode(), null, choices), allocation(p.id()))
                : skip(ir, p, "ทุกรายการถูกระบุว่าไม่ส่ง", choices);
        }
        PortalLot lot = lots.get(0);
        if ("BACKFILL".equals(lot.roundSource())) {
            boolean here = blockRound != null && lot.roundId() == blockRound;
            return plan(ir, here ? ON_ROUND : OTHER_ROUND, null, p.id(), p.ticketId(), p.ticketCode(), null, choices);
        }
        boolean untouched = ImportRequestStep.CONTACTED.equals(lot.roundStep()) && !rounds.hasOwnData(lot.roundId());
        IrPlan moved = plan(ir, untouched ? ADOPT : FILL_IN_PLACE, null, p.id(), p.ticketId(), p.ticketCode(), lot.roundId(), choices);
        // the lots an ADOPT will move are part of what was reviewed: a lot that changed since is refused
        return untouched ? withAllocation(moved, lots.stream().map(l -> l.lotId() + ":" + l.qty().stripTrailingZeros().toPlainString())
            .sorted().collect(Collectors.joining(","))) : moved;
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
            purpose, requester, ir.saleText(), fromRound, ir.lines(), ir.receivedOn(), ir.depositOn(), ir.wantedBy(), ir.wantedText(), null);
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

    private String planHash(List<BlockPlan> blocks, List<Excluded> excluded, List<Skipped> skipped, List<DealToAdvance> deals) {
        try {
            Map<String, Object> canon = new LinkedHashMap<>();
            canon.put("blocks", blocks);
            canon.put("excluded", excluded);
            canon.put("skipped", skipped);
            canon.put("dealsToAdvance", deals);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(json.writeValueAsString(canon).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException e) {
            throw new IllegalStateException("Could not hash the backfill plan", e);
        }
    }
}
