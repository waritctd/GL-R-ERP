package th.co.glr.hr.digest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.function.UnaryOperator;
import th.co.glr.hr.digest.WeeklyDigestRepository.AccountItem;
import th.co.glr.hr.digest.WeeklyDigestRepository.StaleDeal;

/** Pure rendering of the two Monday digests: formal, system-voiced (never in a person's voice), no ranking. Links are portal paths run through
 * {@code url}. The returned HTML is the content block only; the branded shell is added when it is sent. */
final class WeeklyDigestMail {
    static final int MAX_STALE_LISTED = 10;
    private static final String[] MONTHS = {"ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.", "ก.ค.", "ส.ค.", "ก.ย.",
        "ต.ค.", "พ.ย.", "ธ.ค."};
    private static final String BRAND = "#4f46e5";
    private static final String CLOSING = "จึงเรียนมาเพื่อโปรดทราบ";

    record Rendered(String subject, String html, String text) {}

    record SalesWeek(String repName, LocalDate monday, int stageMoves, int quotations, BigDecimal received,
                     List<StaleDeal> stale, int quietDays) {}

    private WeeklyDigestMail() {}

    static String thaiDate(LocalDate d) {
        return d.getDayOfMonth() + " " + MONTHS[d.getMonthValue() - 1] + " " + (d.getYear() + 543);
    }

    static String money(BigDecimal v) {
        return "฿" + String.format(java.util.Locale.US, "%,.2f", v.setScale(2, java.math.RoundingMode.HALF_UP));
    }

    static Rendered account(List<AccountItem> items, LocalDate today, UnaryOperator<String> url) {
        BigDecimal total = items.stream().map(AccountItem::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String intro = "ระบบ GL&R Portal ขอแจ้งรายการงานที่รอฝ่ายบัญชียืนยันการรับชำระเงิน จำนวน " + items.size()
            + " รายการ ยอดคงค้างรวม " + money(total) + " สามารถตรวจสอบและดำเนินการได้จากลิงก์ด้านล่าง";
        StringBuilder h = new StringBuilder("<p style=\"margin:0 0 8px 0;\">เรียน ฝ่ายบัญชี</p>")
            .append("<p style=\"margin:0 0 16px 0;\">").append(esc(intro)).append("</p>");
        StringBuilder t = new StringBuilder("เรียน ฝ่ายบัญชี\n\n").append(intro).append("\n\n");
        for (AccountItem i : items) {
            String name = name(i.title(), i.customer());
            String link = url.apply("/finance/deals/" + i.ticketId());
            String meta = "ผู้ดูแล " + i.repName() + " · ยอดคงค้าง " + money(i.amount()) + " · รอมาแล้ว "
                + i.daysWaiting() + " วัน";
            h.append(row(name, meta, "เปิดดู", link));
            t.append("- ").append(name).append(" (").append(meta).append(")\n  ").append(link).append('\n');
        }
        h.append("<p style=\"margin:16px 0 0 0;\">").append(CLOSING).append("</p>");
        t.append("\n").append(CLOSING);
        return new Rendered("[GL&R Portal] สรุปงานที่รอฝ่ายบัญชียืนยันการรับชำระเงิน ประจำวันที่ " + thaiDate(today),
            h.toString(), t.toString());
    }

    static Rendered sales(SalesWeek w, UnaryOperator<String> url) {
        LocalDate sunday = w.monday().plusDays(6);
        String range = w.monday().getDayOfMonth() + "–" + thaiDate(sunday);
        List<String> done = new java.util.ArrayList<>();
        if (w.stageMoves() > 0) done.add("อัปเดตขั้นงาน " + w.stageMoves() + " ครั้ง");
        if (w.quotations() > 0) done.add("ส่งใบเสนอราคา " + w.quotations() + " ฉบับ");
        if (w.received().signum() > 0) done.add("รับชำระเงิน " + money(w.received()));
        String lead = done.isEmpty()
            ? "ระบบ GL&R Portal ขอสรุปงานของท่านประจำสัปดาห์ (" + range + ")"
            : "ระบบ GL&R Portal ขอสรุปความคืบหน้างานของท่านในสัปดาห์ที่ผ่านมา (" + range + "): "
                + String.join(" · ", done);
        StringBuilder h = new StringBuilder("<p style=\"margin:0 0 8px 0;\">เรียน คุณ" + esc(w.repName()) + "</p>")
            .append("<p style=\"margin:0 0 16px 0;\">").append(esc(lead)).append("</p>");
        StringBuilder t = new StringBuilder("เรียน คุณ" + w.repName() + "\n\n" + lead + "\n");
        if (!w.stale().isEmpty()) {
            String head = "งานที่ไม่มีการอัปเดตเกิน " + w.quietDays() + " วัน";
            String hint = "โปรดอัปเดตสถานะในระบบ เพื่อให้ทีมเห็นความคืบหน้าล่าสุด";
            h.append("<p style=\"margin:8px 0 4px 0;font-weight:600;\">").append(esc(head)).append("</p>")
                .append("<p style=\"margin:0 0 8px 0;color:#6b7280;\">").append(esc(hint)).append("</p>");
            t.append("\n").append(head).append(" — ").append(hint).append("\n");
            for (StaleDeal s : w.stale().stream().limit(MAX_STALE_LISTED).toList()) {
                String name = name(s.title(), s.customer());
                String link = url.apply("/tickets/" + s.ticketId());
                String meta = "ไม่มีการอัปเดต " + s.quietDays() + " วัน";
                h.append(row(name, meta, "อัปเดต", link));
                t.append("- ").append(name).append(" (").append(meta).append(")\n  ").append(link).append('\n');
            }
            int more = w.stale().size() - MAX_STALE_LISTED;
            if (more > 0) {
                String link = url.apply("/tickets");
                h.append("<p style=\"margin:12px 0 0 0;\"><a href=\"").append(esc(link))
                    .append("\" style=\"color:").append(BRAND).append(";\">และอีก ").append(more)
                    .append(" งาน ดูทั้งหมด</a></p>");
                t.append("และอีก ").append(more).append(" งาน ดูทั้งหมด: ").append(link).append('\n');
            }
        }
        h.append("<p style=\"margin:16px 0 0 0;\">").append(CLOSING).append("</p>");
        t.append("\n").append(CLOSING);
        return new Rendered("[GL&R Portal] สรุปงานประจำสัปดาห์ " + range, h.toString(), t.toString());
    }

    private static String name(String title, String customer) {
        boolean sameOrBlank = customer == null || customer.isBlank() || customer.equals(title);
        return sameOrBlank ? title : title + " · " + customer;
    }

    private static String row(String title, String meta, String cta, String link) {
        return "<table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" "
            + "style=\"border-top:1px solid #e5e7eb;\"><tr><td style=\"padding:10px 0;\">"
            + "<div style=\"font-weight:600;\">" + esc(title) + "</div>"
            + "<div style=\"font-size:13px;color:#6b7280;\">" + esc(meta) + "</div></td>"
            + "<td align=\"right\" style=\"padding:10px 0 10px 12px;white-space:nowrap;\">"
            + "<a href=\"" + esc(link) + "\" style=\"color:" + BRAND + ";font-weight:600;text-decoration:none;\">"
            + cta + " &rsaquo;</a></td></tr></table>";
    }

    private static String esc(String v) {
        return v == null ? "" : v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;");
    }
}
