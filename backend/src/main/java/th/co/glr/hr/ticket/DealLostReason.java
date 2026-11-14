package th.co.glr.hr.ticket;

import java.util.Set;

/** Reasons a deal is marked lost (V50). Cross-reference to the business's F1–F8 sheet: PRODUCT_FIT=F1,
 * PRICE=F2, LEAD_TIME=F3, PAYMENT_TERMS=F4, RELATIONSHIP=F5, PROJECT_ON_HOLD=F6, PROJECT_CANCELLED=F7,
 * ALREADY_PURCHASED=F8. */
public final class DealLostReason {
    public static final String PRODUCT_FIT       = "PRODUCT_FIT";
    public static final String PRICE             = "PRICE";
    public static final String LEAD_TIME         = "LEAD_TIME";
    public static final String PAYMENT_TERMS     = "PAYMENT_TERMS";
    public static final String RELATIONSHIP      = "RELATIONSHIP";
    public static final String PROJECT_ON_HOLD   = "PROJECT_ON_HOLD";
    public static final String PROJECT_CANCELLED = "PROJECT_CANCELLED";
    public static final String ALREADY_PURCHASED = "ALREADY_PURCHASED";

    /** F1–F8 in the business's own order, for a client that has to render them as a list. #VALID is derived from it
     * so the two can never hold different codes — the frontend used to keep its own copy of this list, which is
     * exactly how a code set goes stale. */
    public static final java.util.List<String> ORDER = java.util.List.of(
        PRODUCT_FIT, PRICE, LEAD_TIME, PAYMENT_TERMS,
        RELATIONSHIP, PROJECT_ON_HOLD, PROJECT_CANCELLED, ALREADY_PURCHASED
    );

    public static final Set<String> VALID = Set.copyOf(ORDER);

    public static boolean isValid(String reason) {
        return reason != null && VALID.contains(reason);
    }

    private static final java.util.Map<String, String> LABELS = java.util.Map.of(
        PRODUCT_FIT, "ไม่มีสินค้าที่ลูกค้าต้องการ",
        PRICE, "ราคา",
        LEAD_TIME, "ระยะเวลาส่งมอบ (leadtime)",
        PAYMENT_TERMS, "เงื่อนไขการชำระเงิน (payment term)",
        RELATIONSHIP, "ความสัมพันธ์ / connection",
        PROJECT_ON_HOLD, "โครงการหยุดก่อสร้างชั่วคราว",
        PROJECT_CANCELLED, "โครงการหยุดก่อสร้างถาวร",
        ALREADY_PURCHASED, "ลูกค้าสั่งซื้อกระเบื้องไปหมดแล้ว");

    /** Thai label of a code (same wording as frontend LOST_REASON_LABEL); an unknown code reads as itself. */
    public static String label(String code) {
        return LABELS.getOrDefault(code, String.valueOf(code));
    }

    private DealLostReason() {}
}
