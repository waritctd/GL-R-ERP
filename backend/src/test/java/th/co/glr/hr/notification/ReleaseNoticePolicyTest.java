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
            change("พอร์ทัลย้าย", List.copyOf(ReleaseNoticePolicy.ROLES)),
            change("งานของฉัน", List.of("sales", "sales_manager", "ceo")),
            change("รับเงิน", List.of("account")));

        assertThat(ReleaseNoticePolicy.titlesFor("sales", changes)).containsExactly("พอร์ทัลย้าย", "งานของฉัน");
        assertThat(ReleaseNoticePolicy.titlesFor("account", changes)).containsExactly("พอร์ทัลย้าย", "รับเงิน");
        assertThat(ReleaseNoticePolicy.titlesFor("warehouse", changes)).containsExactly("พอร์ทัลย้าย");
        assertThat(ReleaseNoticePolicy.titlesFor("warehouse", changes.subList(1, 3))).isEmpty();
        assertThat(ReleaseNoticePolicy.titlesFor(null, changes)).isEmpty();
    }

    @Test
    void normalizeTrimsAndLowerCasesAndKeepsOrder() {
        Request normalized = ReleaseNoticePolicy.normalize(new Request(" 2026-11-16 ", "  อัปเดตระบบขาย ",
            List.of(change(" งานของฉัน ", List.of(" Sales ", "CEO"))), null, null));

        assertThat(normalized.releaseId()).isEqualTo("2026-11-16");
        assertThat(normalized.title()).isEqualTo("อัปเดตระบบขาย");
        assertThat(normalized.changes()).containsExactly(new Change("งานของฉัน", List.of("sales", "ceo"), null));
    }

    @Test
    void normalizeRejectsAnUnknownRoleRatherThanTargetingNobodyOrEverybody() {
        expectBadRequest(request("2026-11-16", "t", List.of(change("x", List.of("sales", "admin")))));
    }

    @Test
    void normalizeRejectsMalformedRequests() {
        expectBadRequest(null);
        expectBadRequest(request(null, "t", List.of(change("x", List.of("sales")))));
        // The release id becomes a URL path segment: no slashes, dots, spaces or upper case.
        expectBadRequest(request("../etc", "t", List.of(change("x", List.of("sales")))));
        expectBadRequest(request("2026/11/16", "t", List.of(change("x", List.of("sales")))));
        expectBadRequest(request("Release 1", "t", List.of(change("x", List.of("sales")))));
        expectBadRequest(request("a".repeat(41), "t", List.of(change("x", List.of("sales")))));
        expectBadRequest(request("2026-11-16", " ", List.of(change("x", List.of("sales")))));
        expectBadRequest(request("2026-11-16", "ท".repeat(151), List.of(change("x", List.of("sales")))));
        expectBadRequest(request("2026-11-16", "t", null));
        expectBadRequest(request("2026-11-16", "t", List.of()));
        expectBadRequest(request("2026-11-16", "t", List.of(change(" ", List.of("sales")))));
        expectBadRequest(request("2026-11-16", "t", List.of(change("x", List.of()))));
        expectBadRequest(request("2026-11-16", "t", List.of(change("x", null))));
        List<String> withNull = new ArrayList<>(List.of("sales"));
        withNull.add(null);
        expectBadRequest(request("2026-11-16", "t", List.of(change("x", withNull))));
        expectBadRequest(request("2026-11-16", "t", Collections.nCopies(51, change("x", List.of("sales")))));
    }

    // ── The email's content: the one-sentence why, the release date, the per-role cap ──────────────────────

    @Test
    void normalizeKeepsTheWhyOnOneLine_dropsABlankOne_andStripsControlCharacters() {
        Request normalized = ReleaseNoticePolicy.normalize(new Request("2026-11-17", "t", List.of(
            new Change("a", List.of("sales"), "  เห็นงาน\n  ทั้งทีม\tในหน้าเดียว  "),
            new Change("b", List.of("sales"), "   "),
            new Change("c", List.of("sales"), "ก\u0000ข\u0007ค"),
            new Change("d", List.of("sales"), null)), null, null));

        assertThat(normalized.changes()).extracting(Change::why)
            .containsExactly("เห็นงาน ทั้งทีม ในหน้าเดียว", null, "กขค", null);
    }

    @Test
    void normalizeRefusesAWhyOverThreeHundredCharacters() {
        String ok = "ท".repeat(ReleaseNoticePolicy.MAX_WHY);
        assertThat(ReleaseNoticePolicy.normalize(new Request("2026-11-17", "t",
            List.of(new Change("a", List.of("sales"), ok)), null, null)).changes().get(0).why()).isEqualTo(ok);

        expectBadRequest(new Request("2026-11-17", "t",
            List.of(new Change("a", List.of("sales"), ok + "ท")), null, null));
    }

    @Test
    void normalizeAcceptsAnIsoDateOrNone_andRefusesAnythingElse() {
        assertThat(ReleaseNoticePolicy.normalize(new Request("2026-11-17", "t",
            List.of(change("a", List.of("sales"))), " 2026-11-17 ", null)).date()).isEqualTo("2026-11-17");
        assertThat(ReleaseNoticePolicy.normalize(new Request("2026-11-17", "t",
            List.of(change("a", List.of("sales"))), null, null)).date()).isNull();
        for (String bad : List.of("2026-13-01", "17/11/2026", "2026-11-17T00:00", "17 พ.ย. 2569", "2026-02-30")) {
            expectBadRequest(new Request("2026-11-17", "t", List.of(change("a", List.of("sales"))), bad, null));
        }
    }

    @Test
    void theReleaseDateIsWrittenTheThaiWay() {
        assertThat(ReleaseNoticePolicy.thaiDate("2026-11-17")).isEqualTo("17 พ.ย. 2569");
        assertThat(ReleaseNoticePolicy.thaiDate("2027-01-05")).isEqualTo("5 ม.ค. 2570");
        assertThat(ReleaseNoticePolicy.thaiDate(null)).isNull();
    }

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

    @Test
    void theEmailNamesTheDateAndListsTheReadersEntriesWithTheirWhy() {
        Request release = new Request("2026-11-17", "เมนูแบบใหม่", List.of(), "2026-11-17", null);
        ReleaseNoticeEmail email = ReleaseNoticePolicy.email(release, List.of(
            new Change("งานทั้งหมดเป็นกระดาน", List.of("sales"), "เห็นขั้นของงานทันที"),
            new Change("นัดเข้าพบ", List.of("sales"), null)));

        assertThat(email.subject()).isEqualTo("มีอะไรใหม่ 17 พ.ย. 2569: เมนูแบบใหม่");
        assertThat(email.intro()).contains("17 พ.ย. 2569").contains("2 เรื่อง");
        assertThat(email.items()).containsExactly(
            new ReleaseNoticeEmail.Item("งานทั้งหมดเป็นกระดาน", "เห็นขั้นของงานทันที"),
            new ReleaseNoticeEmail.Item("นัดเข้าพบ", null));
        assertThat(email.moreLine()).isNull();
        assertThat(email.link()).isEqualTo("/whats-new/2026-11-17");
        assertThat(email.ctaLabel()).isNotBlank();
        assertThat(email.outro()).isNotBlank();
    }

    @Test
    void withoutADateTheSubjectStillNamesTheRelease() {
        Request release = new Request("2026-11-17", "เมนูแบบใหม่", List.of(), null, null);
        ReleaseNoticeEmail email = ReleaseNoticePolicy.email(release,
            List.of(new Change("a", List.of("sales"), null)));

        assertThat(email.subject()).isEqualTo("มีอะไรใหม่: เมนูแบบใหม่");
        assertThat(email.intro()).contains("1 เรื่อง");
    }

    @Test
    void aLongListIsCappedAtAHandfulWithAMoreLine() {
        Request release = new Request("2026-11-17", "t", List.of(), "2026-11-17", null);
        List<Change> seven = new ArrayList<>();
        for (int i = 1; i <= 7; i++) {
            seven.add(new Change("เรื่อง " + i, List.of("sales"), "เหตุผล " + i));
        }

        ReleaseNoticeEmail capped = ReleaseNoticePolicy.email(release, seven);

        assertThat(capped.items()).hasSize(ReleaseNoticePolicy.MAX_EMAIL_ITEMS).extracting(ReleaseNoticeEmail.Item::title)
            .containsExactly("เรื่อง 1", "เรื่อง 2", "เรื่อง 3", "เรื่อง 4", "เรื่อง 5");
        assertThat(capped.moreLine()).isEqualTo("และอีก 2 เรื่อง");
        assertThat(capped.intro()).contains("7 เรื่อง");
        assertThat(ReleaseNoticePolicy.email(release, seven.subList(0, 5)).moreLine()).isNull();
        assertThat(ReleaseNoticePolicy.email(release, seven.subList(0, 6)).moreLine()).isEqualTo("และอีก 1 เรื่อง");
    }

    @Test
    void theBellRowNamesTheReleaseAndCountsTheGivenTitles() {
        assertThat(ReleaseNoticePolicy.bellTitle("อัปเดตระบบขาย")).isEqualTo("มีอะไรใหม่: อัปเดตระบบขาย");
        String message = ReleaseNoticePolicy.bellMessage("อัปเดตระบบขาย", List.of("งานของฉัน", "นัดเข้าพบ"));
        assertThat(message).contains("อัปเดตระบบขาย").contains("2 เรื่อง");
    }

    private static Change change(String title, List<String> roles) {
        return new Change(title, roles, null);
    }

    private static Request request(String releaseId, String title, List<Change> changes) {
        return new Request(releaseId, title, changes, null, null);
    }

    private static void expectBadRequest(Request request) {
        assertThatThrownBy(() -> ReleaseNoticePolicy.normalize(request))
            .isInstanceOf(ApiException.class)
            .hasFieldOrPropertyWithValue("status", HttpStatus.BAD_REQUEST);
    }
}
