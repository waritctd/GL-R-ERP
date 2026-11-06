package th.co.glr.hr.pricingrequest;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import th.co.glr.hr.notification.DealNoticeText;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository.PricingCostingWriteItem;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;

/** The detailed "ready for the CEO to set the price" bell/email text. */
public final class CeoReviewNotice {

    private static final Logger log = LoggerFactory.getLogger(CeoReviewNotice.class);
    private static final int MAX_LINES = 10;
    private static final String NO_COST = "ต้นทุน -";

    private CeoReviewNotice() {
    }

    public static String message(String headline, PricingRequestSummaryDto summary,
                                 List<PricingRequestItemDto> items,
                                 LandedCostCalculator.CalculationResult costs) {
        Map<Long, PricingCostingWriteItem> costById = new HashMap<>();
        if (costs != null && costs.items() != null) {
            for (PricingCostingWriteItem c : costs.items()) {
                costById.put(c.pricingRequestItemId(), c);
            }
        }
        List<String> lines = new ArrayList<>();
        lines.add(headline);
        lines.add("คำขอราคา " + summary.requestCode() + " · "
            + DealNoticeText.dealLabel(summary.ticketCode(), summary.customerName()));
        lines.add("");
        lines.add("รายการ (" + items.size() + "):");

        boolean hasStock = false;
        int importLines = 0;
        boolean allCosted = true;
        for (int i = 0; i < items.size(); i++) {
            PricingRequestItemDto item = items.get(i);
            String cost;
            String source = item.stockSource();
            if ("IN_THAILAND".equals(source)) {
                hasStock = true;
                cost = "สต็อกในไทย";
            } else if ("IN_TRANSIT".equals(source)) {
                hasStock = true;
                cost = "สินค้าระหว่างขนส่ง";
            } else {
                importLines++;
                cost = importCost(item, costById.get(item.id()));
                if (NO_COST.equals(cost)) {
                    allCosted = false;
                }
            }
            if (i < MAX_LINES) {
                lines.add("• " + name(item) + " — " + qty(item) + " · " + cost);
            }
        }
        if (items.size() > MAX_LINES) {
            lines.add("และอีก " + (items.size() - MAX_LINES) + " รายการ");
        }
        if (costs != null && costs.total() != null && importLines > 0 && allCosted) {
            lines.add((hasStock ? "ต้นทุนรวมสินค้านำเข้า " : "ต้นทุนรวม ") + money(costs.total()) + " บาท");
        }
        lines.add("");
        lines.add("สิ่งที่ต้องทำต่อ: เปิดคำขอราคา ใส่กำไร % แล้วบันทึกราคาขาย");
        return String.join("\n", lines);
    }

    /** Best-effort preview: a costing failure must never break the notice or the status change. */
    public static LandedCostCalculator.CalculationResult costsOrNull(LandedCostCalculator calc,
                                                                      PricingRequestSummaryDto s) {
        // A SQL error inside calculate() aborts the caller's Postgres transaction even though we catch it
        // here, so the notice INSERT and the commit would fail. Isolate it in a savepoint when one is open.
        Connection conn = null;
        Savepoint sp = null;
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            try {
                for (Object r : TransactionSynchronizationManager.getResourceMap().values()) {
                    if (r instanceof ConnectionHolder h && h.isSynchronizedWithTransaction()) {
                        conn = h.getConnection();
                        break;
                    }
                }
                if (conn != null) {
                    sp = conn.setSavepoint();
                }
            } catch (SQLException | RuntimeException e) {
                log.warn("CEO review notice: could not set a savepoint: {}", e.toString());
                sp = null;
            }
        }
        try {
            LandedCostCalculator.CalculationResult result = calc.calculate(s);
            if (sp != null) {
                conn.releaseSavepoint(sp);
            }
            return result;
        } catch (RuntimeException | SQLException e) {
            log.warn("CEO review notice: cost preview failed for pricing request {}: {}", s.requestCode(), e.toString());
            if (sp != null) {
                try {
                    conn.rollback(sp);
                } catch (SQLException re) {
                    log.warn("CEO review notice: savepoint rollback failed: {}", re.toString());
                }
            }
            return null;
        }
    }

    private static String importCost(PricingRequestItemDto item, PricingCostingWriteItem c) {
        if (c == null || c.uncostableReason() != null || c.landedCostPerUnitThb() == null) {
            return NO_COST;
        }
        BigDecimal sqm = c.sqmPerUnit();
        if (sqm != null && sqm.signum() > 0) {
            return "ต้นทุน " + money(c.landedCostPerUnitThb().divide(sqm, 2, RoundingMode.HALF_UP)) + " บาท/ตร.ม.";
        }
        String unit = item.requestedUnit() == null || item.requestedUnit().isBlank() ? "หน่วย" : item.requestedUnit().trim();
        return "ต้นทุน " + money(c.landedCostPerUnitThb()) + " บาท/" + unit;
    }

    private static String name(PricingRequestItemDto item) {
        List<String> parts = new ArrayList<>();
        for (String p : new String[] {item.brand(), item.model(), item.size()}) {
            if (p != null && !p.isBlank()) {
                parts.add(p.trim());
            }
        }
        if (!parts.isEmpty()) {
            return String.join(" ", parts);
        }
        String d = item.productDescription();
        return d == null || d.isBlank() ? "-" : d.trim();
    }

    private static String qty(PricingRequestItemDto item) {
        if (item.requestedQtySqm() != null && item.requestedQtySqm().signum() > 0) {
            return number(item.requestedQtySqm()) + " ตร.ม.";
        }
        String unit = item.requestedUnit() == null ? "" : item.requestedUnit().trim();
        return (item.requestedQty() == null ? "-" : number(item.requestedQty())) + (unit.isEmpty() ? "" : " " + unit);
    }

    private static String number(BigDecimal v) {
        return fmt("#,##0.######", v);
    }

    private static String money(BigDecimal v) {
        return fmt("#,##0.00", v.setScale(2, RoundingMode.HALF_UP));
    }

    private static String fmt(String pattern, BigDecimal v) {
        DecimalFormat f = new DecimalFormat(pattern, DecimalFormatSymbols.getInstance(Locale.US));
        f.setRoundingMode(RoundingMode.HALF_UP);
        return f.format(v);
    }
}
