package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Change;

/** The decision half of the release-notice gate (the enforcement half is ReleaseNoticeIntegrationTest). */
class ReleaseNoticePolicyTest {

    @Test
    void onlyCeoAndHrMayManageAReleaseNotice() {
        assertThat(ReleaseNoticePolicy.canManage("ceo")).isTrue();
        assertThat(ReleaseNoticePolicy.canManage("hr")).isTrue();
    }

    // ── The email's content: the one-sentence why, the release date, the per-role cap ──────────────────────

    @Test
    void changesForIsWrongWayRound_aRoleNeverGetsAnotherRolesEntry() {
        List<Change> changes = List.of(
            new Change("งานของฉัน", List.of("sales"), "ทำไมของฝ่ายขาย"),
            new Change("รับเงิน", List.of("account"), "ทำไมของบัญชี"),
            new Change("เมนูใหม่", List.copyOf(ReleaseNoticePolicy.ROLES), "ทำไมของทุกคน"));

        assertThat(ReleaseNoticePolicy.changesFor("sales", changes)).extracting(Change::title)
            .containsExactly("งานของฉัน", "เมนูใหม่");
        assertThat(ReleaseNoticePolicy.changesFor("account", changes)).extracting(Change::title)
            .containsExactly("รับเงิน", "เมนูใหม่");
        assertThat(ReleaseNoticePolicy.changesFor("warehouse", changes)).extracting(Change::title)
            .containsExactly("เมนูใหม่");
        assertThat(ReleaseNoticePolicy.changesFor("warehouse", changes.subList(0, 2))).isEmpty();
        assertThat(ReleaseNoticePolicy.changesFor(null, changes)).isEmpty();
    }

}
