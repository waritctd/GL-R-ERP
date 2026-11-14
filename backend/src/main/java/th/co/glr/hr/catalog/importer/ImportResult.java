package th.co.glr.hr.catalog.importer;

import java.util.List;

/** rows includes quarantined rows (they ARE staged. A missing/invalid ImportProfile#sizeUnit fails the whole
 * import: rows and quarantined are empty and errors holds exactly one message naming the profile. */
public record ImportResult(
    List<PriceRow> rows,
    List<String> errors,
    List<QuarantinedRow> quarantined
) {
    public int quarantinedCount() {
        return quarantined.size();
    }

    /** One row an operator needs to look at before it can be trusted, with both figures that disagreed. */
    public record QuarantinedRow(
        String sourceSheet,
        int sourceRow,
        String productCode,
        String reason
    ) {}
}
