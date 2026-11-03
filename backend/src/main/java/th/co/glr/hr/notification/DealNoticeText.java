package th.co.glr.hr.notification;

/** Wording shared by the P1 hand-off notifications: the "which deal" line every message carries. */
public final class DealNoticeText {

    private DealNoticeText() {
    }

    /** {@code ดีล <code> · ลูกค้า <customer>}; a blank customer reads "ไม่ระบุลูกค้า" (same fallback as DepositNoticeService). */
    public static String dealLabel(String ticketCode, String customerName) {
        String customer = customerName == null || customerName.isBlank() ? "ไม่ระบุลูกค้า" : customerName.trim();
        return "ดีล " + ticketCode + " · ลูกค้า " + customer;
    }
}
