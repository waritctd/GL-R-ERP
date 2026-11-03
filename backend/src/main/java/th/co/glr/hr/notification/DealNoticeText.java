package th.co.glr.hr.notification;

import java.util.Map;

/** Wording shared by the P1 hand-off notifications: the "which deal" line every message carries. */
public final class DealNoticeText {

    private DealNoticeText() {
    }

    /** {@code ดีล <code> · ลูกค้า <customer>}; a blank customer reads "ไม่ระบุลูกค้า" (same fallback as DepositNoticeService). */
    public static String dealLabel(String ticketCode, String customerName) {
        String customer = customerName == null || customerName.isBlank() ? "ไม่ระบุลูกค้า" : customerName.trim();
        return "ดีล " + ticketCode + " · ลูกค้า " + customer;
    }

    private static final Map<String, String> LOST_REASONS = Map.of(
        "PRODUCT_FIT", "ไม่มีสินค้าที่ลูกค้าต้องการ",
        "PRICE", "ราคา",
        "LEAD_TIME", "ระยะเวลาส่งมอบ (leadtime)",
        "PAYMENT_TERMS", "เงื่อนไขการชำระเงิน (payment term)",
        "RELATIONSHIP", "ความสัมพันธ์ / connection",
        "PROJECT_ON_HOLD", "โครงการหยุดก่อสร้างชั่วคราว",
        "PROJECT_CANCELLED", "โครงการหยุดก่อสร้างถาวร",
        "ALREADY_PURCHASED", "ลูกค้าสั่งซื้อกระเบื้องไปหมดแล้ว");

    private static final Map<String, String> CANCEL_REASONS = Map.of(
        "OWNER_CANCELLED", "เจ้าของยกเลิกโครงการ",
        "PROJECT_SUSPENDED", "โครงการถูกระงับไม่มีกำหนด",
        "BUDGET_CANCELLED", "งบประมาณถูกยกเลิก",
        "OTHER", "อื่น ๆ");

    /** Thai label of a DealLostReason code (same wording as frontend LOST_REASON_LABEL); an unknown code reads as itself. */
    public static String lostReason(String code) {
        return LOST_REASONS.getOrDefault(code, String.valueOf(code));
    }

    /** Thai label of a DealCancelReason code (same wording as frontend CANCEL_REASON_LABEL); an unknown code reads as itself. */
    public static String cancelReason(String code) {
        return CANCEL_REASONS.getOrDefault(code, String.valueOf(code));
    }
}
