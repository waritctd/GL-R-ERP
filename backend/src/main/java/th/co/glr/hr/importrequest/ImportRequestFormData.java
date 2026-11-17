package th.co.glr.hr.importrequest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Everything F-SM-001 (04) prints, and nothing else — the render input for ImportRequestRenderer. The paper
 * form is routinely issued with the approval blocks blank for wet signature (owner ruling), and a null simply
 * leaves that rule empty — the renderer never substitutes a placeholder. */
public record ImportRequestFormData(
    /** "ReF. No." — e.g. {@code IR69068}. Null on a draft preview, which has no number yet. */
    String docNumber,
    /** "Brand" — one brand per form; a deal spanning two brands produces two forms. */
    String brand,
    /** "Request date" — the date the document was raised. */
    LocalDate requestDate,
    /** The {@code Project : ...} line the business writes as the first body row. */
    String projectName,
    /** "สั่งมาให้" — who the goods are ordered for. */
    String customerName,
    /** "Requested by" — the deal's sales rep. */
    String requestedByName,
    /** "กำหนดวันที่ต้องการของ" — free text ("Within 21/5/26"), supplied by sales. */
    String requiredByNote,
    /** "กำหนดเรือเข้าโดยประมาณ" — free text, supplied by import. */
    String vesselEtaNote,
    /** "Checked By" (Buyer or Senior Buyer). */
    String checkedByName,
    /** "Checked date". */
    LocalDate checkedDate,
    /** "Approve By" (General Manager or Managing Director). */
    String approvedByName,
    /** "Approve date". */
    LocalDate approvedDate,
    /** "Request By" (footer) — the import staffer who raised the form. */
    String issuedByName,
    /** "วันที่ได้รับมัดจำ" — from the deal's payment track. */
    LocalDate depositReceivedDate,
    /** The table body, in printed order. */
    List<Line> lines
) {
    /** One printed line of the form's table. #note is the free-text sub-row the business writes underneath a line
     * (the owner's IR69068 reads "สั่งตามPO" under each). It occupies a SECOND table row when present, which is
     * why ImportRequestRenderer#rowsRequired exists — capacity is counted in rows, not lines. */
    public record Line(
        int seq,
        String code,
        String size,
        BigDecimal qty,
        String unit,
        String note
    ) {}
}
