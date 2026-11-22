package th.co.glr.hr.importrequest;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/** What the one time backfill reads from the import sheet and plans to do with it (spec 0001). No record has a cost field. */
final class ImportSheetModels {
    private ImportSheetModels() {}

    /** One product line of an IR. {@code warning} is set when the sheet gave no readable quantity. */
    record SheetLine(String code, BigDecimal qty, String unit, String note, String warning) {}

    record SheetIr(String docNumber, int firstRow, String saleText, String projectText, LocalDate receivedOn,
                   LocalDate depositOn, LocalDate wantedBy, String wantedText, List<SheetLine> lines) {}

    /** A shipment as the sheet describes it. {@code columns} is keyed by the sales.shipment_round column it fills. */
    record SheetFacts(String transportMode, String step, Map<String, Object> columns) {}

    /** A run of non blank rows. {@code blockedReason} is set when the block cannot be read safely. */
    record SheetBlock(int firstRow, String backfillKey, List<String> brands, SheetFacts facts, List<String> proformas,
                      List<String> invoices, List<SheetIr> irs, List<String> warnings, String blockedReason) {}

    record ParsedSheet(String sheetName, int rowCount, List<SheetBlock> blocks) {}

    record FactoryChoice(Long factoryId, @JsonProperty("new") boolean isNew, String countryCode, String countryOther) {}

    /** What a person decided for the run. A {@code repChoices} value of null means the code is not a person. */
    record Choices(Map<String, FactoryChoice> factoryChoices, Map<String, Long> repChoices,
                   Map<String, String> purposeChoices) {
        Choices {
            factoryChoices = factoryChoices == null ? Map.of() : factoryChoices;
            repChoices = repChoices == null ? Map.of() : repChoices;
            purposeChoices = purposeChoices == null ? Map.of() : purposeChoices;
        }

        static Choices none() {
            return new Choices(null, null, null);
        }
    }

    /** What the run does with one IR: CREATE, CREATE_MORE (a later block of an IR this run creates), ADOPT, FILL_IN_PLACE,
     * ON_ROUND, OTHER_ROUND or SKIP. {@code fromRoundId} is the round an ADOPT empties, or the round a FILL_IN_PLACE fills. */
    record IrPlan(String docNumber, int firstRow, String action, String reason, Long importRequestId, Long ticketId,
                  String jobCode, String projectText, String saleText, String purpose, Long requesterId,
                  String requesterName, Long fromRoundId, List<SheetLine> lines, LocalDate receivedOn, LocalDate depositOn,
                  LocalDate wantedBy, String wantedText, String allocation) {}

    /** {@code action}: CREATE or UPDATE a round, or NONE when no IR of the block rides on a backfilled round. */
    record BlockPlan(int firstRow, String backfillKey, String factoryText, Long factoryId, String factoryName, String action,
                     Long roundId, SheetFacts facts, List<String> proformas, List<String> invoices, List<String> warnings,
                     List<IrPlan> irs, String newFactoryCountry, String newFactoryCountryNote) {}

    record Excluded(int row, String what, String reason) {}

    record Skipped(int row, String docNumber, String reason) {}

    record Difference(long roundId, String field, String sheet, String portal) {}

    record DealToAdvance(long ticketId, String code) {}

    record Totals(int roundsCreated, int roundsUpdated, int irsCreated, int irsAdopted, int irsFilled, int irsSkipped,
                  int blocksExcluded, int dealsToAdvance) {}

    record Plan(String sheetName, int rowCount, List<BlockPlan> blocks, List<Excluded> excluded, List<Skipped> skipped,
                List<Difference> differences, List<String> unresolvedBrands, List<String> unresolvedCodes,
                List<DealToAdvance> dealsToAdvance, Totals totals, String planSha256) {}

    /** The outcome of a run. {@code failed} lists blocks a commit rolled back. */
    record Result(String mode, Long runId, Totals totals, String planSha256, List<Excluded> failed, Path_ reportPaths) {}

    record Path_(String json, String markdown) {}

    /** The sheet cannot be used at all: nothing was written. */
    static final class ImportSheetException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ImportSheetException(String message) {
            super(message);
        }
    }
}
