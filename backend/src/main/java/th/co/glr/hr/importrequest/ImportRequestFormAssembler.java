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
     */
    static ImportRequestFormData fromStored(ImportRequestDtos.ImportRequestDto row) {
        return new ImportRequestFormData(
            row.docNumber(),
            row.brand(),
            row.issueDate(),
            row.projectName(),
            row.customerName(),
            row.requestedByName(),
            row.requiredByNote(),
            row.vesselEtaNote(),
            row.checkedByName(),
            row.checkedDate(),
            row.approvedByName(),
            row.approvedDate(),
            row.issuedByName(),
            row.depositReceivedDate(),
            row.items().stream()
                .map(it -> new ImportRequestFormData.Line(it.seq(), it.code(), it.size(), it.qty(),
                                                          it.unit(), it.note()))
                .toList());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
