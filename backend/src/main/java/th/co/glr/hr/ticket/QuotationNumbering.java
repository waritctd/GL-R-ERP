package th.co.glr.hr.ticket;

/**
 * The {@code {base}-{revisionNo}} numbering scheme shared by BOTH quotation flows that write
 * {@code sales.quotation} — the direct deal quotation ({@code dealquotation/}) and the
 * คำขอราคา/PricingRequest-chain quotation ({@code customerquotation/}). One {@code number} column,
 * one UNIQUE constraint (V6), one sequence ({@code sales.quotation_code_seq}) — so the string
 * format the two flows print must never be allowed to drift into two implementations that could
 * silently disagree.
 *
 * <p>Owner ruling (2026-09-18, PricingRequest-chain quotation numbering): a document's FIRST
 * revision is {@code QT-<year>-<seq>-1} — the suffix starts at 1, not "bare" — and each later
 * revision bumps the suffix on the SAME base ({@code -2}, {@code -3}, …). This is versioning, not
 * a new document number, matching the rule the direct quotation flow already shipped under
 * (0b630107, "quotation numbers carry -1 from the first issue").
 *
 * <p>Extracted out of {@code dealquotation.DealQuotationRepository}, which had this logic first —
 * see that class's git history for the fuller original Javadoc this trims down. Pure string
 * functions, no DB access, so both {@code DealQuotationRepository} (which keeps its own
 * {@code baseNumber}/{@code revisionNumber} methods as thin delegates, for its existing callers)
 * and {@code customerquotation.CustomerQuotationService} (which calls this class directly) share
 * exactly one implementation.
 */
public final class QuotationNumbering {
    private QuotationNumbering() {}

    /**
     * A revision child's number is {@code {base}-{revisionNo}} — INCLUDING revision 1 (owner
     * feedback 2026-09-11 on the direct-quotation flow: "มีรันเลข -1 -2 ต่อท้ายตี้วแต่แรก" / "ใบแรก
     * เป็น QT-2026-0014-1"; re-confirmed for the PricingRequest-chain flow 2026-09-18). Since every
     * number this class produces was itself built by {@link #revisionNumber}, the trailing
     * {@code "-" + sourceRevisionNo} suffix on a source number always exactly identifies the base
     * — so stripping it recovers the ORIGINAL base number regardless of how many times the chain
     * has already been revised, without needing to walk {@code parent_quotation_id} all the way to
     * the root.
     *
     * <p><b>Legacy rows:</b> a quotation issued BEFORE this change carries a BARE number (no
     * {@code -1}) at {@code revisionNo == 1} — those rows are never rewritten/migrated (owner
     * ruling: "existing production quotations with bare numbers are LEFT ALONE"). For such a row,
     * {@code sourceNumber} simply does not end with {@code "-1"} (the base is always
     * {@code QT-<year>-<4-digit seq>}, so it can never accidentally collide with a {@code -N}
     * suffix), so the {@code endsWith} check below falls through and returns it unchanged — exactly
     * the base it already is. Revising such a row therefore mints {@code {bare}-2} (treating the
     * bare original as version 1), never {@code {bare}-1} (which would collide conceptually with
     * the original) and never a brand-new code. One method handles both eras with no special-casing
     * of {@code sourceRevisionNo == 1}.
     */
    public static String baseNumber(String sourceNumber, int sourceRevisionNo) {
        String suffix = "-" + sourceRevisionNo;
        return sourceNumber.endsWith(suffix)
            ? sourceNumber.substring(0, sourceNumber.length() - suffix.length())
            : sourceNumber;
    }

    /** Always {@code {base}-{revisionNo}}, including revision 1 — see {@link #baseNumber}'s
     * Javadoc for why a bare number never appears for anything minted after this change. */
    public static String revisionNumber(String baseNumber, int revisionNo) {
        return baseNumber + "-" + revisionNo;
    }
}
