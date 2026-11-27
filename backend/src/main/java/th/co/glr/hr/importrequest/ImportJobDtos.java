package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;

/** Wire shapes of /api/import-jobs (งานนำเข้า: an issued import request with no sales job). No price of any kind. */
public final class ImportJobDtos {
    private ImportJobDtos() {}

    public record JobLine(String code, String size, BigDecimal qty, String unit) {}

    /** {@code mode}: NONE (wait to be planned), EXISTING (with {@code roundId}) or NEW. */
    public record RoundChoice(String mode, Long roundId) {}

    /** {@code docNumber} null: the portal issues the next number. */
    public record CreateImportJobRequest(String purpose, String purposeNote, Long factoryId, LocalDate receivedOn,
                                         Long requesterId, LocalDate depositReceivedOn, LocalDate wantedBy, String docNumber,
                                         List<JobLine> lines, RoundChoice round) {}

    /** Only what is sent changes. {@code clear} names the optional fields to empty: requesterId, depositReceivedOn, wantedBy. */
    public record UpdateImportJobRequest(String purpose, String purposeNote, Long requesterId, LocalDate receivedOn,
                                         LocalDate depositReceivedOn, LocalDate wantedBy, String docNumber, Long factoryId,
                                         List<String> clear) {}

    public record ItemRequest(String code, String size, BigDecimal qty, String unit) {}

    public record JobLot(long lotId, long roundId, String roundLabel, BigDecimal qty, BigDecimal receivedQty, String step) {}

    public record JobItem(long id, int seq, String code, String size, BigDecimal qty, String unit, String note, boolean locked,
                          List<JobLot> lots) {}

    /** A round the job rides on: its step, dates and vessel, never its documents or note. */
    public record JobRound(long id, String factoryName, String label, String source, String step, String transportMode,
                           String vessel, String port, LocalDate orderedOn, LocalDate pickedUpOn, LocalDate etdActualOn,
                           LocalDate etaOn, LocalDate transshipEtaOn, LocalDate insuredOn, LocalDate arrivedOn,
                           LocalDate receivedOn) {}

    public record JobHistory(Instant at, String actorName, String action, String before, String after) {}

    public record ImportJobDto(ImportRequestDto importRequest, String source, String purpose, String purposeNote, Long requesterId,
                               LocalDate wantedBy, List<JobItem> items, List<JobRound> rounds, List<JobHistory> history,
                               boolean canEdit) {}

    /** A person the job can be named for on the create and edit form. */
    public record RepOption(long id, String name) {}

    /** One thing the sheet load left for import to check. */
    public record NeedsCheck(int row, String what, String reason) {}

    public record BackfillRunSummary(Instant finishedAt, int roundsCreated, int irsCreated, int irsAdopted, int irsSkipped,
                                     int blocksExcluded, int blocksFailed, int dealsAdvanced, List<NeedsCheck> needsCheck) {}
}
