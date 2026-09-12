package th.co.glr.hr.catalog.importer;

import java.util.List;

/**
 * {@code rows} includes quarantined rows (they ARE staged — see {@link PriceRow#quarantineReason()}
 * — just excluded from commit); {@code errors} are rows dropped entirely before staging (no price,
 * no code where required, an unreadable sheet); {@code quarantined} is a reporting-only summary of
 * the reconciliation failures within {@code rows} so an operator sees counts and reasons rather
 * than a quiet, successful-looking import. A missing/invalid {@code ImportProfile#sizeUnit} fails
 * the whole import: {@code rows} and {@code quarantined} are empty and {@code errors} holds exactly
 * one message naming the profile.
 */
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
