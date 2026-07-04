package th.co.glr.hr.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
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

            BigDecimal fxRate = fxRates.findByCurrency(
                    item.rawCurrency() != null ? item.rawCurrency() : "THB")
                .map(FxRateDto::rateToThb)
                .orElse(ONE);

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

            tickets.updateItemCalcResults(item.id(), calcedCost, calcedPrice, config.version(), calcedPrice);
        }

        return tickets.findById(ticketId).orElseThrow();
    }
}
