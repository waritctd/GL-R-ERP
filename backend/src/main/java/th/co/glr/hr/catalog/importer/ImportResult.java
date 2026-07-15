package th.co.glr.hr.catalog.importer;

import java.util.List;

public record ImportResult(
    List<PriceRow> rows,
    List<String> errors
) {}
