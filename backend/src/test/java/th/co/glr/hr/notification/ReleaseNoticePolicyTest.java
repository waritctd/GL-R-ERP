package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Change;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Request;

/** The decision half of the release-notice gate (the enforcement half is ReleaseNoticeIntegrationTest). */
class ReleaseNoticePolicyTest {

    private static final List<String> NON_MANAGERS =
        List.of("sales", "sales_manager", "import", "account", "warehouse", "qc", "employee");

    @Test
    void onlyCeoAndHrMayManageAReleaseNotice() {
        assertThat(ReleaseNoticePolicy.canManage("ceo")).isTrue();
        assertThat(ReleaseNoticePolicy.canManage("hr")).isTrue();
    }

    // Wrong-way-round: every other role DivisionAccessPolicy.roleFor can return is refused, and so are the
    // degenerate inputs. A gate that only proves the two good roles pass would also pass a gate that admits all.
    @Test
    void everyOtherRoleIsRefused() {
        for (String role : NON_MANAGERS) {
            assertThat(ReleaseNoticePolicy.canManage(role)).as(role).isFalse();
        }
        assertThat(ReleaseNoticePolicy.canManage(null)).isFalse();
        assertThat(ReleaseNoticePolicy.canManage("")).isFalse();
        assertThat(ReleaseNoticePolicy.canManage("CEO")).as("role names are exact, lower-case").isFalse();
        assertThat(ReleaseNoticePolicy.canManage("admin")).isFalse();
    }

    @Test
    void theNineTargetableRolesAreExactlyWhatRoleForCanReturn() {
        assertThat(ReleaseNoticePolicy.ROLES).containsExactlyInAnyOrder(
            "ceo", "hr", "import", "account", "warehouse", "qc", "sales_manager", "sales", "employee");
    }

    @Test
    void theBellLinkIsTheReleasePage() {
        assertThat(ReleaseNoticePolicy.link("2026-11-16")).isEqualTo("/whats-new/2026-11-16");
    }

    @Test
    void titlesForPicksOnlyTheChangesWhoseAudienceIncludesTheRole() {
        List<Change> changes = List.of(
            new Change("พอร์ทัลย้าย", List.copyOf(ReleaseNoticePolicy.ROLES)),
            new Change("งานของฉัน", List.of("sales", "sales_manager", "ceo")),
            new Change("รับเงิน", List.of("account")));

        assertThat(ReleaseNoticePolicy.titlesFor("sales", changes)).containsExactly("พอร์ทัลย้าย", "งานของฉัน");
        assertThat(ReleaseNoticePolicy.titlesFor("account", changes)).containsExactly("พอร์ทัลย้าย", "รับเงิน");
        assertThat(ReleaseNoticePolicy.titlesFor("warehouse", changes)).containsExactly("พอร์ทัลย้าย");
        assertThat(ReleaseNoticePolicy.titlesFor("warehouse", changes.subList(1, 3))).isEmpty();
        assertThat(ReleaseNoticePolicy.titlesFor(null, changes)).isEmpty();
    }

    @Test
    void normalizeTrimsAndLowerCasesAndKeepsOrder() {
        Request normalized = ReleaseNoticePolicy.normalize(new Request(" 2026-11-16 ", "  อัปเดตระบบขาย ",
            List.of(new Change(" งานของฉัน ", List.of(" Sales ", "CEO")))));

        assertThat(normalized.releaseId()).isEqualTo("2026-11-16");
        assertThat(normalized.title()).isEqualTo("อัปเดตระบบขาย");
        assertThat(normalized.changes()).containsExactly(new Change("งานของฉัน", List.of("sales", "ceo")));
    }

    @Test
    void normalizeRejectsAnUnknownRoleRatherThanTargetingNobodyOrEverybody() {
        expectBadRequest(new Request("2026-11-16", "t", List.of(new Change("x", List.of("sales", "admin")))));
    }

    @Test
    void normalizeRejectsMalformedRequests() {
        expectBadRequest(null);
        expectBadRequest(new Request(null, "t", List.of(new Change("x", List.of("sales")))));
        // The release id becomes a URL path segment: no slashes, dots, spaces or upper case.
        expectBadRequest(new Request("../etc", "t", List.of(new Change("x", List.of("sales")))));
        expectBadRequest(new Request("2026/11/16", "t", List.of(new Change("x", List.of("sales")))));
        expectBadRequest(new Request("Release 1", "t", List.of(new Change("x", List.of("sales")))));
        expectBadRequest(new Request("a".repeat(41), "t", List.of(new Change("x", List.of("sales")))));
        expectBadRequest(new Request("2026-11-16", " ", List.of(new Change("x", List.of("sales")))));
        expectBadRequest(new Request("2026-11-16", "ท".repeat(151), List.of(new Change("x", List.of("sales")))));
        expectBadRequest(new Request("2026-11-16", "t", null));
        expectBadRequest(new Request("2026-11-16", "t", List.of()));
        expectBadRequest(new Request("2026-11-16", "t", List.of(new Change(" ", List.of("sales")))));
        expectBadRequest(new Request("2026-11-16", "t", List.of(new Change("x", List.of()))));
        expectBadRequest(new Request("2026-11-16", "t", List.of(new Change("x", null))));
        List<String> withNull = new ArrayList<>(List.of("sales"));
        withNull.add(null);
        expectBadRequest(new Request("2026-11-16", "t", List.of(new Change("x", withNull))));
        expectBadRequest(new Request("2026-11-16", "t",
            Collections.nCopies(51, new Change("x", List.of("sales")))));
    }

    @Test
    void theBellRowAndTheEmailNameTheReleaseAndListOnlyTheGivenTitles() {
        assertThat(ReleaseNoticePolicy.bellTitle("อัปเดตระบบขาย")).isEqualTo("มีอะไรใหม่: อัปเดตระบบขาย");
        String message = ReleaseNoticePolicy.bellMessage("อัปเดตระบบขาย", List.of("งานของฉัน", "นัดเข้าพบ"));
        assertThat(message).contains("อัปเดตระบบขาย").contains("2 เรื่อง");

        assertThat(ReleaseNoticePolicy.emailSubject("อัปเดตระบบขาย")).isEqualTo("มีอะไรใหม่: อัปเดตระบบขาย");
        String body = ReleaseNoticePolicy.emailBody(List.of("งานของฉัน", "นัดเข้าพบ"));
        assertThat(body).contains("• งานของฉัน").contains("• นัดเข้าพบ").contains("2 เรื่อง");
    }

    private static void expectBadRequest(Request request) {
        assertThatThrownBy(() -> ReleaseNoticePolicy.normalize(request))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }
}
