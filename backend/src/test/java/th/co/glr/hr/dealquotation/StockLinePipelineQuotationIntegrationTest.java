package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestStatus;
import th.co.glr.hr.pricingstock.AbstractStockLineIntegrationTest;
import th.co.glr.hr.ticket.QuotationRenderer;

/**
 * Slice 1, stock lines: the pipeline quotation ({@link DealQuotationService#createFromPricingRequest},
 * the engine the new-form pricing chain produces quotations with) copies each pricing-request
 * item's {@code stock_source} onto {@code sales.quotation_item}, and a stock line comes out with a
 * real price. Lives in the {@code dealquotation} package only because
 * {@code wirePricingRequestDependencies} is package-private.
 *
 * <p>Fixture note: the APPROVED stock decision is seeded through the repositories
 * ({@link #seedStockOnlyDecision}) and then marked approved with a raw UPDATE, so this test fails on
 * ITS OWN assertion and not inside the unbuilt {@code startReview}/{@code approve} paths (those
 * have their own tests in {@code StockLineCeoDecisionIntegrationTest}).
 *
 * <p>Deliberately NOT covered: the LEGACY quotation engine ({@code CustomerQuotationService#create},
 * decisions with no price mode) - see the report; stock lines on a legacy-mode decision are an open
 * design question, not something to silently pin.
 *
 * <p>[PIN] = passes before the feature exists on purpose.
 */
class StockLinePipelineQuotationIntegrationTest extends AbstractStockLineIntegrationTest {

    private DealQuotationService quotationService;

    @BeforeEach
    void wireQuotationEngine() {
        Mailer noMail = new Mailer() {
            @Override
            public void send(String to, String subject, String body) {}

            @Override
            public void sendHtml(String to, String subject, String htmlBody, String textBody,
                                 List<Mailer.InlineImage> inlineImages) {}

            @Override
            public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {}

            @Override
            public void sendWithAttachments(String to, String subject, String body, List<Mailer.Attachment> attachments) {}
        };
        DealQuotationRepository quotationRepository = new DealQuotationRepository(jdbc, new CatalogRepository(jdbc));
        quotationService = new DealQuotationService(quotationRepository, tickets, new CustomerRepository(jdbc),
            new ContactRepository(jdbc), notifications,
            new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc),
            new EmployeeSignatureRepository(jdbc), new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        quotationService.wirePricingRequestDependencies(pricingRequests, decisionRepository,
            new th.co.glr.hr.customerquotation.CustomerQuotationRepository(jdbc));
    }

    @Test
    void pipelineQuotation_copiesStockSourceOntoEachQuotationItem_andThePricedStockLinesHaveAPositiveUnitPrice() {
        long id = persistDraft(stockLine("Stock TH", IN_THAILAND, 10), stockLine("Stock TR", IN_TRANSIT, 4));
        seedEta(id, "Stock TR", eta());
        long decisionId = seedStockOnlyDecision(id);
        seedPriceMode(decisionId, "NET");
        seedListPriceAndNet(decisionId, itemId(id, "Stock TH"), new BigDecimal("1200.00"), new BigDecimal("1200.00"));
        seedListPriceAndNet(decisionId, itemId(id, "Stock TR"), new BigDecimal("700.00"), new BigDecimal("700.00"));
        seedApproved(decisionId, id);

        DealQuotationDto quotation = quotationService.createFromPricingRequest(id, salesActor);

        assertThat(quotation.items()).hasSize(2);
        assertThat(storedQuotationSource(quotation.id(), itemId(id, "Stock TH"))).isEqualTo(IN_THAILAND);
        assertThat(storedQuotationSource(quotation.id(), itemId(id, "Stock TR"))).isEqualTo(IN_TRANSIT);
        for (DealQuotationItemDto item : quotation.items()) {
            assertThat(item.unitPrice()).as("a stock line prints a real price").isGreaterThan(BigDecimal.ZERO);
            assertThat(item.netUnitPrice()).isGreaterThan(BigDecimal.ZERO);
        }
        DealQuotationItemDto th = quotation.items().stream()
            .filter(i -> "Stock TH".equals(i.model())).findFirst().orElseThrow();
        assertThat(th.unitPrice()).isEqualByComparingTo("1200.00");
    }

    @Test
    void pipelineQuotation_ofAnImportOnlyRequest_leavesStockSourceNull_PIN() {
        long id = persistSubmitAndPickUp(importLine("Imp A", catalogProductFactoryA, FACTORY_A, 10));
        quoteEveryImportLineReady(id);
        assertThat(status(id)).isEqualTo(PricingRequestStatus.READY_FOR_CEO_REVIEW);
        PricingDecisionDto started = decisionService.startReview(id,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        PricingDecisionDto withMode = decisionService.update(started.id(),
            new UpdatePricingDecisionRequest(null, "NET", List.of()), ceoActor);
        decisionService.approve(withMode.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);

        DealQuotationDto quotation = quotationService.createFromPricingRequest(id, salesActor);

        assertThat(quotation.items()).hasSize(1);
        assertThat(storedQuotationSource(quotation.id(), itemId(id, "Imp A")))
            .as("an import line must never be stamped as stock").isNull();
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    /** Raw: an APPROVED decision as approve() would leave it for stock lines (approved = minimum = net). */
    private void seedApproved(long decisionId, long pricingRequestId) {
        jdbc.update("""
            UPDATE sales.pricing_decision_item
               SET approved_selling_price_per_requested_unit = net_unit_price,
                   minimum_selling_price_per_requested_unit = net_unit_price,
                   discount_pct = 0
             WHERE pricing_decision_id = :d
            """, Map.of("d", decisionId));
        jdbc.update("""
            UPDATE sales.pricing_decision
               SET status = 'APPROVED', approved_by = :ceo, approved_at = now()
             WHERE pricing_decision_id = :d
            """, Map.of("d", decisionId, "ceo", ceoUserId));
        forceStatus(pricingRequestId, PricingRequestStatus.APPROVED_FOR_QUOTATION);
    }

    private String storedQuotationSource(long quotationId, long pricingRequestItemId) {
        return jdbc.queryForObject("""
            SELECT stock_source FROM sales.quotation_item
             WHERE quotation_id = :q AND pricing_request_item_id = :i
            """, Map.of("q", quotationId, "i", pricingRequestItemId), String.class);
    }
}
