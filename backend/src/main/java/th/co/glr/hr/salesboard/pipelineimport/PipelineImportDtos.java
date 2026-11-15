package th.co.glr.hr.salesboard.pipelineimport;

import java.math.BigDecimal;
import java.util.List;

/** Wire shapes of POST /api/sales/pipeline-imports. */
public final class PipelineImportDtos {

    /** What the file would do (dry run) or did (commit). {@code rows} counts non-blank data rows; the four counts
     * partition it. {@code toUpdate} rows fill blank fields of an existing deal; {@code unchanged} rows matched a deal
     * that already holds everything the row says; {@code skipped} rows have an ERROR issue and are never written. */
    public record PipelineImportResult(int rows, int toCreate, int toUpdate, int unchanged, int skipped,
                                       boolean committed, List<RowPlan> plan, List<RowIssue> issues) {}

    /** One data row. {@code action}: CREATE, UPDATE, UNCHANGED or SKIP. {@code repCode} is the code the deal will
     * show — after the route correction, so it can differ from the file (a WARNING issue then says so).
     * {@code ticketId}/{@code ticketCode}: the matched deal, or on a commit the created one; null on a dry-run create.
     * {@code changes}: the Thai labels of the blank fields an UPDATE fills. */
    public record RowPlan(int row, String action, String project, String designer, String ownerName, String repCode,
                          BigDecimal value, Long ticketId, String ticketCode, List<String> changes) {}

    /** {@code severity}: ERROR (the row is skipped) or WARNING (imported, but read this). */
    public record RowIssue(int row, String severity, String message) {}

    public static final String CREATE = "CREATE";
    public static final String UPDATE = "UPDATE";
    public static final String UNCHANGED = "UNCHANGED";
    public static final String SKIP = "SKIP";
    public static final String ERROR = "ERROR";
    public static final String WARNING = "WARNING";

    private PipelineImportDtos() {}
}
