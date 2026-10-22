package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.HttpStatus.CONFLICT;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationAttachmentDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteDtos.FactoryQuoteDto;
import th.co.glr.hr.factoryquote.FactoryQuoteRepository;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteItemRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteRequests.ReceiveFactoryQuoteRequest;
import th.co.glr.hr.factoryquote.FactoryQuoteService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricing.FxRateRepository;
import th.co.glr.hr.pricing.PricingFormulaConfigRepository;
import th.co.glr.hr.pricingcosting.LandedCostCalculator;
import th.co.glr.hr.pricingcosting.PricingCostingRepository;
import th.co.glr.hr.pricingcosting.PricingFormulaEngine;
import th.co.glr.hr.pricingdecision.PricingDecisionDtos.PricingDecisionDto;
import th.co.glr.hr.pricingdecision.PricingDecisionRepository;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.ApprovePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.StartPricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionItemRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionRequests.UpdatePricingDecisionRequest;
import th.co.glr.hr.pricingdecision.PricingDecisionService;
import th.co.glr.hr.pricingrequest.PricingRequestRecipient;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestRequests;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.pricingrequest.QuantityType;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * เอกสารแนบ on a quotation written from a คำขอราคา ({@code origin = 'PRICING_REQUEST'}), where
 * DealQuotationService's view and edit rules are NARROWER than for a direct quotation: sales (owner),
 * sales_manager and ceo only, and the {@code can_create_quotation} grant opens nothing. The
 * attachment routes must apply that origin's rule, not the direct one.
 *
 * <p>The quotation is a real one: pricing request, factory quotes, the CEO's decision, then
 * {@code DealQuotationService#createFromPricingRequest}, the same chain
 * {@link DealQuotationPricingRequestApprovalIntegrationTest} drives. The factory-quote file in
 * the last test is a real one too, uploaded by Import through {@link FactoryQuoteService}.
 */
class DealQuotationAttachmentPricingRequestOriginIntegrationTest extends AbstractDealQuotationAttachmentIntegrationTest {

    private static final byte[] FACTORY_COST = "factory price list, cost per piece".getBytes(StandardCharsets.UTF_8);

    private PricingRequestService pricingRequestService;
    private FactoryQuoteService factoryQuoteService;
    private PricingDecisionService decisionService;
    private long ticketId;
    private long catalogProductIdFactoryA;
    private long catalogProductIdFactoryB;

    @BeforeEach
    void wirePricingChain() {
        TicketRepository tickets = new TicketRepository(jdbc);
        PricingRequestRepository pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();

        pricingRequestService = new PricingRequestService(pricingRequests, tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        FactoryQuoteRepository factoryQuotes = new FactoryQuoteRepository(jdbc);
        FxRateRepository fxRates = new FxRateRepository(jdbc);
        PricingFormulaEngine formulaEngine = new PricingFormulaEngine(new PricingFormulaConfigRepository(jdbc));
        LandedCostCalculator landedCostCalculator = new LandedCostCalculator(factoryQuotes, pricingRequests,
            fxRates, new FactoryConfigRepository(jdbc), new CatalogRepository(jdbc), formulaEngine);
        factoryQuoteService = new FactoryQuoteService(factoryQuotes, pricingRequests, tickets,
            new FactoryConfigRepository(jdbc), notifications, fileStorage, landedCostCalculator);
        decisionService = new PricingDecisionService(new PricingDecisionRepository(jdbc), pricingRequests,
            new PricingCostingRepository(jdbc), tickets, fxRates, notifications, landedCostCalculator, formulaEngine);
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, new EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        catalogProductIdFactoryA = insertCatalogProduct("Factory A-ATT", "IT", "TEST-A-ATT-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        catalogProductIdFactoryB = insertCatalogProduct("Factory B-ATT", "IT", "TEST-B-ATT-001",
            new BigDecimal("100.00"), "THB", "per_piece");
        CustomerDto customer = customers.create(
            "บริษัท เอกสารแนบ คำขอราคา จำกัด", "0100000000388", "388 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0388");
        ProjectDto project = new ProjectRepository(jdbc).create(customer.id(), "โครงการ เอกสารแนบ คำขอราคา");
        long contactId = new ContactRepository(jdbc).create(customer.id(), "สมหญิง", "ทดสอบ",
            "ผู้จัดการฝ่ายจัดซื้อ", "contact-att@example.com", "081-000-0388").id();
        ticketId = ticketService.create(
            new CreateTicketRequest("ดีล เอกสารแนบ คำขอราคา", "NORMAL", customer.name(), customer.id(), project.id(),
                contactId, null, null, List.of(ticketItem("SCG", "Tile A-ATT", "Factory A-ATT"),
                    ticketItem("Cotto", "Tile B-ATT", "Factory B-ATT"))),
            salesActor).summary().id();
    }

    @Test
    void onAQuotationWrittenFromAPricingRequest_theGrantOpensNothing_andOnlyItsThreeRolesReadTheFiles() {
        DealQuotationDto quotation = quotationFromThePricingRequest(pricingRequestReadyForTheCeo().pricingRequestId());
        long file = seed(quotation.id(), FILE, VENDOR_QUOTE, salesActor);
        long rows = fileRows();

        // A grant-holding qc and a grant-holding account, plain account and import, another rep.
        for (UserPrincipal outsider : List.of(grantedQcActor, accountWithGrantActor, accountActor, importActor,
                otherSalesActor)) {
            String who = outsider.role() + " " + outsider.id();
            // Control: the rule for this origin refuses this caller the quotation itself.
            refused(who + " opening the quotation", FORBIDDEN, () -> quotationService.get(quotation.id(), outsider));

            refused(who + " listing the files", FORBIDDEN, () -> attachmentService.list(quotation.id(), outsider));
            refused(who + " downloading a file", FORBIDDEN, () -> attachmentService.download(file, outsider));
            refused(who + " uploading", FORBIDDEN, () -> attachmentService.upload(quotation.id(), pdf("x.pdf"), outsider));
            refused(who + " deleting a file", FORBIDDEN, () -> attachmentService.delete(file, outsider));
        }
        assertThat(fileRows()).as("nothing stored, nothing removed").isEqualTo(rows);
        assertThat(storedBytes(file)).isEqualTo(VENDOR_QUOTE);

        // Control: it is this ORIGIN that is closed to the grant. The same qc reads a direct quotation's files.
        DealQuotationDto direct = draftOwnedBy(salesActor);
        long directFile = seed(direct.id(), FILE, VENDOR_QUOTE, salesActor);
        assertThat(attachmentService.list(direct.id(), grantedQcActor))
            .extracting(DealQuotationAttachmentDto::id).containsExactly(directFile);

        // Controls: the three roles of this origin read; the two editors attach; the CEO does not.
        for (UserPrincipal reader : List.of(salesActor, salesManagerActor, ceoActor)) {
            assertThat(attachmentService.list(quotation.id(), reader)).as(reader.role())
                .extracting(DealQuotationAttachmentDto::id).containsExactly(file);
            assertThat(served(file, reader)).isEqualTo(VENDOR_QUOTE);
        }
        assertThat(attachmentService.upload(quotation.id(), pdf("owner.pdf"), salesActor)).isNotNull();
        assertThat(attachmentService.upload(quotation.id(), pdf("manager.pdf"), salesManagerActor)).isNotNull();
        refused("ceo uploading", FORBIDDEN, () -> attachmentService.upload(quotation.id(), pdf("x.pdf"), ceoActor));
        attachmentService.delete(file, salesActor);
        assertThat(rowExists(file)).isFalse();
    }

    @Test
    void onceIssued_theQuotationTakesNoMoreFiles_andGivesNoneUp() {
        DealQuotationDto drafted = quotationFromThePricingRequest(pricingRequestReadyForTheCeo().pricingRequestId());
        long file = seed(drafted.id(), FILE, VENDOR_QUOTE, salesActor);
        quotationService.update(drafted.id(), withValidityDays(drafted, 30), salesActor);
        quotationService.submit(drafted.id(), salesActor);
        DealQuotationDto issued = quotationService.approve(drafted.id(), new ApproveRequest(null), ceoActor);
        assertThat(issued.docStatus()).as("the premise: this origin is ISSUED on approval").isEqualTo(QuotationStatus.ISSUED);
        long rows = fileRows();

        for (UserPrincipal editor : List.of(salesActor, salesManagerActor)) {
            refused(editor.role() + " uploading to an ISSUED quotation", CONFLICT,
                () -> attachmentService.upload(issued.id(), pdf("late.pdf"), editor));
            refused(editor.role() + " deleting from an ISSUED quotation", CONFLICT,
                () -> attachmentService.delete(file, editor));
        }
        assertThat(fileRows()).as("nothing stored, nothing removed").isEqualTo(rows);

        // Control: the issued document still shows what it was approved with.
        assertThat(attachmentService.list(issued.id(), ceoActor))
            .extracting(DealQuotationAttachmentDto::id).containsExactly(file);
    }

    /**
     * The file Import attached to a factory quote is the factory's own price: the rep and the
     * sales manager have no right to it anywhere in the application. It lives in the same table as
     * a quotation's files, so an id is all that separates the two.
     */
    @Test
    void theFactoryQuotesOwnFile_isNotReachableThroughAQuotationsFileRoutes() {
        PricingChain chain = pricingRequestReadyForTheCeo();
        DealQuotationDto quotation = quotationFromThePricingRequest(chain.pricingRequestId());
        long own = seed(quotation.id(), FILE, VENDOR_QUOTE, salesActor);

        // Control: Import, whose file it is, reads it where it belongs.
        assertThat(factoryQuoteService.getAttachment(chain.factoryQuoteFile(), importActor).fileName())
            .isEqualTo("factory-cost.pdf");
        refused("sales reading the factory quote's file where it belongs", FORBIDDEN,
            () -> factoryQuoteService.getAttachment(chain.factoryQuoteFile(), salesActor));

        for (UserPrincipal caller : List.of(salesActor, salesManagerActor, ceoActor)) {
            refused(caller.role() + " downloading the factory's file by its id", NOT_FOUND,
                () -> attachmentService.download(chain.factoryQuoteFile(), caller));
        }
        for (UserPrincipal editor : List.of(salesActor, salesManagerActor)) {
            refused(editor.role() + " deleting the factory's file by its id", NOT_FOUND,
                () -> attachmentService.delete(chain.factoryQuoteFile(), editor));
        }
        assertThat(storedBytes(chain.factoryQuoteFile())).isEqualTo(FACTORY_COST);

        // Control: the quotation's own file is served to the same caller.
        assertThat(served(own, salesActor)).isEqualTo(VENDOR_QUOTE);
    }

    // ── the chain, as DealQuotationPricingRequestApprovalIntegrationTest builds it ────────────

    private record PricingChain(long pricingRequestId, long factoryQuoteFile) {}

    /** A two-item pricing request Import has answered for both factories, with one real file on
     * the first factory's quote. */
    private PricingChain pricingRequestReadyForTheCeo() {
        long pricingRequestId = pricingRequestService.createDraft(ticketId, twoItemPricingRequest(), salesActor)
            .summary().id();
        pricingRequestService.submit(pricingRequestId, salesActor);
        pricingRequestService.pickup(pricingRequestId, importActor);
        List<FactoryQuoteDto> drafts = factoryQuoteService.generateDrafts(pricingRequestId, importActor);
        long factoryQuoteFile = factoryQuoteService.uploadAttachment(drafts.get(0).id(),
            file("factory-cost.pdf", PDF, FACTORY_COST), importActor).id();
        for (FactoryQuoteDto draft : drafts) {
            // CR-1: the import side must mark the factory contacted before entering its price.
            factoryQuoteService.markContacted(draft.id(), new th.co.glr.hr.factoryquote.FactoryQuoteRequests
                .MarkFactoryContactedRequest(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Bangkok")), null), importActor);
            FactoryQuoteDto responded = factoryQuoteService.receive(draft.id(),
                response("REF-" + draft.factoryName(), draft.items().get(0).pricingRequestItemId()), importActor);
            factoryQuoteService.markReadyForCosting(responded.id(), importActor);
        }
        return new PricingChain(pricingRequestId, factoryQuoteFile);
    }

    /** The CEO approves a decision, and the owning rep writes the quotation from it. */
    private DealQuotationDto quotationFromThePricingRequest(long pricingRequestId) {
        PricingDecisionDto started = decisionService.startReview(pricingRequestId,
            new StartPricingDecisionRequest(new BigDecimal("0.20"), "THB", null, UUID.randomUUID().toString()),
            ceoActor);
        List<UpdatePricingDecisionItemRequest> discounts = List.of(
            discount(started.items().get(0).id(), "10"), discount(started.items().get(1).id(), "5"));
        PricingDecisionDto priced = decisionService.update(started.id(),
            new UpdatePricingDecisionRequest(null, "NET", discounts), ceoActor);
        decisionService.approve(priced.id(),
            new ApprovePricingDecisionRequest("อนุมัติ", UUID.randomUUID().toString()), ceoActor);
        DealQuotationDto quotation = quotationService.createFromPricingRequest(pricingRequestId, salesActor);
        assertThat(quotation.origin()).as("the premise: a quotation of the PRICING_REQUEST origin").isEqualTo("PRICING_REQUEST");
        return quotation;
    }

    private static UpdatePricingDecisionItemRequest discount(long itemId, String discountPct) {
        return new UpdatePricingDecisionItemRequest(itemId, null, null, null, null, false,
            new BigDecimal(discountPct), false, null, false, null, false);
    }

    private PricingRequestRequests.CreatePricingRequestRequest twoItemPricingRequest() {
        return new PricingRequestRequests.CreatePricingRequestRequest(
            PricingRequestRecipient.DESIGNER, null, "Designer Co.", LocalDate.now().plusDays(14),
            new BigDecimal("1000.00"), "THB", "attachment test request", UUID.randomUUID().toString(),
            List.of(
                pricingItem("SCG", "Tile A-ATT", "Factory A-ATT", catalogProductIdFactoryA, 10),
                pricingItem("Cotto", "Tile B-ATT", "Factory B-ATT", catalogProductIdFactoryB, 5)));
    }

    private static PricingRequestRequests.PricingRequestItemRequest pricingItem(
        String brand, String model, String factory, long productId, int pieces
    ) {
        return new PricingRequestRequests.PricingRequestItemRequest(null, productId, null, brand, model,
            brand + " " + model, "White", "Matte", "60x60", factory, null, null, null, null,
            QuantityType.CONFIRMED, null, null, null,
            null, new BigDecimal("10"), new BigDecimal("0.36"), WastageCalculator.QUANTITY_MODE_PIECES,
            null, pieces, WastageCalculator.WASTAGE_MODE_NONE, null, 4, null,
            false, "ไทย-สต็อก", 3, 7, null, null, null);
    }

    private static TicketItemRequest ticketItem(String brand, String model, String factory) {
        return new TicketItemRequest(brand, model, "White", "Matte", "60x60", factory,
            new BigDecimal("1"), null, "PIECE", null, null, null, null, "THB");
    }

    private static ReceiveFactoryQuoteRequest response(String ref, long pricingRequestItemId) {
        return new ReceiveFactoryQuoteRequest(ref, "THB", "30 days", "45 days",
            "revision", "note", List.of(new ReceiveFactoryQuoteItemRequest(
                pricingRequestItemId, null, null, new BigDecimal("1.00"), "piece", "piece",
                new BigDecimal("100.00"), "THB", null, new BigDecimal("1.00"), null, null,
                "45 days", null, null)),
            UUID.randomUUID().toString());
    }

    /** The stored quotation resaved with a ยืนราคา day count, which submit requires of this origin. */
    private static UpsertDealQuotationRequest withValidityDays(DealQuotationDto quotation, int days) {
        List<ItemInput> items = quotation.items().stream()
            .map(DealQuotationAttachmentPricingRequestOriginIntegrationTest::stored).toList();
        return new UpsertDealQuotationRequest(quotation.contactId(), quotation.deptCode(),
            quotation.unitCode(), quotation.offerDate(), quotation.depositPercent(), quotation.remainderMode(),
            quotation.creditDays(), days, quotation.validityMode(), quotation.validityUntil(),
            quotation.customerNotes(), quotation.priceMode(), quotation.documentLanguage(), quotation.currency(),
            quotation.printedByDisplayId(), quotation.salesRepDisplayId(), quotation.projectName(),
            quotation.omitContactHonorific(), quotation.fullPaymentTerm(), items);
    }

    private static ItemInput stored(DealQuotationItemDto item) {
        return new ItemInput(item.locationLabel(), item.catalogPriceId(), item.productCode(),
            item.brand(), item.model(), item.color(), item.texture(), item.sizeText(), item.thicknessMm(),
            item.sqmPerPiece(), item.quantityMode(), item.areaSqm(), item.piecesInput(), item.wastageMode(),
            item.wastageValue(), item.piecesPerBox(), item.unitPrice(), item.discountPct(), item.originCountry(),
            item.leadTimeMinDays(), item.leadTimeMaxDays(), item.itemNotes(),
            "TILE", null, null, null, item.specialPriceSqm(), null, null, null, null,
            item.id(), item.sqmPerBox(), true);
    }
}
