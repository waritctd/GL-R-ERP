package th.co.glr.hr.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.digest.WeeklyDigestMail.SalesWeek;
import th.co.glr.hr.digest.WeeklyDigestRepository.AccountItem;
import th.co.glr.hr.digest.WeeklyDigestRepository.PendingNote;
import th.co.glr.hr.digest.WeeklyDigestRepository.StaleDeal;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;

/** Rendering with made-up data. With -Ddigest.sample.dir=... it also writes both mails, in the branded shell. */
class WeeklyDigestMailTest {
    private static final java.util.function.UnaryOperator<String> URL = p -> "https://portal.example.test" + p;

    @Test
    void salesListsTenStaleDealsThenAMoreLinkAndEscapesNames() {
        List<StaleDeal> stale = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            stale.add(new StaleDeal(i, i == 1 ? "งาน <b>A</b>" : "งานตัวอย่าง " + i, "บริษัท ตัวอย่าง จำกัด", 14 + i));
        }
        var r = WeeklyDigestMail.sales(new SalesWeek("สมหญิง ตัวอย่าง", LocalDate.of(2026, 11, 23), 3, 2,
            new BigDecimal("125000.5"), stale, 14), URL);
        assertThat(r.subject()).isEqualTo("[GL&R Portal] สรุปงานประจำสัปดาห์ 23–29 พ.ย. 2569");
        assertThat(r.html()).contains("อัปเดตขั้นงาน 3 ครั้ง", "฿125,000.50", "และอีก 2 งาน", "งาน &lt;b&gt;A&lt;/b&gt;")
            .doesNotContain("<b>A</b>").doesNotContain("งานตัวอย่าง 12");
        assertThat(r.html()).doesNotContain("ดีล");
        assertThat(WeeklyDigestMail.weekRange(LocalDate.of(2026, 11, 30), LocalDate.of(2026, 12, 6)))
            .isEqualTo("30 พ.ย. 2569 – 6 ธ.ค. 2569");
        assertThat(WeeklyDigestMail.weekRange(LocalDate.of(2026, 12, 28), LocalDate.of(2027, 1, 3)))
            .isEqualTo("28 ธ.ค. 2569 – 3 ม.ค. 2570");
    }

    @Test
    void pendingFallsBackToHomeForLinklessNotesAndCapsTheList() {
        List<PendingNote> notes = new ArrayList<>();
        for (int i = 1; i <= 11; i++) {
            notes.add(new PendingNote("รายการ " + i, i == 1 ? null : "/x/" + i, i));
        }
        var r = WeeklyDigestMail.pending("สมศรี ตัวอย่าง", notes, LocalDate.of(2026, 11, 30), URL);
        assertThat(r.html()).contains("จำนวน 11 รายการ", "https://portal.example.test/\"", "และอีก 1 รายการ")
            .doesNotContain("รายการ 11<");
    }

    @Test
    void samples() throws Exception {
        String dir = System.getProperty("digest.sample.dir");
        if (dir == null) {
            return;
        }
        BrandAssets brand = new BrandAssets();
        NotificationEmailService shell = new NotificationEmailService(Mockito.mock(Mailer.class), brand, "", "",
            "https://portal.example.test");
        var account = WeeklyDigestMail.account(List.of(
            new AccountItem(101, "โครงการบ้านตัวอย่าง A", "บริษัท ตัวอย่าง จำกัด", "สมหญิง ตัวอย่าง",
                new BigDecimal("48500"), 9),
            new AccountItem(102, "โชว์รูมตัวอย่าง B", "ห้างหุ้นส่วนตัวอย่าง", "สมชาย ตัวอย่าง",
                new BigDecimal("212000.75"), 3)), LocalDate.of(2026, 11, 30), shell::portalUrl);
        var sales = WeeklyDigestMail.sales(new SalesWeek("สมหญิง ตัวอย่าง", LocalDate.of(2026, 11, 23), 3, 2,
            new BigDecimal("125000"), List.of(new StaleDeal(201, "โครงการบ้านตัวอย่าง C", "คุณตัวอย่าง", 21),
                new StaleDeal(202, "คอนโดตัวอย่าง D", "บริษัท ตัวอย่าง จำกัด", 15)), 14), shell::portalUrl);
        Files.createDirectories(Path.of(dir));
        Files.writeString(Path.of(dir, "account.html"), shell.renderShell(account.html()));
        Files.writeString(Path.of(dir, "sales.html"), shell.renderShell(sales.html()));
        var pending = WeeklyDigestMail.pending("สมศรี ตัวอย่าง", List.of(
            new PendingNote("มีคำขอจัดส่งสินค้าใหม่รอจัดรอบรถ", "/warehouse/deliveries", 2),
            new PendingNote("ใบแจ้งหนี้รอออกใบกำกับภาษี", null, 5)), LocalDate.of(2026, 11, 30), shell::portalUrl);
        Files.writeString(Path.of(dir, "pending.html"), shell.renderShell(pending.html()));
    }
}
