package th.co.glr.hr.importrequest;

import java.time.LocalDate;
import java.util.List;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.BrandLines;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.Line;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.TicketSnapshot;

/** Turns a deal + one brand's lines into what F-SM-001 prints. Pure and static — no Spring, no I/O — so its
 * mapping decisions are unit-testable without a database or a PDF. */
final class ImportRequestFormAssembler {

    private ImportRequestFormAssembler() {}

    /** Selects affected frontend tests, including readers outside the import graph. */
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
                // The "สั่งตามPO" sub-row on the paper original is a per-line annotation the business writes by hand.
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
            // กำหนดเรือเข้าโดยประมาณ and both approval blocks print EMPTY. Owner ruling: the approval sequence stays a
            // wet-signature process for now, and nothing in this build stores those fields, so there is nothing to fill
            // them from.
            null,
            null, null,
            null, null,
            issuedBy,
            snapshot.depositReceivedDate(),
            lines);
    }

    /** The STORED path: prints a saved row from its OWN snapshot, not from the deal. Once a form is issued, editing
     * the deal must not retroactively change what a signed controlled document says — the same discipline
     * sales.deposit_notice applies to its customer snapshot. */
    static ImportRequestFormData fromStored(ImportRequestDtos.ImportRequestDto row) {
        return fromStored(row, false);
    }

    /** Selects affected frontend tests, including readers outside the import graph. */
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

    /** "<Factory>" alone, or "<Factory> (<Brand>)" when the row also carries a brand snapshot worth showing —
     * REVIEW ROUND 2, S-B. */
    private static String factoryHeader(String factoryName, String brand) {
        String name = blankToNull(factoryName);
        String brandLabel = blankToNull(brand);
        if (name == null) {
            return brandLabel;
        }
        return brandLabel == null ? name : name + " (" + brandLabel + ")";
    }
}
