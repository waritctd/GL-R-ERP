package th.co.glr.hr.notification;

/** Wording shared by the P1 hand-off notifications: the "which deal" line every message carries. */
public final class DealNoticeText {

    private DealNoticeText() {
    }

    /** Third line of every "for your information" notice. */
    public static final String FYI = "แจ้งเพื่อทราบ ไม่ต้องดำเนินการ";

    /** {@code ดีล <code> · ลูกค้า <customer>}; a blank customer reads "ไม่ระบุลูกค้า" (same fallback as DepositNoticeService). */
    public static String dealLabel(String ticketCode, String customerName) {
        String customer = customerName == null || customerName.isBlank() ? "ไม่ระบุลูกค้า" : customerName.trim();
        return "ดีล " + ticketCode + " · ลูกค้า " + customer;
    }

    /** P2 B6: the rep's bell/email when a quotation (customer or deal) expires. */
    public static String quotationExpired(String number, String ticketCode, String customerName) {
        return "ใบเสนอราคา " + number + " หมดอายุแล้ว\n"
            + dealLabel(ticketCode, customerName)
            + "\nสิ่งที่ต้องทำต่อ: ติดตามลูกค้า หรือออกใบเสนอราคาใหม่";
    }
}
