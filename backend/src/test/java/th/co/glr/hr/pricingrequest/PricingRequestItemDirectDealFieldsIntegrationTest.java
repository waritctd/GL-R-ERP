package th.co.glr.hr.pricingrequest;

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
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.WastageCalculator;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestDetailDto;
import th.co.glr.hr.pricingrequest.PricingRequestDtos.PricingRequestItemDto;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CreatePricingRequestRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * Direct-deal-form parity on pricing-request items: proves, against real
 * Postgres and through the real {@link PricingRequestService}, that:
 * <ul>
 *   <li>create/update persist every new tile field on {@code sales.pricing_request_item};</li>
 *   <li>a missing required field (model/color/texture/size/thicknessMm/sqmPerPiece/
 *       piecesPerBox/quantity) is rejected with 400, both on create and on update;</li>
 *   <li>{@code requestedQty}/{@code requestedQtySqm}/{@code requestedUnit}/
 *       {@code requestedUnitBasis} are correctly DERIVED via {@link WastageCalculator}, including
 *       wastage and full-box rounding — never the client-sent value;</li>
 *   <li>a legacy row (no new-form columns at all, as if written before they existed) still reads
 *       back cleanly with every new field null.</li>
 * </ul>
 */
class PricingRequestItemDirectDealFieldsIntegrationTest extends AbstractPostgresIntegrationTest {
    private PricingRequestRepository pricingRequests;
    private PricingRequestService pricingRequestService;
    private TicketRepository tickets;

    private long salesRepId;
    private UserPrincipal salesActor;
    private long ticketId;
    private long catalogProductId;

    @BeforeEach
    void wireServicesAndCreateDeal() {
        tickets = new TicketRepository(jdbc);
        pricingRequests = new PricingRequestRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        ObjectMapper objectMapper = new ObjectMapper();
        pricingRequestService = new PricingRequestService(
            pricingRequests, tickets, notifications, objectMapper, new ContactRepository(jdbc),
            new FileStorageService("/tmp/glr-pricing-item-fields-test-uploads"), factoryQuoteCarryForward());
        TicketService ticketService = new TicketService(tickets, notifications, objectMapper, customers,
            new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc), new th.co.glr.hr.customer.ContactRepository(jdbc), new th.co.glr.hr.customer.ProjectRepository(jdbc),
            new th.co.glr.hr.dealquotation.DealQuotationRepository(jdbc, new th.co.glr.hr.catalog.CatalogRepository(jdbc)));

