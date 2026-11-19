package th.co.glr.hr.deposit;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Drafts saved before the ส่วนลด / size-line release carry the old label and a bare description. For an untouched
 * DRAFT (its items' seq and amount equal its referenced quotation's lines exactly) this rewrites each item that still holds
 * pre-release values (the bare description and no label) from the quotation line. An edited item or draft, a draft whose quotation is not found,
 * every non-DRAFT notice and every draft saved since V228 ran (updated_at past its installed_on) are left alone; a second run rewrites nothing.
 */
// ponytail: delete this class one release after deploy; by then every pre-release DRAFT is refreshed or edited.
@Component
public class DepositNoticeBackfill implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DepositNoticeBackfill.class);

    private final NamedParameterJdbcTemplate jdbc;
    private final DepositNoticeRepository docs;

    public DepositNoticeBackfill(NamedParameterJdbcTemplate jdbc, DepositNoticeRepository docs) {
        this.jdbc = jdbc;
        this.docs = docs;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            int n = refresh();
            if (n > 0) {
                log.info("Deposit-notice backfill rewrote {} draft item(s)", n);
            }
        } catch (RuntimeException e) {
            log.warn("Deposit-notice backfill failed; drafts keep their old labels", e);
        }
    }

    /** @return the number of items rewritten */
    public int refresh() {
        int rewritten = 0;
        List<Map<String, Object>> drafts = jdbc.queryForList(
            "SELECT deposit_notice_id, ticket_id, reference FROM sales.deposit_notice"
                + " WHERE status = 'DRAFT' AND reference IS NOT NULL"
                // one-shot marker: a save bumps updated_at, so only drafts untouched since V228 ran qualify
                + " AND updated_at <= (SELECT installed_on FROM hr.flyway_schema_history WHERE version = '228')",
            Map.of());
        for (Map<String, Object> d : drafts) {
            long id = ((Number) d.get("deposit_notice_id")).longValue();
            Long quotationId = docs.findNewEngineQuotationId(((Number) d.get("ticket_id")).longValue(),
                (String) d.get("reference")).orElse(null);
            if (quotationId == null) {
                continue;
            }
            List<DepositNoticeRepository.QuotationInvoiceLine> lines = docs.findQuotationInvoiceLines(quotationId);
            List<Map<String, Object>> items = jdbc.queryForList(
                "SELECT item_id, seq, description, discount_label, amount FROM sales.deposit_notice_item"
                    + " WHERE deposit_notice_id = :id ORDER BY seq", Map.of("id", id));
            if (!matches(items, lines)) {
                continue;
            }
            for (int i = 0; i < items.size(); i++) {
                Map<String, Object> item = items.get(i);
                var line = lines.get(i);
                // Only a pre-release item is rewritten: the bare description (no size line) and no label (V228 nulled
                // the legacy shapes). Anything else was typed by a user (or already refreshed) and stays.
                boolean preRelease = line.description().split("\n", 2)[0].equals(item.get("description"))
                    && item.get("discount_label") == null;
                boolean changes = !java.util.Objects.equals(item.get("description"), line.description())
                    || !java.util.Objects.equals(item.get("discount_label"), line.discountLabel());
                if (!preRelease || !changes) {
                    continue;
                }
                rewritten += jdbc.update("UPDATE sales.deposit_notice_item SET discount_label = :label,"
                        + " description = :description WHERE item_id = :itemId",
                    new org.springframework.jdbc.core.namedparam.MapSqlParameterSource()
                        .addValue("itemId", item.get("item_id")).addValue("description", line.description())
                        .addValue("label", line.discountLabel()));
            }
        }
        return rewritten;
    }

    private static boolean matches(List<Map<String, Object>> items, List<DepositNoticeRepository.QuotationInvoiceLine> lines) {
        if (items.isEmpty() || items.size() != lines.size()) {
            return false;
        }
        for (int i = 0; i < items.size(); i++) {
            var amount = (java.math.BigDecimal) items.get(i).get("amount");
            if (((Number) items.get(i).get("seq")).intValue() != lines.get(i).seq()
                || amount == null || lines.get(i).amount() == null || amount.compareTo(lines.get(i).amount()) != 0) {
                return false;
            }
        }
        return true;
    }
}
