package th.co.glr.hr.importrequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.factory.FactoryConfigRepository;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;
import th.co.glr.hr.importrequest.ImportRequestRequests.AdvanceImportStepRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.CreateImportRequestsRequest;
import th.co.glr.hr.importrequest.ImportRequestRequests.NewFactoryCountryInput;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestRepository;
import th.co.glr.hr.pricingrequest.PricingRequestService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.FulfilmentStatus;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * V184's factory-resolution cascade (owner decision 2, revised 09-18 — country is now REQUIRED for
 * an auto-created factory, with {@code 'ZZ'}/อื่นๆ as the one catch-all), the per-factory rollup
 * ({@code TicketService#applyImportRequestRollup}), and the legacy one-shot import actions'
 * refusal on an IR-tracked deal — against real Postgres, through the real service.
 *
 * <p>Every case here is either a WRITE to the real {@code price_catalog.factories} master (the
 * auto-create path) or a scope/authorisation-shaped decision, so per CLAUDE.md this needs real-DB
 * evidence, not a mocked repository.
 */
class ImportRequestFactoryResolutionIntegrationTest extends AbstractPostgresIntegrationTest {

    private ImportRequestService service;
    private TicketService ticketService;
    private TicketRepository tickets;

    private long ownerId;
    private UserPrincipal owner;
    private UserPrincipal importUser;
    private UserPrincipal ceoUser;

    @BeforeEach
    void wireRealCollaborators() {
        ImportRequestQueryRepository queries = new ImportRequestQueryRepository(jdbc);
        ImportRequestRepository stored = new ImportRequestRepository(jdbc);
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        tickets = new TicketRepository(jdbc);

        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ObjectMapper objectMapper = new ObjectMapper();
        FileStorageService fileStorage = new FileStorageService("/tmp/glr-ir-resolution-test-uploads");
        PricingRequestService pricingRequestService = new PricingRequestService(
            new PricingRequestRepository(jdbc), tickets, notifications, objectMapper,
            new ContactRepository(jdbc), fileStorage, factoryQuoteCarryForward());
        EmployeeAuthRepository auth = new EmployeeAuthRepository(jdbc);
        ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, auth);

        service = new ImportRequestService(queries, new ImportRequestRenderer(), stored, factories,
            tickets, ticketService);

        ownerId = insertEmployee("RESOLV-OWN");
        owner = principal(ownerId, "sales");
        importUser = principal(insertEmployee("RESOLV-IMP"), "import");
        ceoUser = principal(insertEmployee("RESOLV-CEO"), "ceo");
    }

    // ── factory resolution cascade (owner decision 2) ────────────────────────────────────────────

    /**
     * Step 5 of the plan's cascade: no direct id anywhere AND no name anywhere for a line refuses
     * outright, naming the line, rather than silently deriving a factory from {@code brand} (the
     * 2026-09-18 correction). Zero rows must land in the factory master — a refused create must not
     * have side effects.
     */
    @Test
    void aLineWithNoFactoryAnywhereIsRefused_andCreatesNoFactoryRow() {
        long ticketId = insertTicket("RESOLV-1");
        insertItem(ticketId, "SomeBrand", "Unresolvable Line", "60x60", "10", "pcs", 0, null);
        int factoriesBefore = countFactories();

        assertThatThrownBy(() -> service.createDrafts(ticketId, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("Unresolvable Line");
            });
        assertThat(countFactories()).isEqualTo(factoriesBefore);
    }

    /**
     * A typed name matching an EXISTING master row apart from case, internal whitespace, or Unicode
     * NFC-vs-NFD combining-mark form reuses that row rather than creating a near-duplicate — each
     * axis checked separately, against its own deal, so a failure names which one broke. None of
     * these need {@code newFactoryCountries}: the line resolves to an EXISTING factory, so the
     * auto-create path (and its country requirement) is never reached.
     */
    @Test
    void aTypedNameMatchingAnExistingFactoryByCase_reusesTheRow_andCreatesNothing() {
        long factoryId = insertFactory("Ceramica Digitale", "IT", null);
        long ticketId = insertTicket("RESOLV-2A");
        insertItem(ticketId, "AnyBrand", "Case Variant Line", "60x60", "10", "pcs", 0,
            "CERAMICA digitale");
        int before = countFactories();

        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);

        assertThat(drafts.get(0).factoryId()).isEqualTo(factoryId);
        assertThat(countFactories()).isEqualTo(before);
    }

    @Test
    void aTypedNameMatchingAnExistingFactoryByWhitespace_reusesTheRow_andCreatesNothing() {
        long factoryId = insertFactory("Ceramica Digitale", "IT", null);
        long ticketId = insertTicket("RESOLV-2B");
        insertItem(ticketId, "AnyBrand", "Whitespace Variant Line", "60x60", "10", "pcs", 0,
            "  Ceramica   Digitale  ");
        int before = countFactories();

        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);

        assertThat(drafts.get(0).factoryId()).isEqualTo(factoryId);
        assertThat(countFactories()).isEqualTo(before);
    }

    /**
     * é (U+00E9, precomposed) vs. e + combining acute accent (U+0065 U+0301) — the same visible text,
     * two different byte sequences, which is exactly what NFC normalization must reconcile. Thai
     * script has no canonical precomposition to decompose from, so this needs a script that does.
     */
    @Test
    void aTypedNameMatchingAnExistingFactoryByNfcVsNfdForm_reusesTheRow_andCreatesNothing() {
        String nfcName = "Café Ceramica"; // precomposed é
        long factoryId = insertFactory(nfcName, "IT", null);
        String nfdVariant = java.text.Normalizer.normalize(nfcName, java.text.Normalizer.Form.NFD);
        assertThat(nfdVariant).as("the two byte sequences must actually differ for this test to mean anything")
            .isNotEqualTo(nfcName);

        long ticketId = insertTicket("RESOLV-2C");
        insertItem(ticketId, "AnyBrand", "NFD Variant Line", "60x60", "10", "pcs", 0, nfdVariant);
        int before = countFactories();

        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);

        assertThat(drafts.get(0).factoryId()).isEqualTo(factoryId);
        assertThat(countFactories()).isEqualTo(before);
    }

    // ── auto-create requires a country (owner decision 09-18) ───────────────────────────────────

    /**
     * The headline rule: a factory name matching NOTHING existing, with NO country supplied for it,
     * refuses the whole call — 409, naming the factory — and creates ZERO rows (no factory, no IR).
     */
    @Test
    void aNewFactoryWithNoCountrySupplied_isRefused_andCreatesNothing() {
        long ticketId = insertTicket("RESOLV-3-NOCOUNTRY");
        insertItem(ticketId, "AnyBrand", "Needs A Country Line", "60x60", "10", "pcs", 0,
            "โรงงานไม่มีประเทศ");
        int factoriesBefore = countFactories();

        assertThatThrownBy(() -> service.createDrafts(ticketId, null, owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("โรงงานไม่มีประเทศ");
            });
        assertThat(countFactories()).isEqualTo(factoriesBefore);
        assertThat(service.list(ticketId, owner)).isEmpty();
    }

    /**
     * A typed name matching NOTHING existing, WITH a supplied real country, creates exactly one row
     * — country set, country_other NULL — with the auto-create provenance V184 added ({@code
     * created_source='IMPORT_REQUEST'}, {@code created_by_id}, {@code created_at}).
     */
    @Test
    void aTypedNameMatchingNothing_withARealCountry_createsExactlyOneFactoryRow_withProvenance() {
        long ticketId = insertTicket("RESOLV-3");
        insertItem(ticketId, "AnyBrand", "Brand New Factory Line", "60x60", "10", "pcs", 0,
            "โรงงานใหม่เอี่ยม");
        int factoriesBefore = countFactories();

        List<ImportRequestDto> drafts = service.createDrafts(ticketId,
            new CreateImportRequestsRequest(null,
                List.of(new NewFactoryCountryInput("โรงงานใหม่เอี่ยม", "IT", null))),
            owner);

        assertThat(countFactories()).isEqualTo(factoriesBefore + 1);
        assertThat(drafts).hasSize(1);
        long newFactoryId = drafts.get(0).factoryId();
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT name, created_source, created_by_id, created_at, country, country_other
              FROM price_catalog.factories WHERE factory_id = :id
            """, Map.of("id", newFactoryId));
        assertThat(row.get("name")).isEqualTo("โรงงานใหม่เอี่ยม");
        assertThat(row.get("created_source")).isEqualTo("IMPORT_REQUEST");
        assertThat(row.get("created_by_id")).isEqualTo(ownerId);
        assertThat(row.get("created_at")).isNotNull();
        assertThat(row.get("country")).isEqualTo("IT");
        assertThat(row.get("country_other")).isNull();

        // Calling createDrafts again for a NEW deal with the SAME typed name must reuse, not
        // duplicate — proves the normalized-name match also covers what this test itself created,
        // and needs no newFactoryCountries entry this time (nothing left to create).
        long ticketId2 = insertTicket("RESOLV-3B");
        insertItem(ticketId2, "AnyBrand", "Second Deal Same Factory", "60x60", "5", "pcs", 0,
            "โรงงานใหม่เอี่ยม");
        List<ImportRequestDto> drafts2 = service.createDrafts(ticketId2, null, owner);
        assertThat(drafts2.get(0).factoryId()).isEqualTo(newFactoryId);
        assertThat(countFactories()).isEqualTo(factoriesBefore + 1);
    }

    /** {@code 'ZZ'} (อื่นๆ) with NO {@code countryOther} typed is refused — 400, creates nothing. */
    @Test
    void aNewFactoryWithZzAndNoCountryOther_isRefused400_andCreatesNothing() {
        long ticketId = insertTicket("RESOLV-3-ZZ-BLANK");
        insertItem(ticketId, "AnyBrand", "ZZ No Text Line", "60x60", "10", "pcs", 0, "โรงงานลึกลับ");
        int factoriesBefore = countFactories();

        assertThatThrownBy(() -> service.createDrafts(ticketId,
                new CreateImportRequestsRequest(null,
                    List.of(new NewFactoryCountryInput("โรงงานลึกลับ", "ZZ", null))),
                owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThat(countFactories()).isEqualTo(factoriesBefore);
    }

    /** {@code 'ZZ'} (อื่นๆ) WITH a typed {@code countryOther} creates the row: country='ZZ'. */
    @Test
    void aNewFactoryWithZzAndCountryOther_createsARowWithCountryOtherSet() {
        long ticketId = insertTicket("RESOLV-3-ZZ-TEXT");
        insertItem(ticketId, "AnyBrand", "ZZ With Text Line", "60x60", "10", "pcs", 0, "โรงงานลึกลับ2");

        List<ImportRequestDto> drafts = service.createDrafts(ticketId,
            new CreateImportRequestsRequest(null,
                List.of(new NewFactoryCountryInput("โรงงานลึกลับ2", "ZZ", "Farawaystan"))),
            owner);

        Map<String, Object> row = jdbc.queryForMap("""
            SELECT country, country_other FROM price_catalog.factories WHERE factory_id = :id
            """, Map.of("id", drafts.get(0).factoryId()));
        assertThat(row.get("country")).isEqualTo("ZZ");
        assertThat(row.get("country_other")).isEqualTo("Farawaystan");
    }

    /** A non-'ZZ' country with a {@code countryOther} typed anyway is refused — stray data. */
    @Test
    void aNewFactoryWithARealCountryAndCountryOther_isRefused400() {
        long ticketId = insertTicket("RESOLV-3-STRAY");
        insertItem(ticketId, "AnyBrand", "Stray Text Line", "60x60", "10", "pcs", 0, "โรงงานสเตรย์");

        assertThatThrownBy(() -> service.createDrafts(ticketId,
                new CreateImportRequestsRequest(null,
                    List.of(new NewFactoryCountryInput("โรงงานสเตรย์", "IT", "Not ZZ but has text"))),
                owner))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    /**
     * Several new factories on one deal: ALL must be supplied and valid before ANY is created — one
     * missing name refuses the whole call even though the others were supplied correctly.
     */
    @Test
    void severalNewFactories_oneMissingCountry_refusesTheWholeCall_createsNone() {
        long ticketId = insertTicket("RESOLV-3-PARTIAL");
        insertItem(ticketId, "AnyBrand", "Has Country Line", "60x60", "10", "pcs", 0, "มีประเทศ");
        insertItem(ticketId, "AnyBrand", "Missing Country Line", "60x60", "10", "pcs", 1, "ไม่มีประเทศ");
        int factoriesBefore = countFactories();

        assertThatThrownBy(() -> service.createDrafts(ticketId,
                new CreateImportRequestsRequest(null,
                    List.of(new NewFactoryCountryInput("มีประเทศ", "IT", null))),
                owner))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
                assertThat(e.getMessage()).contains("ไม่มีประเทศ");
            });
        // The factory whose country WAS supplied must not have been created either.
        assertThat(countFactories()).isEqualTo(factoriesBefore);
    }

    /** {@code 'ZZ'} sorts LAST in the country picker, after every real (Thai-name-sorted) country. */
    @Test
    void listCountries_putsZzLast() {
        FactoryConfigRepository factories = new FactoryConfigRepository(jdbc);
        List<FactoryConfigRepository.CountryOptionDto> countries = factories.listCountries();

        assertThat(countries).isNotEmpty();
        assertThat(countries.get(countries.size() - 1).countryCode()).isEqualTo("ZZ");
        assertThat(countries).extracting(FactoryConfigRepository.CountryOptionDto::countryCode)
            .doesNotContain("XX");
    }

    /** Two factories sharing the same brand both issue — factory_id is the grouping key, not brand. */
    @Test
    void twoFactoriesSharingABrand_bothIssueIndependently() {
        long factoryA = insertFactory("Factory Alpha", "IT", null);
        long factoryB = insertFactory("Factory Beta", "IT", null);
        long ticketId = insertTicket("RESOLV-4");
        insertItem(ticketId, "SharedBrand", "Alpha Line", "60x60", "10", "pcs", 0, "Factory Alpha");
        insertItem(ticketId, "SharedBrand", "Beta Line", "60x60", "10", "pcs", 1, "Factory Beta");

        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);
        assertThat(drafts).extracting(ImportRequestDto::factoryId)
            .containsExactlyInAnyOrder(factoryA, factoryB);
        assertThat(drafts).extracting(ImportRequestDto::brand)
            .containsExactly("SharedBrand", "SharedBrand");

        for (ImportRequestDto d : drafts) {
            assertThat(service.issue(d.id(), null, owner).status()).isEqualTo(ImportRequestStatus.ISSUED);
        }
    }

    // ── F-SM-001 line prefill (owner decision 09-18 #3 §A) ──────────────────────────────────────

    /**
     * A realistic fictional fixture with one catalog-linked line and one hand-typed line, asserting
     * every prefilled field the plan calls out: code, size, qty, unit and the derived colour/surface
     * note. The catalog-linked line's code comes from {@code price_catalog.product_prices
     * .product_code} via {@code ticket_item.catalog_price_id} (NOT the hand-typed model, which is
     * deliberately given a DIFFERENT value here so a test bug that reads the wrong column would show
     * up as the wrong string); the hand-typed line has no catalog link at all, so its code falls all
     * the way to {@code ticket_item.model}.
     */
    @Test
    void createDrafts_prefillsCodeSizeQtyUnitAndNote_forCatalogLinkedAndHandTypedLines() {
        long catalogPriceId = insertCatalogProduct("Prefill Catalog Factory", "IT", "CAT-CODE-001",
            new BigDecimal("12.50"), "EUR", "per_sqm");
        insertFactory("Prefill Hand-Typed Factory", "IT", null);
        long ticketId = insertTicket("PREFILL-1");
        insertItemWithCatalogAndColor(ticketId, "AnyBrand", "Ignored Model Text", "60x60 cm", "10",
            "pcs", 0, null, catalogPriceId, "Grigio", "Matte");
        insertItemWithCatalogAndColor(ticketId, "AnyBrand", "Hand Typed Model", "30x30", "5", "pcs",
            1, "Prefill Hand-Typed Factory", null, "Bianco", null);

        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);

        ImportRequestDto catalogFactoryIr = drafts.stream()
            .filter(d -> "Prefill Catalog Factory".equals(d.factoryName())).findFirst().orElseThrow();
        assertThat(catalogFactoryIr.items()).hasSize(1);
        var catalogItem = catalogFactoryIr.items().get(0);
        assertThat(catalogItem.code()).as("catalog code beats the hand-typed model")
            .isEqualTo("CAT-CODE-001");
        assertThat(catalogItem.size()).isEqualTo("60x60 cm");
        assertThat(catalogItem.qty()).isEqualByComparingTo("10");
        assertThat(catalogItem.unit()).isEqualTo("pcs");
        assertThat(catalogItem.note()).as("colour + surface, both known")
            .isEqualTo("สี Grigio · ผิว Matte");

        ImportRequestDto handTypedFactoryIr = drafts.stream()
            .filter(d -> "Prefill Hand-Typed Factory".equals(d.factoryName())).findFirst().orElseThrow();
        assertThat(handTypedFactoryIr.items()).hasSize(1);
        var handTypedItem = handTypedFactoryIr.items().get(0);
        assertThat(handTypedItem.code()).as("no catalog link — falls back to the hand-typed model")
            .isEqualTo("Hand Typed Model");
        assertThat(handTypedItem.size()).isEqualTo("30x30");
        assertThat(handTypedItem.qty()).isEqualByComparingTo("5");
        assertThat(handTypedItem.unit()).isEqualTo("pcs");
        assertThat(handTypedItem.note()).as("surface unknown — colour alone, no dangling separator")
            .isEqualTo("สี Bianco");
    }

    /** Neither colour nor surface known: the note sub-row is omitted entirely, not a blank string. */
    @Test
    void createDrafts_omitsTheNoteEntirely_whenNeitherColourNorSurfaceIsKnown() {
        long ticketId = insertTicket("PREFILL-2");
        insertItemWithCatalogAndColor(ticketId, "AnyBrand", "Plain Model", "60x60", "10", "pcs", 0,
            "Prefill Plain Factory", null, null, null);

        List<ImportRequestDto> drafts = service.createDrafts(ticketId,
            new CreateImportRequestsRequest(null,
                List.of(new NewFactoryCountryInput("Prefill Plain Factory", "IT", null))),
            owner);

        assertThat(drafts.get(0).items().get(0).note()).isNull();
    }

    // ── rollup (owner decision 3) ─────────────────────────────────────────────────────────────

    @Test
    void rollup_firesOnlyOnceEveryFactoryIsReceived_notBefore() {
        insertFactory("Rollup Factory A", "IT", null);
        insertFactory("Rollup Factory B", "IT", null);
        long ticketId = insertTicket("RESOLV-5");
        insertItem(ticketId, "Brand", "Line A", "60x60", "10", "pcs", 0, "Rollup Factory A");
        insertItem(ticketId, "Brand", "Line B", "60x60", "10", "pcs", 1, "Rollup Factory B");

        List<ImportRequestDto> drafts = service.createDrafts(ticketId, null, owner);
        for (ImportRequestDto d : drafts) {
            service.issue(d.id(), null, owner);
        }
        assertThat(fulfillmentStatus(ticketId)).isEqualTo(FulfilmentStatus.IR_ISSUED);

        ImportRequestDto a = drafts.stream().filter(d -> "Rollup Factory A".equals(d.factoryName()))
            .findFirst().orElseThrow();
        ImportRequestDto b = drafts.stream().filter(d -> "Rollup Factory B".equals(d.factoryName()))
            .findFirst().orElseThrow();

        receiveAllSteps(a.id());
        // Only ONE of two factories RECEIVED — must NOT roll up yet.
        assertThat(fulfillmentStatus(ticketId)).isEqualTo(FulfilmentStatus.IR_ISSUED);

        receiveAllSteps(b.id());
        // Both RECEIVED — rolls up now.
        assertThat(fulfillmentStatus(ticketId)).isEqualTo(FulfilmentStatus.GOODS_RECEIVED);
    }

    /**
     * Owner decision 3: a deal already {@code PARTIALLY_DELIVERED} (some lines delivered from stock
     * while the imported remainder was still in transit — Case 8) must NOT have its delivery status
     * clobbered back onto the import axis when the last factory's IR reaches RECEIVED. Only the
     * {@code GOODS_RECEIVED} event is written, so {@code TicketRepository#hasReceivedGoods} flips
     * true without disturbing the delivery already under way.
     */
    @Test
    void rollup_onAMixedPartiallyDeliveredDeal_writesOnlyTheEvent_neverTouchesStatus() {
        insertFactory("Mixed Deal Factory", "IT", null);
        long ticketId = insertTicket("RESOLV-6");
        insertItem(ticketId, "Brand", "Import Line", "60x60", "10", "pcs", 0, "Mixed Deal Factory");

        long irId = service.createDrafts(ticketId, null, owner).get(0).id();
        service.issue(irId, null, owner);
        assertThat(fulfillmentStatus(ticketId)).isEqualTo(FulfilmentStatus.IR_ISSUED);

        // Simulate: while this factory's goods were still in transit, the deal's stock-sourced lines
        // were already delivered, putting the deal on the DELIVERY axis.
        jdbc.update("UPDATE sales.ticket SET fulfillment_status = :s WHERE ticket_id = :id",
            Map.of("s", FulfilmentStatus.PARTIALLY_DELIVERED, "id", ticketId));

        int eventsBefore = countGoodsReceivedEvents(ticketId);
        receiveAllSteps(irId);

        // Status untouched — still PARTIALLY_DELIVERED, never reset to GOODS_RECEIVED.
        assertThat(fulfillmentStatus(ticketId)).isEqualTo(FulfilmentStatus.PARTIALLY_DELIVERED);
        // ...but the event fired, so hasReceivedGoods-style reads still see it.
        assertThat(countGoodsReceivedEvents(ticketId)).isEqualTo(eventsBefore + 1);
    }

    // ── legacy one-shot actions refuse on an IR-tracked deal ─────────────────────────────────────

    @Test
    void legacyMarkIrSent_isRefusedOnceTheDealIsTrackedPerFactory() {
        insertFactory("Legacy Refusal Factory", "IT", null);
        long ticketId = insertTicket("RESOLV-7");
        insertItem(ticketId, "Brand", "Line", "60x60", "10", "pcs", 0, "Legacy Refusal Factory");
        service.issue(service.createDrafts(ticketId, null, owner).get(0).id(), null, owner);

        assertThat(tickets.hasLiveImportRequests(ticketId)).isTrue();
        assertThatThrownBy(() -> ticketService.markIrSent(ticketId, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
        // Status must not have moved despite the refusal.
        assertThat(fulfillmentStatus(ticketId)).isEqualTo(FulfilmentStatus.IR_ISSUED);
    }

    @Test
    void legacyIssueImportRequest_isRefusedOnceTheDealIsTrackedPerFactory() {
        insertFactory("Legacy Issue Refusal Factory", "IT", null);
        long ticketId = insertTicket("RESOLV-8");
        insertItem(ticketId, "Brand", "Line", "60x60", "10", "pcs", 0, "Legacy Issue Refusal Factory");
        service.issue(service.createDrafts(ticketId, null, owner).get(0).id(), null, owner);

        // The legacy one-shot action itself 409s once fulfillment_status is already IR_ISSUED —
        // recordFirstImportRequestIssued sets exactly that, so the deal cannot tell which path
        // raised its first import request.
        assertThatThrownBy(() -> ticketService.issueImportRequest(ticketId, importUser))
            .isInstanceOfSatisfying(ApiException.class,
                e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────────

    private void receiveAllSteps(long importRequestId) {
        for (String step : List.of(ImportRequestStep.ORDERED, ImportRequestStep.PICKED_UP,
                ImportRequestStep.IN_TRANSIT, ImportRequestStep.AWAITING_CUSTOMS, ImportRequestStep.RECEIVED)) {
            service.advanceStep(importRequestId, new AdvanceImportStepRequest(step, null, null), importUser);
        }
    }

    private String fulfillmentStatus(long ticketId) {
        return jdbc.queryForObject(
            "SELECT fulfillment_status FROM sales.ticket WHERE ticket_id = :id",
            Map.of("id", ticketId), String.class);
    }

    private int countGoodsReceivedEvents(long ticketId) {
        Integer n = jdbc.queryForObject("""
            SELECT count(*) FROM sales.ticket_event
             WHERE ticket_id = :id AND kind = 'GOODS_RECEIVED'
            """, Map.of("id", ticketId), Integer.class);
        return n == null ? 0 : n;
    }

    private int countFactories() {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM price_catalog.factories", Map.of(), Integer.class);
        return n == null ? 0 : n;
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-resolv@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }

    private long insertEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, first_name_th, last_name_th) "
                + "VALUES (:c, 'ทดสอบ', 'ใบขอซื้อ') RETURNING employee_id",
            Map.of("c", code), Long.class);
    }

    private long insertFactory(String name, String country, String countryOther) {
        return jdbc.queryForObject("""
            INSERT INTO price_catalog.factories (name, country, country_other, default_currency)
            VALUES (:name, :country, :countryOther, 'EUR') RETURNING factory_id
            """, new MapSqlParameterSource().addValue("name", name).addValue("country", country)
                .addValue("countryOther", countryOther), Long.class);
    }

    private long insertTicket(String code) {
        return jdbc.queryForObject("""
            INSERT INTO sales.ticket (code, title, created_by, customer_name, status, payment_status,
                                       sales_stage)
            VALUES (:code, 'ทดสอบ resolution', :by, 'บริษัท ทดสอบ จำกัด', 'quotation_issued',
                    'DEPOSIT_PAID', :stage)
            RETURNING ticket_id
            """, new MapSqlParameterSource().addValue("code", code).addValue("by", ownerId)
                .addValue("stage", DealStage.ORDER_RECEIVED), Long.class);
    }

    private void insertItem(long ticket, String brand, String model, String size, String qty,
                            String unit, int sortOrder, String factory) {
        insertItemWithCatalogAndColor(ticket, brand, model, size, qty, unit, sortOrder, factory,
            null, null, null);
    }

    /** Line-prefill fixture builder (owner decision 09-18 #3 §A): adds a catalog link and/or
     * colour/texture on top of {@link #insertItem}'s shape. */
    private void insertItemWithCatalogAndColor(long ticket, String brand, String model, String size,
            String qty, String unit, int sortOrder, String factory, Long catalogPriceId,
            String color, String texture) {
        jdbc.update("""
            INSERT INTO sales.ticket_item
                (ticket_id, brand, model, size, qty, unit, sort_order, factory, catalog_price_id,
                 color, texture)
            VALUES (:t, :brand, :model, :size, :qty, :unit, :sort, :factory, :catalogPriceId,
                    :color, :texture)
            """, new MapSqlParameterSource().addValue("t", ticket).addValue("brand", brand)
                .addValue("model", model).addValue("size", size)
                .addValue("qty", new BigDecimal(qty)).addValue("unit", unit)
                .addValue("sort", sortOrder).addValue("factory", factory)
                .addValue("catalogPriceId", catalogPriceId)
                .addValue("color", color).addValue("texture", texture));
    }
}
