package th.co.glr.hr.ticket;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The five money milestones finance tracks a deal through (H1, owner decision A2), derived from
 * {@code sales_stage}. This is the ONE place the stage-to-milestone mapping lives -- a client
 * renders {@link #TRACK} and highlights {@link #of}'s result rather than re-deriving it.
 *
 * <pre>
 *   1 ORDER_RECEIVED    S10        ได้รับคำสั่งซื้อ
 *   2 DEPOSIT_RECEIVED  S11        ได้รับมัดจำ            (skipped when the deposit policy bypasses the notice)
 *   3 PROCUREMENT       S12-S17    รอสินค้า / นำเข้า
 *   4 DELIVERY          S18-S19    ส่งมอบ — รอชำระส่วนที่เหลือ
 *   5 CLOSED_PAID       S20        ชำระครบ ปิดงาน
 * </pre>
 *
 * A stage below ORDER_RECEIVED has no money milestone yet.
 */
public final class MoneyMilestone {
    public record Step(String key, int index, String label) {}

    private static final Step ORDER_RECEIVED = new Step("ORDER_RECEIVED", 1, "ได้รับคำสั่งซื้อ");
    private static final Step DEPOSIT_RECEIVED = new Step("DEPOSIT_RECEIVED", 2, "ได้รับมัดจำ");
    private static final Step PROCUREMENT = new Step("PROCUREMENT", 3, "รอสินค้า / นำเข้า");
    private static final Step DELIVERY = new Step("DELIVERY", 4, "ส่งมอบ — รอชำระส่วนที่เหลือ");
    private static final Step CLOSED_PAID = new Step("CLOSED_PAID", 5, "ชำระครบ ปิดงาน");

    /** The track, in order. */
    public static final List<Step> TRACK =
        List.of(ORDER_RECEIVED, DEPOSIT_RECEIVED, PROCUREMENT, DELIVERY, CLOSED_PAID);

    private static final Map<String, Step> BY_STAGE = Map.of(
        DealStage.ORDER_RECEIVED, ORDER_RECEIVED,
        DealStage.DEPOSIT_RECEIVED, DEPOSIT_RECEIVED,
        DealStage.PROCUREMENT, PROCUREMENT,
        DealStage.DELIVERY_SCHEDULING, DELIVERY,
        DealStage.DELIVERED, DELIVERY,
        DealStage.CLOSED_PAID, CLOSED_PAID);

    /** The milestone a deal at {@code salesStage} is on; empty below S10 or for an unknown/null code. */
    public static Optional<Step> of(String salesStage) {
        return salesStage == null ? Optional.empty() : Optional.ofNullable(BY_STAGE.get(salesStage));
    }

    /** Only the deposit milestone can be skipped, and only when the policy bypasses the deposit notice. */
    public static boolean isSkipped(Step step, String depositPolicy) {
        return step != null && step.index() == DEPOSIT_RECEIVED.index() && depositPolicy != null && DepositPolicy.bypassesDepositNotice(depositPolicy);
    }

    private MoneyMilestone() {}
}