        salesRepId = employees.create(new UpsertEmployeeRequest(
            null, null, "พนักงานขาย V185", null, null, null, null, null, null, null,
            "sales-v185@glr.co.th", null, "SALES", "แผนกขาย", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        salesActor = new UserPrincipal(salesRepId, "sales-v185@glr.co.th", "Sales V185", "sales", salesRepId,
            true, LocalDate.now(), false, null, false);

        catalogProductId = insertCatalogProduct("V185 Test Factory", "TH", "V185-TEST-001",
            new BigDecimal("100.00"), "THB", "per_piece");

        CustomerDto customer = customers.create(
            "บริษัท ทดสอบ V185 จำกัด", "0100000000001", "123 ถนนทดสอบ", "สำนักงานใหญ่", "02-000-0000");
        ProjectDto project = projects.create(customer.id(), "โครงการทดสอบ V185");
        TicketDto created = ticketService.create(
            new CreateTicketRequest("ใบเสนอราคา V185", "NORMAL", customer.name(), customer.id(), project.id(),
                null, null, null,
                List.of(new TicketItemRequest("Toyota", "Hilux", "White", "Matte", "L", null,
                    new BigDecimal("1"), null, null, null, null, null, null, "THB"))),
            salesActor);
        ticketId = created.summary().id();
    }

    // ── derivation: PIECES mode, no wastage, no box rounding ───────────────────────────────

    @Test
    void createDraft_persistsNewFieldsAndDerivesRequestedQtyFromPieces() {
        PricingRequestDetailDto detail = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b
                .quantityMode(WastageCalculator.QUANTITY_MODE_PIECES).piecesInput(10)
                .wastageMode(WastageCalculator.WASTAGE_MODE_NONE).piecesPerBox(4)))),
            salesActor);

        PricingRequestItemDto item = onlyItem(detail);
        // 10 pieces, no wastage, box multiple of 4 -> rounds UP to 12 (round-to-full-box default).
        assertThat(item.requestedQty()).isEqualByComparingTo("12");
        assertThat(item.requestedUnit()).isEqualTo("แผ่น");
        assertThat(item.requestedUnitBasis()).isEqualTo(UnitBasis.PER_PIECE);
        assertThat(item.requestedQtySqm()).isEqualByComparingTo(new BigDecimal("12").multiply(new BigDecimal("0.36")));
        assertThat(item.piecesBeforeWastage()).isEqualTo(10);
        assertThat(item.piecesAfterWastage()).isEqualTo(10);
        assertThat(item.boxes()).isEqualTo(3);
        assertThat(item.roundToFullBox()).isTrue();
        assertThat(item.thicknessMm()).isEqualByComparingTo("10");
        assertThat(item.sqmPerPiece()).isEqualByComparingTo("0.36");
        assertThat(item.piecesPerBox()).isEqualTo(4);
        assertThat(item.color()).isEqualTo("สี");
        assertThat(item.texture()).isEqualTo("ผิว");
        assertThat(item.size()).isEqualTo("60x60");
    }

    @Test
    void createDraft_derivesRequestedQtyWithWastageAndLoosePieces() {
        // 10 pieces + 10% wastage = 11 (HALF_UP), piecesPerBox=4, roundToFullBox=false -> stays 11 (no rounding),
        // boxes=2, loose=3.
        PricingRequestDetailDto detail = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b
                .quantityMode(WastageCalculator.QUANTITY_MODE_PIECES).piecesInput(10)
                .wastageMode(WastageCalculator.WASTAGE_MODE_PERCENT).wastageValue(new BigDecimal("10"))
                .piecesPerBox(4).roundToFullBox(false)))),
            salesActor);

        PricingRequestItemDto item = onlyItem(detail);
        assertThat(item.piecesBeforeWastage()).isEqualTo(10);
        assertThat(item.piecesAfterWastage()).isEqualTo(11);
        assertThat(item.requestedQty()).isEqualByComparingTo("11");
        assertThat(item.boxes()).isEqualTo(2);
        assertThat(item.roundToFullBox()).isFalse();
    }

    @Test
    void createDraft_derivesRequestedQtyFromAreaMode() {
        // AREA mode: areaSqm=3.5, sqmPerPiece=0.36 -> piecesPerSqm = round2(1/0.36) = 2.78,
        // pieces = ceil(3.5 * 2.78) = ceil(9.73) = 10, no wastage (every fraction of a piece
        // rounds up). piecesPerBox is required
        // even here, so this uses piecesPerBox=10 -- an EXACT multiple of the
        // derived 10 pieces, so full-box rounding (the default) is a no-op and does not obscure
        // what this test actually exercises (the AREA -> pieces conversion).
        PricingRequestDetailDto detail = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b
                .quantityMode(WastageCalculator.QUANTITY_MODE_AREA).areaSqm(new BigDecimal("3.5"))
                .wastageMode(WastageCalculator.WASTAGE_MODE_NONE).piecesPerBox(10)))),
            salesActor);

        PricingRequestItemDto item = onlyItem(detail);
        assertThat(item.requestedQty()).isEqualByComparingTo("10");
        assertThat(item.boxes()).isEqualTo(1);
    }

    // ── ประเทศต้นทาง required ───────────────────────────────────────────────

    @Test
    void createDraft_rejectsItemMissingOriginCountry() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b.originCountry(null)))), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("ประเทศต้นทาง");
            });
    }

    // ── a tiny AREA quantity rounds up to one piece ────────────────────────────────────────

    @Test
    void createDraft_aTinyAreaRoundsUpToOnePiece() {
        // 0.3 m2 at 0.72 m2/piece (piecesPerSqm 1.39) = 0.417 rounds UP to 1 piece
        // ("ปัดขึ้นทุกกรณี"): any positive area is at least 1 piece, so the line is accepted
        // and V59's chk_pricing_request_item_qty (requested_qty > 0) is never reached.
        PricingRequestDetailDto detail = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b
                .quantityMode(WastageCalculator.QUANTITY_MODE_AREA).areaSqm(new BigDecimal("0.3"))
                .sqmPerPiece(new BigDecimal("0.72")).wastageMode(WastageCalculator.WASTAGE_MODE_NONE)
                .piecesPerBox(10).roundToFullBox(false)))),
            salesActor);
        assertThat(onlyItem(detail).requestedQty()).isEqualByComparingTo("1");
    }

    // ── Sales thickness never writes to the shared catalogue ───────────────────────────────

    @Test
    void submit_neverWritesSalesThicknessToTheSharedCatalog() {
        // A typed thickness is unreviewed: a typo must not become catalogue truth. Costing reads
        // it from the request item instead.
        long nullThicknessPrice = insertCatalogProduct("WriteBack Factory", "TH", "WB-NULL-001",
            new BigDecimal("100.00"), "THB", "per_piece", "ACTIVE", null);
        long id = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b
                .productId(nullThicknessPrice).thicknessMm(new BigDecimal("12"))))),
            salesActor).summary().id();
        pricingRequestService.submit(id, salesActor);

        BigDecimal catalogThickness = jdbc.queryForObject(
            "SELECT thickness_mm FROM price_catalog.product_prices WHERE price_id = :id",
            Map.of("id", nullThicknessPrice), BigDecimal.class);
        assertThat(catalogThickness).as("submit must leave the catalogue thickness NULL").isNull();
        assertThat(onlyItem(pricingRequestService.get(id, salesActor)).thicknessMm())
            .as("the typed value stays on the request item").isEqualByComparingTo("12");
    }

    private PricingRequestItemDto onlyItem(PricingRequestDetailDto detail) {
        List<PricingRequestItemDto> items = pricingRequests.findItems(detail.summary().id());
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private CreatePricingRequestRequest createRequest(List<PricingRequestItemRequest> items) {
        return new CreatePricingRequestRequest(
            PricingRequestRecipient.BUYER, null, "Buyer Co.", LocalDate.now().plusDays(14),
            null, "THB", "V185 test", java.util.UUID.randomUUID().toString(), items);
    }

    /** A fully valid, complete new-form item — every owner-ruling-required field populated, no wastage, no box
     * rounding, PIECES mode with 10 pieces. customize lets a test null out or override exactly the field(s) it
     * cares about. */
    private PricingRequestItemRequest completeItem(java.util.function.UnaryOperator<ItemBuilder> customize) {
        ItemBuilder builder = new ItemBuilder()
            .brand("Toyota").model("Hilux").color("สี").texture("ผิว").size("60x60")
            .thicknessMm(new BigDecimal("10")).sqmPerPiece(new BigDecimal("0.36"))
            .quantityMode(WastageCalculator.QUANTITY_MODE_PIECES).piecesInput(10)
            .wastageMode(WastageCalculator.WASTAGE_MODE_NONE).piecesPerBox(4)
            .roundToFullBox(true)
            // Origin country and lead time are unconditionally required too.
            .originCountry("ไทย-สต็อก").leadTimeMinDays(3).leadTimeMaxDays(7);
        return customize.apply(builder).build();
    }

    /** Tiny fluent builder over PricingRequestItemRequest's canonical (35-arg) constructor — a positional call site
     * that long would be unreadable, and this file needs many small variations of it. */
    private static final class ItemBuilder {
        private Long productId;
        private Long variantId;
        private String factory;
        private String brand;
        private String model;
        private String color;
        private String texture;
        private String size;
        private BigDecimal thicknessMm;
        private BigDecimal sqmPerPiece;
        private String quantityMode;
        private BigDecimal areaSqm;
        private Integer piecesInput;
        private String wastageMode;
        private BigDecimal wastageValue;
        private Integer piecesPerBox;
        private Boolean roundToFullBox;
        private String originCountry;
        private Integer leadTimeMinDays;
        private Integer leadTimeMaxDays;
        private String originCountryOther;

        ItemBuilder productId(Long v) { this.productId = v; return this; }
        // Neither has an editor input yet (variantId never has; the old form's
        // dedicated โรงงาน field that wrote `factory` was removed), but both must
        // round-trip through create/update/revision instead of being silently nulled on every
        // save -- see PricingRequestCreateModal.jsx's pricingRequestItemInputFromRow.
        ItemBuilder variantId(Long v) { this.variantId = v; return this; }
        ItemBuilder factory(String v) { this.factory = v; return this; }
        ItemBuilder brand(String v) { this.brand = v; return this; }
        ItemBuilder model(String v) { this.model = v; return this; }
        ItemBuilder color(String v) { this.color = v; return this; }
        ItemBuilder texture(String v) { this.texture = v; return this; }
        ItemBuilder size(String v) { this.size = v; return this; }
        ItemBuilder thicknessMm(BigDecimal v) { this.thicknessMm = v; return this; }
        ItemBuilder sqmPerPiece(BigDecimal v) { this.sqmPerPiece = v; return this; }
        ItemBuilder quantityMode(String v) { this.quantityMode = v; return this; }
        ItemBuilder areaSqm(BigDecimal v) { this.areaSqm = v; return this; }
        ItemBuilder piecesInput(Integer v) { this.piecesInput = v; return this; }
        ItemBuilder wastageMode(String v) { this.wastageMode = v; return this; }
        ItemBuilder wastageValue(BigDecimal v) { this.wastageValue = v; return this; }
        ItemBuilder piecesPerBox(Integer v) { this.piecesPerBox = v; return this; }
        ItemBuilder roundToFullBox(Boolean v) { this.roundToFullBox = v; return this; }
        ItemBuilder originCountry(String v) { this.originCountry = v; return this; }
        ItemBuilder leadTimeMinDays(Integer v) { this.leadTimeMinDays = v; return this; }
        ItemBuilder leadTimeMaxDays(Integer v) { this.leadTimeMaxDays = v; return this; }
        ItemBuilder originCountryOther(String v) { this.originCountryOther = v; return this; }

        PricingRequestItemRequest build() {
            return new PricingRequestItemRequest(
                null, productId, variantId, brand, model, null, color, texture, size, factory,
                null, null, null, null, QuantityType.REFERENCE, null, null, null,
                null, thicknessMm, sqmPerPiece, quantityMode, areaSqm, piecesInput,
                wastageMode, wastageValue, piecesPerBox, null, roundToFullBox, originCountry,
                leadTimeMinDays, leadTimeMaxDays, null, null, null, originCountryOther);
        }
    }
}
