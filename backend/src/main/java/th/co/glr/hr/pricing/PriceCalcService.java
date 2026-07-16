package th.co.glr.hr.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketRepository;

@Service
public class PriceCalcService {
    private static final BigDecimal ONE = BigDecimal.ONE;

    private final FxRateRepository fxRates;
    private final PriceCalcConfigRepository priceConfigs;
    private final FactoryConfigRepository factoryConfigs;
    private final TicketRepository tickets;

    public PriceCalcService(
        FxRateRepository fxRates,
        PriceCalcConfigRepository priceConfigs,
        FactoryConfigRepository factoryConfigs,
        TicketRepository tickets
    ) {
        this.fxRates       = fxRates;
        this.priceConfigs  = priceConfigs;
        this.factoryConfigs = factoryConfigs;
        this.tickets       = tickets;
    }

    @Transactional
    public TicketDto calculateForTicket(long ticketId) {
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));

        List<TicketItemDto> items = ticket.items();
        for (TicketItemDto item : items) {
            if (item.rawPrice() == null) continue;

            String country = factoryConfigs.findByName(item.factory())
                .map(FactoryConfigDto::country)
                .orElse("Thailand");

            PriceCalcConfigDto config = priceConfigs.findCurrentByCountry(country)
                .orElseGet(() -> priceConfigs.findCurrentByCountry("Thailand")
                    .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "ไม่พบ price config สำหรับประเทศ: " + country)));

            BigDecimal fxRate = resolveFxRate(item.rawCurrency());

            // sqmPerPiece: qtySqm / qty, fallback to 1
            BigDecimal sqmPerPiece = ONE;
            if (item.qtySqm() != null && item.qty() != null
                    && item.qty().compareTo(BigDecimal.ZERO) > 0) {
                sqmPerPiece = item.qtySqm().divide(item.qty(), 8, RoundingMode.HALF_UP);
            }

            // Normalize goodsCost to THB per sqm
            BigDecimal goodsCostPerSqm;
            if ("sqm".equals(item.rawUnit())) {
                goodsCostPerSqm = item.rawPrice().multiply(fxRate);
            } else {
                // piece → divide by sqmPerPiece to get per-sqm
                if (sqmPerPiece.compareTo(BigDecimal.ZERO) > 0) {
                    goodsCostPerSqm = item.rawPrice().multiply(fxRate)
                        .divide(sqmPerPiece, 8, RoundingMode.HALF_UP);
                } else {
                    goodsCostPerSqm = item.rawPrice().multiply(fxRate);
                }
            }

            // CIF(THB/sqm) = goodsCost + freight + insurance
            BigDecimal cifPerSqm = goodsCostPerSqm
                .add(config.freightPerSqm())
                .add(config.insurancePerSqm());

            // importDuty = CIF × taxRate
            BigDecimal importDutyPerSqm = cifPerSqm.multiply(config.importDutyPct());

            // landedCost = CIF + duty + inland
            BigDecimal landedCostPerSqm = cifPerSqm.add(importDutyPerSqm)
                .add(config.inlandFactoryToPortPerSqm())
                .add(config.inlandPortToWarehousePerSqm());

            // sellPrice = landedCost × (1 + margin)
            BigDecimal sellPricePerSqm = landedCostPerSqm.multiply(ONE.add(config.marginPct()));

            // Convert back to per piece
            BigDecimal calcedCost  = landedCostPerSqm.multiply(sqmPerPiece).setScale(4, RoundingMode.HALF_UP);
            BigDecimal calcedPrice = sellPricePerSqm.multiply(sqmPerPiece).setScale(2, RoundingMode.HALF_UP);

            // A CEO manual price override must survive recalculation: calced_cost/calced_price/
            // calc_config_version always reflect the fresh calculation, but proposed_price stays
            // pinned to the manual override rather than being clobbered by calcedPrice (otherwise
            // approve() would silently approve the calculated price, not the override).
            BigDecimal proposedPrice = item.manualPrice() != null ? item.manualPrice() : calcedPrice;
            tickets.updateItemCalcResults(item.id(), calcedCost, calcedPrice, config.version(), proposedPrice);
        }

        return tickets.findById(ticketId).orElseThrow();
    }

    // THB (and a null/blank currency, treated as THB) never needs a lookup — rate is always 1.
    // Any other currency MUST resolve against sales.fx_rates; silently falling back to 1:1 THB
    // was a pricing-integrity bug (an unset FX rate made goods look free), so this now fails
    // loudly instead (2026-07-16 sales-ticket-flow audit).
    private BigDecimal resolveFxRate(String rawCurrency) {
        String currency = rawCurrency != null ? rawCurrency : "THB";
        if ("THB".equals(currency)) {
            return ONE;
        }
        return fxRates.findByCurrency(currency)
            .map(FxRateDto::rateToThb)
            .orElseThrow(() -> new ApiException(HttpStatus.UNPROCESSABLE_ENTITY,
                "ไม่พบอัตราแลกเปลี่ยนสำหรับสกุลเงิน " + currency + " — กรุณาตั้งค่าใน CEO Settings"));
    }

    public List<PriceBreakdownItemDto> calculateBreakdown(long ticketId) {
        TicketDto ticket = tickets.findById(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "Ticket not found"));

        List<PriceBreakdownItemDto> result = new ArrayList<>();
        for (TicketItemDto item : ticket.items()) {
            if (item.rawPrice() == null) continue;

            String country = factoryConfigs.findByName(item.factory())
                .map(FactoryConfigDto::country)
                .orElse("Thailand");

            PriceCalcConfigDto config = priceConfigs.findCurrentByCountry(country)
                .orElseGet(() -> priceConfigs.findCurrentByCountry("Thailand").orElseThrow());

            BigDecimal fxRate = resolveFxRate(item.rawCurrency());

            BigDecimal sqmPerPiece = ONE;
            if (item.qtySqm() != null && item.qty() != null
                    && item.qty().compareTo(BigDecimal.ZERO) > 0) {
                sqmPerPiece = item.qtySqm().divide(item.qty(), 8, RoundingMode.HALF_UP);
            }

            BigDecimal goodsCostPerSqm;
            if ("sqm".equals(item.rawUnit())) {
                goodsCostPerSqm = item.rawPrice().multiply(fxRate);
            } else {
                goodsCostPerSqm = sqmPerPiece.compareTo(BigDecimal.ZERO) > 0
                    ? item.rawPrice().multiply(fxRate).divide(sqmPerPiece, 8, RoundingMode.HALF_UP)
                    : item.rawPrice().multiply(fxRate);
            }

            BigDecimal cifPerSqm = goodsCostPerSqm
                .add(config.freightPerSqm())
                .add(config.insurancePerSqm());
            BigDecimal importDutyPerSqm = cifPerSqm.multiply(config.importDutyPct());
            BigDecimal inlandPerSqm = config.inlandFactoryToPortPerSqm()
                .add(config.inlandPortToWarehousePerSqm());
            BigDecimal landedCostPerSqm = cifPerSqm.add(importDutyPerSqm).add(inlandPerSqm);
            BigDecimal sellPricePerSqm = landedCostPerSqm.multiply(ONE.add(config.marginPct()));

            result.add(new PriceBreakdownItemDto(
                item.id(), item.brand(), item.model(), item.factory(),
                item.rawCurrency(), fxRate, sqmPerPiece,
                goodsCostPerSqm.setScale(4, RoundingMode.HALF_UP),
                config.freightPerSqm(), config.insurancePerSqm(),
                cifPerSqm.setScale(4, RoundingMode.HALF_UP),
                importDutyPerSqm.setScale(4, RoundingMode.HALF_UP),
                inlandPerSqm.setScale(4, RoundingMode.HALF_UP),
                landedCostPerSqm.setScale(4, RoundingMode.HALF_UP),
                config.marginPct(),
                sellPricePerSqm.setScale(4, RoundingMode.HALF_UP),
                landedCostPerSqm.multiply(sqmPerPiece).setScale(4, RoundingMode.HALF_UP),
                sellPricePerSqm.multiply(sqmPerPiece).setScale(2, RoundingMode.HALF_UP),
                config.version()
            ));
        }
        return result;
    }
}
