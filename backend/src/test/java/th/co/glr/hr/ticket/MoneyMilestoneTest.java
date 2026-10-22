package th.co.glr.hr.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The ONE stage -> money-milestone mapping (owner decision A2, H1). */
class MoneyMilestoneTest {

    @Test
    void trackHasExactlyTheFiveOwnerMilestonesInOrder() {
        assertThat(MoneyMilestone.TRACK).extracting(MoneyMilestone.Step::index).containsExactly(1, 2, 3, 4, 5);
        assertThat(MoneyMilestone.TRACK).extracting(MoneyMilestone.Step::label).containsExactly(
            "ได้รับคำสั่งซื้อ", "ได้รับมัดจำ", "รอสินค้า / นำเข้า", "ส่งมอบ — รอชำระส่วนที่เหลือ", "ชำระครบ ปิดงาน");
    }

    @Test
    void everyOrderStageFromOrderReceivedMapsToAMilestone() {
        int floor = DealStage.indexOf(DealStage.ORDER_RECEIVED);
        for (String stage : DealStage.ORDER.subList(floor, DealStage.ORDER.size())) {
            assertThat(MoneyMilestone.of(stage)).as(stage).isPresent();
        }
    }

    @Test
    void stageToMilestoneIndex() {
        assertThat(indexOf(DealStage.ORDER_RECEIVED)).isEqualTo(1);
        assertThat(indexOf(DealStage.DEPOSIT_RECEIVED)).isEqualTo(2);
        assertThat(indexOf(DealStage.PROCUREMENT)).isEqualTo(3);
        assertThat(indexOf(DealStage.DELIVERY_SCHEDULING)).isEqualTo(4);
        assertThat(indexOf(DealStage.DELIVERED)).isEqualTo(4);
        assertThat(indexOf(DealStage.CLOSED_PAID)).isEqualTo(5);
    }

    @Test
    void everyPreOrderStageHasNoMilestone() {
        int floor = DealStage.indexOf(DealStage.ORDER_RECEIVED);
        List<String> pre = DealStage.ORDER.subList(0, floor);
        assertThat(pre).isNotEmpty();
        for (String stage : pre) {
            assertThat(MoneyMilestone.of(stage)).as(stage).isEmpty();
        }
        assertThat(MoneyMilestone.of(null)).isEmpty();
        assertThat(MoneyMilestone.of("NOT_A_STAGE")).isEmpty();
    }

    @Test
    void depositMilestoneIsSkippedOnlyWhenPolicyBypassesTheDepositNotice() {
        MoneyMilestone.Step deposit = MoneyMilestone.of(DealStage.DEPOSIT_RECEIVED).orElseThrow();
        assertThat(MoneyMilestone.isSkipped(deposit, DepositPolicy.REQUIRED)).isFalse();
        assertThat(MoneyMilestone.isSkipped(deposit, null)).isFalse();
        assertThat(MoneyMilestone.isSkipped(deposit, DepositPolicy.NOT_REQUIRED)).isTrue();
        assertThat(MoneyMilestone.isSkipped(deposit, DepositPolicy.WAIVED)).isTrue();
        assertThat(MoneyMilestone.isSkipped(deposit, DepositPolicy.CREDIT_CUSTOMER)).isTrue();

        // No other milestone is ever skipped, whatever the policy.
        for (MoneyMilestone.Step step : MoneyMilestone.TRACK) {
            if (step.index() != 2) {
                assertThat(MoneyMilestone.isSkipped(step, DepositPolicy.WAIVED)).as(step.key()).isFalse();
            }
        }
    }

    private static int indexOf(String stage) {
        return MoneyMilestone.of(stage).orElseThrow().index();
    }
}
