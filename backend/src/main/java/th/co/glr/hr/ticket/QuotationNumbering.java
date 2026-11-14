package th.co.glr.hr.ticket;

/** The {base-{revisionNo}} numbering scheme shared by BOTH quotation flows that write sales.quotation — the
 * direct deal quotation (dealquotation/) and the คำขอราคา/PricingRequest-chain quotation (customerquotation/). */
public final class QuotationNumbering {
    private QuotationNumbering() {}

    /** A revision child's number is {base-{revisionNo}} — INCLUDING revision 1 (owner feedback 2026-09-11 on the
     * direct-quotation flow: "มีรันเลข -1 -2 ต่อท้ายตี้วแต่แรก" / "ใบแรก เป็น QT-2026-0014-1"; re-confirmed for
     * the PricingRequest-chain flow 2026-09-18). */
    public static String baseNumber(String sourceNumber, int sourceRevisionNo) {
        String suffix = "-" + sourceRevisionNo;
        return sourceNumber.endsWith(suffix)
            ? sourceNumber.substring(0, sourceNumber.length() - suffix.length())
            : sourceNumber;
    }

    /** Always {base-{revisionNo}}, including revision 1 — see #baseNumber's Javadoc for why a bare number never
     * appears for anything minted after this change. */
    public static String revisionNumber(String baseNumber, int revisionNo) {
        return baseNumber + "-" + revisionNo;
    }
}
