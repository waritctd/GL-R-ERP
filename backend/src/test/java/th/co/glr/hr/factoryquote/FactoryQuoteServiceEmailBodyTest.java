package th.co.glr.hr.factoryquote;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestSummaryDto;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.ticket.TicketRepository;

/** Unit-tests FactoryQuoteService#emailBody — the RFQ draft-body template — by invoking it directly via
 * reflection (it is private), with every collaborator mocked, no database involved. */
class FactoryQuoteServiceEmailBodyTest {

    private final FactoryQuoteRepository quotes = mock(FactoryQuoteRepository.class);
    private final PricingRequestRepository pricingRequests = mock(PricingRequestRepository.class);
    private final TicketRepository tickets = mock(TicketRepository.class);
    private final FactoryConfigRepository factoryConfigs = mock(FactoryConfigRepository.class);
    private final NotificationRepository notifications = mock(NotificationRepository.class);
    private final FileStorageService fileStorage = mock(FileStorageService.class);
    private final LandedCostCalculator landedCosts = mock(LandedCostCalculator.class);
    private final FactoryQuoteService service = new FactoryQuoteService(
        quotes, pricingRequests, tickets, factoryConfigs, notifications, fileStorage, landedCosts);

    private final UserPrincipal actor = new UserPrincipal(
        7L, "somchai@glr.co.th", "Somchai Rep", "sales", 7L, true, LocalDate.now(), false, null, false);

    @Test
    void fullyPopulatedLine_rendersEveryField() throws Exception {
        PricingRequestSummaryDto summary = summary();
        PricingRequestItemDto item = item(
            "Padana", "Marmo Grigio 60x60", "Grey marble-look porcelain tile", "Grigio", "Matte",
            "60x60 cm", new BigDecimal("120.000"), "PCS", "Rectified edge, please", "PAD-MG6060");
        when(pricingRequests.findIncludedInFactoryEmailAttachmentFiles(summary.id())).thenReturn(List.of());

        String body = invokeEmailBody(summary, "Padana Ceramiche", List.of(item), actor);

        assertThat(body).contains("1. Padana Marmo Grigio 60x60");
        assertThat(body).contains("(Code: PAD-MG6060)");
        assertThat(body).contains("Colour: Grigio");
        assertThat(body).contains("Surface: Matte");
        assertThat(body).contains("Size: 60x60 cm");
        // stripTrailingZeros().toPlainString(): "120.000" reads as "120", not "120.000".
        assertThat(body).contains("Quantity: 120 PCS");
        assertThat(body).doesNotContain("120.000");
        assertThat(body).contains("Special requirement: Rectified edge, please");
        assertThat(body).doesNotContain("null");
    }

    // --- helpers ---

    private String invokeEmailBody(PricingRequestSummaryDto summary, String factoryName,
                                    List<PricingRequestItemDto> items, UserPrincipal actorArg) throws Exception {
        Method method = FactoryQuoteService.class.getDeclaredMethod(
            "emailBody", PricingRequestSummaryDto.class, String.class, List.class, UserPrincipal.class);
        method.setAccessible(true);
        return (String) method.invoke(service, summary, factoryName, items, actorArg);
    }

    private PricingRequestSummaryDto summary() {
        return new PricingRequestSummaryDto(
            1L, "PCR-2026-0099", 50L, "T-99", "Fictional Project", "Fictional Customer", 7L,
            PricingRequestRecipient.BUYER, null, null, PricingRequestStatus.IMPORT_REVIEWING, 7L, "Somchai Rep",
            9L, "Nok Import", LocalDate.now().plusDays(14), new BigDecimal("500.00"), "THB", null, 1, 1, null,
            null, null, null, Instant.now(), Instant.now(), null);
    }

    private PricingRequestItemDto item(String brand, String model, String productDescription, String color,
                                       String texture, String size, BigDecimal qty, String unit,
                                       String specialRequirement, String catalogProductCode) {
        return new PricingRequestItemDto(
            1L, 1L, null, null, null,
            brand, model, productDescription, color, texture, size, null,
            qty, null, unit, null, null, null, null, specialRequirement, 1,
            null, null, null, null, null, null, null, catalogProductCode, null, null, null, null);
    }
}
