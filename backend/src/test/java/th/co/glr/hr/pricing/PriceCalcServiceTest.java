package th.co.glr.hr.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.factory.FactoryConfigDto;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;
import th.co.glr.hr.ticket.TicketSummaryDto;

// 2026-07-16 pricing-integrity audit: missing-FX must fail loudly (finding #1), and a CEO
// manual price override must survive recalculation (finding #2).
class PriceCalcServiceTest {

    private final FxRateRepository fxRates = mock(FxRateRepository.class);
    private final PriceCalcConfigRepository priceConfigs = mock(PriceCalcConfigRepository.class);
    private final FactoryConfigRepository factoryConfigs = mock(FactoryConfigRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final PriceCalcService service =
        new PriceCalcService(fxRates, priceConfigs, factoryConfigs, tickets);

    @Test
    void calculateForTicket_missingFxRateThrows422WithCurrencyInMessage() {
        stubConfigAndFactory();
        TicketItemDto item = item(1L, "USD", new BigDecimal("100"), null);
        stubTicket(10L, List.of(item));
        when(fxRates.findByCurrency("USD")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.calculateForTicket(10L))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
                assertThat(e.getMessage()).contains("USD");
            });
        verify(tickets, never()).updateItemCalcResults(anyLong(), any(), any(), anyInt(), any());
    }

    @Test
    void calculateForTicket_thbCurrencySkipsFxLookupEntirely() {
        stubConfigAndFactory();
        TicketItemDto item = item(1L, "THB", new BigDecimal("100"), null);
        stubTicket(10L, List.of(item));

        service.calculateForTicket(10L);

        verify(fxRates, never()).findByCurrency(anyString());
        verify(tickets).updateItemCalcResults(eq(1L), any(), any(), eq(1), any());
    }

    @Test
    void calculateForTicket_nullCurrencyTreatedAsThbSkipsFxLookup() {
        stubConfigAndFactory();
        TicketItemDto item = item(1L, null, new BigDecimal("100"), null);
        stubTicket(10L, List.of(item));

        service.calculateForTicket(10L);

        verify(fxRates, never()).findByCurrency(anyString());
    }

    @Test
    void calculateForTicket_resolvesFxRateWhenPresentForNonThbCurrency() {
        stubConfigAndFactory();
        TicketItemDto item = item(1L, "USD", new BigDecimal("100"), null);
        stubTicket(10L, List.of(item));
        when(fxRates.findByCurrency("USD"))
            .thenReturn(Optional.of(new FxRateDto(1L, "USD", new BigDecimal("35"),
                LocalDate.now(), Instant.now(), "MANUAL", null)));

        service.calculateForTicket(10L);

        verify(tickets).updateItemCalcResults(eq(1L), any(), any(), eq(1), any());
    }

    @Test
    void calculateForTicket_manualPriceOverridePreservedAsProposedPrice() {
        stubConfigAndFactory();
        BigDecimal manualPrice = new BigDecimal("999.00");
        TicketItemDto item = item(1L, "THB", new BigDecimal("100"), manualPrice);
        stubTicket(10L, List.of(item));

        service.calculateForTicket(10L);

        // calced_cost/calced_price/config_version still reflect the fresh calculation,
        // but the proposed_price param passed through stays pinned to the manual override.
        verify(tickets).updateItemCalcResults(eq(1L), any(BigDecimal.class),
            any(BigDecimal.class), eq(1), eq(manualPrice));
    }

    @Test
    void calculateForTicket_noManualPriceUsesCalculatedPriceAsProposedPrice() {
        stubConfigAndFactory();
        TicketItemDto item = item(1L, "THB", new BigDecimal("100"), null);
        stubTicket(10L, List.of(item));

        service.calculateForTicket(10L);

        ArgumentCaptor<BigDecimal> calcedPriceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        ArgumentCaptor<BigDecimal> proposedPriceCaptor = ArgumentCaptor.forClass(BigDecimal.class);
        verify(tickets).updateItemCalcResults(eq(1L), any(BigDecimal.class),
            calcedPriceCaptor.capture(), eq(1), proposedPriceCaptor.capture());

        assertThat(proposedPriceCaptor.getValue()).isEqualByComparingTo(calcedPriceCaptor.getValue());
    }

    // ── fixtures ──────────────────────────────────────────────────────────

    private void stubConfigAndFactory() {
        when(factoryConfigs.findByName(any()))
            .thenReturn(Optional.of(new FactoryConfigDto(1L, "Cotto", null, "USD", "piece", "Thailand")));
        when(priceConfigs.findCurrentByCountry("Thailand"))
            .thenReturn(Optional.of(new PriceCalcConfigDto(
                1L, 1, "Thailand",
                new BigDecimal("10"), new BigDecimal("5"),
                new BigDecimal("2"), new BigDecimal("3"),
                new BigDecimal("0.1"), new BigDecimal("0.2"),
                true, LocalDate.now(), Instant.now())));
    }

    private void stubTicket(long ticketId, List<TicketItemDto> items) {
        TicketSummaryDto summary = new TicketSummaryDto(
            ticketId, "PR-2026-0001", "PRICE_REQUEST", "Test ticket", TicketStatus.PRICE_PROPOSED,
            "NORMAL", 1L, "Sales User", null, null, "Test Customer", null, null, null, null, null, null,
            Instant.now(), Instant.now(), null, items.size(), false, null, null,
            "QUOTE_DESIGN_SIDE", null, null, Instant.now(),
            "ACTIVE", "UNKNOWN", "REQUIRED", null, "DESIGNER_LED");
        TicketDto ticket = new TicketDto(summary, items, List.of(), null, List.of());
        when(tickets.findById(ticketId)).thenReturn(Optional.of(ticket));
    }

    private TicketItemDto item(long id, String rawCurrency, BigDecimal rawPrice, BigDecimal manualPrice) {
        return new TicketItemDto(
            id, 10L, "Cotto", "Marble", null, null, null, "Cotto",
            new BigDecimal("10"), new BigDecimal("20"),
            rawPrice, rawCurrency, "piece",
            null, null, "THB", 0,
            null, null, null, "PIECE",
            manualPrice, manualPrice != null ? "CEO discount" : null);
    }

    private static long anyLong() { return org.mockito.ArgumentMatchers.anyLong(); }
    private static int anyInt() { return org.mockito.ArgumentMatchers.anyInt(); }
}
