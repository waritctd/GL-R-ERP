package th.co.glr.hr.importrequest;

import java.time.LocalDate;
import java.util.List;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.BrandLines;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.Line;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.TicketSnapshot;

/**
 * Turns a deal + one brand's lines into what F-SM-001 prints.
 *
 * <p>Sits between the query repository and {@link ImportRequestRenderer} for the same reason
 * {@code LorYor01FormAssembler} sits between the declaration aggregate and its renderer: the
 * renderer's job is coordinates, and it should not also have to know which value came from the
 * deal, which from the payment track, and which the caller typed.
 *
 * <p>Pure and static — no Spring, no I/O — so its mapping decisions are unit-testable without a
 * database or a PDF.
 */
final class ImportRequestFormAssembler {

    private ImportRequestFormAssembler() {}

    /**
     * @param requestDate the form's "Request date". Passed in rather than read from a clock here so
     *                    the caller owns time; a renderer test that hardcodes a date would otherwise
     *                    be comparing against today.
     * @param issuedBy    "Request By" (footer) — the import staffer generating the form. This is
     *                    NOT the same person as "Requested by" in the table, which is the deal's
     *                    sales rep; the owner's IR69068 shows "Ya" in the column and "Jennet" in
     *                    the footer.
     */
    static ImportRequestFormData assemble(TicketSnapshot snapshot, BrandLines group,
                                          String docNumber, String requiredBy,
                                          LocalDate requestDate, String issuedBy) {
        List<ImportRequestFormData.Line> lines = new java.util.ArrayList<>();
        int seq = 1;
        for (Line line : group.lines()) {
            lines.add(new ImportRequestFormData.Line(
                seq++,
                line.code(),
                line.size(),
                line.qty(),
                line.unit(),
                // The "สั่งตามPO" sub-row on the paper original is a per-line annotation the
                // business writes by hand. There is nowhere on the deal it could come from, so it
                // is deliberately left null rather than invented — that also halves the row cost of
                // a line, which is what makes 26 rows hold 26 items instead of 13.
                null));
        }

        return new ImportRequestFormData(
            blankToNull(docNumber),
            group.brand(),
            requestDate,
            snapshot.projectName(),
            snapshot.customerName(),
            snapshot.requestedByName(),
            blankToNull(requiredBy),
            // กำหนดเรือเข้าโดยประมาณ and both approval blocks print EMPTY. Owner ruling: the
            // approval sequence stays a wet-signature process for now, and nothing in this build
            // stores those fields, so there is nothing to fill them from.
            null,
            null, null,
            null, null,
            issuedBy,
            snapshot.depositReceivedDate(),
            lines);
    }

    /**
     * The STORED path: prints a saved row from its OWN snapshot, not from the deal.
     *
     * <p>That is the whole point of storing it. Once a form is issued, editing the deal must not
     * retroactively change what a signed controlled document says — the same discipline
     * {@code sales.deposit_notice} applies to its customer snapshot. So nothing here reads the ticket;
     * every value comes off the {@code sales.import_request} row.
     *
     * <p>"Request date" is the row's {@code issueDate}, which is null on a DRAFT — a draft preview
     * correctly prints no date, because it has not been raised.
     *
     * <p><b>REVIEW ROUND 2, S-B:</b> the printed "Brand" header names the FACTORY, not the brand
     * snapshot alone — V184 moved the grouping key (and so the whole point of one form per header)
     * from brand to {@code factoryId}, so a per-factory form that named only a brand would no longer
     * say which factory it is FOR when a factory carries lines spanning more than one brand (the
     * exact case {@code sales.import_request.brand}'s own comment describes). See {@link
     * #factoryHeader}.
     *
     * <p>Defaults to the INTERNAL copy — see {@link #fromStored(ImportRequestDtos.ImportRequestDto,
     * boolean)} for the FACTORY copy (owner decision 09-19 #2).
     */
    static ImportRequestFormData fromStored(ImportRequestDtos.ImportRequestDto row) {
        return fromStored(row, false);
    }

    /**
     * @param factoryCopy OWNER DECISION 09-19 #2, extended by REVIEW ROUND 3 item 5: {@code true}
     *                    blanks {@code customerName} ("สั่งมาให้"), {@code depositReceivedDate}
     *                    ("วันที่ได้รับมัดจำ"), AND {@code projectName} — a project name often
     *                    identifies the customer just as surely as their name would (the same
     *                    reasoning the order-email subject already applies, owner decision 09-19
     *                    #2), so the factory copy must not carry it either. Every OTHER field —
     *                    factory/brand header, requested-by, lines, required-by, vessel ETA, footer
     *                    — is identical between the two copies; only these three are ever withheld.
     */
    static ImportRequestFormData fromStored(ImportRequestDtos.ImportRequestDto row, boolean factoryCopy) {
        return new ImportRequestFormData(
            row.docNumber(),
            factoryHeader(row.factoryName(), row.brand()),
            row.issueDate(),
            factoryCopy ? null : row.projectName(),
            factoryCopy ? null : row.customerName(),
            row.requestedByName(),
            row.requiredByNote(),
            row.vesselEtaNote(),
            row.checkedByName(),
            row.checkedDate(),
            row.approvedByName(),
            row.approvedDate(),
            row.issuedByName(),
            factoryCopy ? null : row.depositReceivedDate(),
            row.items().stream()
                .map(it -> new ImportRequestFormData.Line(it.seq(), it.code(), it.size(), it.qty(),
                                                          it.unit(), it.note()))
                .toList());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }

    /**
     * "<Factory>" alone, or "<Factory> (<Brand>)" when the row also carries a brand snapshot worth
     * showing — REVIEW ROUND 2, S-B. {@code factoryName} is expected non-blank on every STORED row
     * (the service never inserts one without a resolved factory — see {@code
     * sales.import_request.factory_id}'s own comment), but falls back to the brand alone rather than
     * printing nothing if it somehow is blank, so a header field is never simply empty.
     */
    private static String factoryHeader(String factoryName, String brand) {
        String name = blankToNull(factoryName);
        String brandLabel = blankToNull(brand);
        if (name == null) {
            return brandLabel;
        }
        return brandLabel == null ? name : name + " (" + brandLabel + ")";
    }
}
