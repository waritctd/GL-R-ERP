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
import th.co.glr.hr.pricingrequest.PricingRequestRequests.CustomerChangeRevisionRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.PricingRequestItemRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.SetItemFactoryRequest;
import th.co.glr.hr.pricingrequest.PricingRequestRequests.UpdatePricingRequestRequest;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.TicketDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * V185 (direct-deal-form parity, Phase 1 of the sales-flow redesign): proves, against real
 * Postgres and through the real {@link PricingRequestService}, that:
 * <ul>
 *   <li>create/update persist every new tile field on {@code sales.pricing_request_item};</li>
 *   <li>a missing required field (owner ruling: model/color/texture/size/thicknessMm/sqmPerPiece/
 *       piecesPerBox/quantity) is rejected with 400, both on create and on update;</li>
 *   <li>{@code requestedQty}/{@code requestedQtySqm}/{@code requestedUnit}/
 *       {@code requestedUnitBasis} are correctly DERIVED via {@link WastageCalculator}, including
 *       wastage and full-box rounding — never the client-sent value;</li>
 *   <li>a legacy row (no new-form columns at all, as if written before this migration) still reads
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
            new QuotationRenderer(), pricingRequestService, new th.co.glr.hr.auth.EmployeeAuthRepository(jdbc));

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
        // 10 pieces + 10% wastage = 11 (HALF_UP), piecesPerBox=4, roundToFullBox=false ->
        // stays 11 (no rounding), boxes=2, loose=3.
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
        // AREA mode: areaSqm=3.6, sqmPerPiece=0.36 -> piecesPerSqm = round2(1/0.36) = 2.78,
        // pieces = round(3.6 * 2.78) = round(10.008) = 10, no wastage. piecesPerBox is required
        // (owner ruling) even here, so this uses piecesPerBox=10 -- an EXACT multiple of the
        // derived 10 pieces, so full-box rounding (the default) is a no-op and does not obscure
        // what this test actually exercises (the AREA -> pieces conversion).
        PricingRequestDetailDto detail = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b
                .quantityMode(WastageCalculator.QUANTITY_MODE_AREA).areaSqm(new BigDecimal("3.6"))
                .wastageMode(WastageCalculator.WASTAGE_MODE_NONE).piecesPerBox(10)))),
            salesActor);

        PricingRequestItemDto item = onlyItem(detail);
        assertThat(item.requestedQty()).isEqualByComparingTo("10");
        assertThat(item.boxes()).isEqualTo(1);
    }

    // ── GLA-125 item 1: ประเทศต้นทาง required ───────────────────────────────────────────────

    @Test
    void createDraft_rejectsItemMissingOriginCountry() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b.originCountry(null)))), salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("ประเทศต้นทาง");
            });
    }

    // ── GLA-125 item 2: อื่นๆ requires the typed name ───────────────────────────────────────

    @Test
    void createDraft_rejectsOtherOriginCountryMissingTypedName() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b.originCountry("อื่นๆ").originCountryOther(null)))),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("ชื่อประเทศต้นทาง");
            });
    }

    @Test
    void createDraft_acceptsOtherOriginCountryWithTypedNameAndPersistsIt() {
        PricingRequestDetailDto detail = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b.originCountry("อื่นๆ").originCountryOther("เวียดนาม")))),
            salesActor);

        PricingRequestItemDto item = onlyItem(detail);
        assertThat(item.originCountry()).isEqualTo("อื่นๆ");
        assertThat(item.originCountryOther()).isEqualTo("เวียดนาม");
    }

    @Test
    void createDraft_clearsOriginCountryOtherWhenOriginCountryIsNotOther() {
        // A caller that sends a stray originCountryOther alongside a NON-อื่นๆ origin_country
        // (e.g. a rep switched away from อื่นๆ but the field somehow still carried a value) must
        // not have it persisted -- PricingRequestService#resolveItem normalizes it away.
        PricingRequestDetailDto detail = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b.originCountry("ไทย-สต็อก").originCountryOther("เวียดนาม")))),
            salesActor);

        PricingRequestItemDto item = onlyItem(detail);
        assertThat(item.originCountry()).isEqualTo("ไทย-สต็อก");
        assertThat(item.originCountryOther()).isNull();
    }

    // ── GLA-125 item 3: ระยะเวลานำเข้า required, min <= max ─────────────────────────────────

    @Test
    void createDraft_rejectsItemMissingLeadTime() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b.leadTimeMinDays(null).leadTimeMaxDays(null)))),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("ระยะเวลานำเข้า");
            });
    }

    @Test
    void createDraft_rejectsLeadTimeMinGreaterThanMax() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b.leadTimeMinDays(90).leadTimeMaxDays(75)))),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("ต้องไม่มากกว่า");
            });
    }

    // ── GLA-125 item 4: header terms ────────────────────────────────────────────────────────

    @Test
    void createDraft_persistsEveryHeaderTermField() {
        long importUserId = createEmployee("ฝ่ายนำเข้า GLA125 A");
        long salesRepId = createEmployee("พนักงานขาย GLA125 A");
        PricingRequestDetailDto detail = pricingRequestService.createDraft(ticketId,
            createRequestWithHeaderTerms(List.of(completeItem(b -> b)),
                "CREDIT", 30, 15, importUserId, salesRepId, "ขาย", "D01", true),
            salesActor);

        var summary = detail.summary();
        assertThat(summary.paymentTermMode()).isEqualTo("CREDIT");
        assertThat(summary.creditDays()).isEqualTo(30);
        assertThat(summary.validityDays()).isEqualTo(15);
        assertThat(summary.printedByDisplayId()).isEqualTo(importUserId);
        assertThat(summary.salesRepDisplayId()).isEqualTo(salesRepId);
        assertThat(summary.deptCode()).isEqualTo("ขาย");
        assertThat(summary.unitCode()).isEqualTo("D01");
        assertThat(summary.omitContactHonorific()).isTrue();

        // Re-read from the repository directly (not the same in-memory object createDraft
        // returned) to prove these actually reached the database, not just the return value.
        var reread = pricingRequests.findSummary(summary.id()).orElseThrow();
        assertThat(reread.paymentTermMode()).isEqualTo("CREDIT");
        assertThat(reread.creditDays()).isEqualTo(30);
        assertThat(reread.omitContactHonorific()).isTrue();
    }

    @Test
    void createDraft_rejectsCreditModeWithNoCreditDays() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequestWithHeaderTerms(List.of(completeItem(b -> b)),
                "CREDIT", null, null, null, null, null, null, false),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("เครดิต");
            });
    }

    @Test
    void createDraft_rejectsUnrecognisedPaymentTermMode() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequestWithHeaderTerms(List.of(completeItem(b -> b)),
                "NOT_A_REAL_MODE", null, null, null, null, null, null, false),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── GLA-125, second review pass, finding N5: printedByDisplayId/salesRepDisplayId were never
    // validated at all before create/updateDraft/createCustomerChangeRevision wrote them straight
    // to sales.pricing_request. An unknown employee id used to reach the FK constraint and 500;
    // an active-but-ineligible employee (any id at all, sales division or not, holding no
    // can_create_quotation grant) was silently accepted. Both are now rejected with a clean 400 by
    // PricingRequestService#requireEligibleDisplayEmployeeId, which reuses the exact predicate
    // DealQuotationService already enforces for the identically-named fields on a direct-deal
    // quotation (th.co.glr.hr.commission.QuotationDisplayNameEligibility) -- proved here through
    // the real PricingRequestService and real Postgres, not a mocked repository.

    @Test
    void createDraft_rejectsAnUnknownPrintedByDisplayId_withBadRequestNotAServerError() {
        long unknownEmployeeId = 999_999_999L;
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequestWithHeaderTerms(List.of(completeItem(b -> b)),
                null, null, null, unknownEmployeeId, null, null, null, false),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("ผู้พิมพ์");
            });
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.pricing_request WHERE ticket_id = :id", Map.of("id", ticketId), Long.class))
            .as("the rejected create must not have left a row behind")
            .isZero();
    }

    @Test
    void createDraft_rejectsAnActiveEmployeeWithNoSalesDivisionAndNoQuotationGrant_asSalesRepDisplayId() {
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        long ineligibleId = employees.create(new UpsertEmployeeRequest(
            null, null, "พนักงานทั่วไป N5", null, null, null, null, null, null, null,
            "n5-ineligible@glr.co.th", null, "OTHER", "ฝ่ายอื่น", "แผนกอื่น",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));

        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequestWithHeaderTerms(List.of(completeItem(b -> b)),
                null, null, null, null, ineligibleId, null, null, false),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void createDraft_acceptsAnActiveCanCreateQuotationGrantHolder_asPrintedByDisplayId_evenOutsideSalesDivision() {
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        long grantHolderId = employees.create(new UpsertEmployeeRequest(
            null, null, "คิวซี N5", null, null, null, null, null, null, null,
            "n5-grantholder@glr.co.th", null, "QC", "ฝ่าย QC", "แผนก QC",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id",
            Map.of("id", grantHolderId));

        PricingRequestDetailDto detail = pricingRequestService.createDraft(ticketId,
            createRequestWithHeaderTerms(List.of(completeItem(b -> b)),
                null, null, null, grantHolderId, null, null, null, false),
            salesActor);
        assertThat(detail.summary().printedByDisplayId()).isEqualTo(grantHolderId);
    }

    @Test
    void updateDraft_rejectsAnUnknownSalesRepDisplayId_withBadRequestNotAServerError() {
        long draftId = pricingRequestService.createDraft(ticketId,
            createRequestWithHeaderTerms(List.of(completeItem(b -> b)), null, null, null, null, null, null, null, false),
            salesActor).summary().id();
        long unknownEmployeeId = 999_999_998L;

        assertThatThrownBy(() -> pricingRequestService.updateDraft(draftId,
            new UpdatePricingRequestRequest(
                PricingRequestRecipient.BUYER, null, "Buyer Co.", LocalDate.now().plusDays(14),
                null, "THB", "V185 test",
                null, null, null, null, unknownEmployeeId, null, null, false,
                List.of(completeItem(b -> b))),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    @Test
    void createCustomerChangeRevision_copiesHeaderTermsFromWhatTheCallerSends_notInheritedSilentlyFromTheParent() {
        // The parent is created WITHOUT header terms...
        long parentId = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b))), salesActor).summary().id();
        pricingRequestService.submit(parentId, salesActor);

        // ...and the revision explicitly sends its OWN header terms -- this must reach the new
        // row exactly as sent, not be left null (silently "inherited" as null from the parent)
        // and not be dropped by withResolvedItems' reconstruction (the same bug class as Opus
        // review finding #1, one level up: see PricingRequestService#withResolvedItems' own
        // comment on why every overload now passes these through explicitly).
        PricingRequestDetailDto revision = pricingRequestService.createCustomerChangeRevision(parentId,
            new CustomerChangeRevisionRequest(
                "ลูกค้าขอเปลี่ยนเงื่อนไข", java.util.UUID.randomUUID().toString(), PricingRequestRecipient.BUYER,
                null, "Buyer Co.", null, null, "THB", null,
                "ON_DELIVERY", null, 45, null, null, "ขาย", "D02", true,
                List.of(completeItem(b -> b))),
            salesActor);

        var revisionSummary = revision.summary();
        assertThat(revisionSummary.paymentTermMode()).isEqualTo("ON_DELIVERY");
        assertThat(revisionSummary.creditDays()).isNull();
        assertThat(revisionSummary.validityDays()).isEqualTo(45);
        assertThat(revisionSummary.deptCode()).isEqualTo("ขาย");
        assertThat(revisionSummary.unitCode()).isEqualTo("D02");
        assertThat(revisionSummary.omitContactHonorific()).isTrue();
    }

    // ── zero-piece rejection (Opus review finding #2, 2026-09-18) ──────────────────────────

    @Test
    void createDraft_rejectsAnAreaModeLineThatDerivesToZeroPieces() {
        // 0.3 m² at 0.72 m²/piece rounds DOWN to 0 pieces (piecesPerSqm = round2(1/0.72) = 1.39,
        // pieces = round(0.3 * 1.39) = round(0.417) = 0) -- before this fix that 0 would reach
        // sales.pricing_request_item.requested_qty and violate V59's
        // chk_pricing_request_item_qty (requested_qty > 0), surfacing as a raw 500 instead of a
        // caller-fixable 400.
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b
                .quantityMode(WastageCalculator.QUANTITY_MODE_AREA).areaSqm(new BigDecimal("0.3"))
                .sqmPerPiece(new BigDecimal("0.72")).wastageMode(WastageCalculator.WASTAGE_MODE_NONE)
                .piecesPerBox(10).roundToFullBox(false)))),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("0 ชิ้น");
            });
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.pricing_request WHERE ticket_id = :id", Map.of("id", ticketId), Long.class))
            .isZero();
    }

    // ── required-field validation (owner ruling 1) ──────────────────────────────────────────

    @Test
    void createDraft_rejectsItemMissingRequiredFields() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b.thicknessMm(null).sqmPerPiece(null)
                .piecesPerBox(null).color(null).texture(null).size(null)))),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("ความหนา", "ตร.ม./แผ่น", "จำนวนแผ่นต่อกล่อง", "สี", "ผิว", "ขนาด");
            });
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM sales.pricing_request WHERE ticket_id = :id", Map.of("id", ticketId), Long.class))
            .isZero();
    }

    @Test
    void createDraft_rejectsItemMissingQuantity() {
        assertThatThrownBy(() -> pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b.quantityMode(WastageCalculator.QUANTITY_MODE_PIECES)
                .piecesInput(null)))),
            salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> {
                assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
                assertThat(e.getMessage()).contains("จำนวน");
            });
    }

    @Test
    void updateDraft_rejectsReplacementItemMissingRequiredFields() {
        long id = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b))), salesActor).summary().id();

        UpdatePricingRequestRequest update = new UpdatePricingRequestRequest(
            PricingRequestRecipient.BUYER, null, "Buyer Co.", null, null, null, null,
            List.of(completeItem(b -> b.thicknessMm(null))));

        assertThatThrownBy(() -> pricingRequestService.updateDraft(id, update, salesActor))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
    }

    // ── legacy rows (ruling 4): pre-V185 items still read fine ─────────────────────────────

    @Test
    void findItems_legacyRowWithNoNewFields_readsWithEveryNewFieldNull() {
        long id = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b))), salesActor).summary().id();

        // Simulate a row written before V185 ever existed: blank out every new column directly,
        // bypassing the service (which would now refuse to persist an incomplete item).
        jdbc.update("""
            UPDATE sales.pricing_request_item
               SET product_code = NULL, thickness_mm = NULL, sqm_per_piece = NULL,
                   quantity_mode = NULL, area_sqm = NULL, pieces_input = NULL,
                   wastage_mode = NULL, wastage_value = NULL, pieces_per_box = NULL,
                   sqm_per_box = NULL, pieces_before_wastage = NULL, pieces_after_wastage = NULL,
                   boxes = NULL, origin_country = NULL, lead_time_min_days = NULL,
                   lead_time_max_days = NULL
             WHERE pricing_request_id = :id
            """, Map.of("id", id));

        List<PricingRequestItemDto> items = pricingRequests.findItems(id);
        assertThat(items).hasSize(1);
        PricingRequestItemDto legacy = items.get(0);
        assertThat(legacy.productCode()).isNull();
        assertThat(legacy.thicknessMm()).isNull();
        assertThat(legacy.sqmPerPiece()).isNull();
        assertThat(legacy.quantityMode()).isNull();
        assertThat(legacy.piecesPerBox()).isNull();
        assertThat(legacy.boxes()).isNull();
        // round_to_full_box is NOT NULL DEFAULT TRUE (mirrors quotation_item's own V182 column) —
        // a legacy row still reads true, never null.
        assertThat(legacy.roundToFullBox()).isTrue();
        // The original (pre-blank-out) requestedQty/Unit/Basis survive untouched — legacy data,
        // not re-derived by this UPDATE.
        assertThat(legacy.requestedQty()).isNotNull();
        assertThat(legacy.requestedUnit()).isEqualTo("แผ่น");
    }

    // ── factory/variantId round-trip (Opus review finding #1, 2026-09-18) ─────────────────
    //
    // The MODAL bug (PricingRequestCreateModal.jsx's pricingRequestItemInputFromRow never sent
    // `factory`/`variantId`) cannot be exercised from a pure-backend test -- there is no frontend
    // here. What CAN be pinned at this layer is the other half of the contract the frontend fix
    // depends on: that PricingRequestService/-Repository actually PRESERVE these two fields
    // end-to-end through create -> Import gap-fill -> customer-change revision, GIVEN that the
    // caller sends them (which the frontend now does). If a future change reintroduces a NULL
    // here -- e.g. resolveItem dropping the field again, or replaceItems' INSERT missing a column
    // -- this test catches it even though the regression that prompted it lived in the frontend.

    @Test
    void createCustomerChangeRevision_preservesImportAssignedFactoryAndVariantId() {
        // A non-catalog line (no productId) is the case setItemFactory exists for -- Import
        // gap-fills a factory Sales left blank. variantId is set from the start to prove it also
        // survives, even though no editor UI writes it today.
        long id = pricingRequestService.createDraft(ticketId,
            createRequest(List.of(completeItem(b -> b.variantId(4242L)))), salesActor).summary().id();
        pricingRequestService.submit(id, salesActor);

        long importUserId = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc)).create(new UpsertEmployeeRequest(
                null, null, "ฝ่ายนำเข้า V185", null, null, null, null, null, null, null,
                "import-v185@glr.co.th", null, "IMPORT", "แผนกนำเข้า", "แผนกนำเข้า",
                null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
        UserPrincipal importActor = new UserPrincipal(importUserId, "import-v185@glr.co.th", "Import V185",
            "import", importUserId, true, LocalDate.now(), false, null, false);
        pricingRequestService.pickup(id, importActor);

        PricingRequestItemDto beforeFactory = onlyItemById(id);
        assertThat(beforeFactory.factory()).isNull();
        assertThat(beforeFactory.variantId()).isEqualTo(4242L);

        pricingRequestService.setItemFactory(id, beforeFactory.id(),
            new SetItemFactoryRequest("โรงงาน กขค"), importActor);

        PricingRequestItemDto afterFactory = onlyItemById(id);
        assertThat(afterFactory.factory()).isEqualTo("โรงงาน กขค");
        assertThat(afterFactory.variantId()).isEqualTo(4242L);

        // Sales revises the deal. The revision's item request is built the SAME way
        // PricingRequestCreateModal.jsx's itemFromExisting + pricingRequestItemInputFromRow now
        // build it: whatever the CURRENT persisted item holds is sent back, not omitted.
        PricingRequestItemRequest revisionItem = completeItem(b -> b
            .factory(afterFactory.factory()).variantId(afterFactory.variantId()));
        PricingRequestDetailDto revision = pricingRequestService.createCustomerChangeRevision(id,
            new CustomerChangeRevisionRequest("ลูกค้าขอเปลี่ยนแปลง", java.util.UUID.randomUUID().toString(),
                PricingRequestRecipient.BUYER, null, "Buyer Co.", null, null, "THB", null,
                List.of(revisionItem)),
            salesActor);

        PricingRequestItemDto revisionRead = onlyItem(revision);
        assertThat(revisionRead.factory()).isEqualTo("โรงงาน กขค");
        assertThat(revisionRead.variantId()).isEqualTo(4242L);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private PricingRequestItemDto onlyItem(PricingRequestDetailDto detail) {
        List<PricingRequestItemDto> items = pricingRequests.findItems(detail.summary().id());
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private PricingRequestItemDto onlyItemById(long pricingRequestId) {
        List<PricingRequestItemDto> items = pricingRequests.findItems(pricingRequestId);
        assertThat(items).hasSize(1);
        return items.get(0);
    }

    private CreatePricingRequestRequest createRequest(List<PricingRequestItemRequest> items) {
        return new CreatePricingRequestRequest(
            PricingRequestRecipient.BUYER, null, "Buyer Co.", LocalDate.now().plusDays(14),
            null, "THB", "V185 test", java.util.UUID.randomUUID().toString(), items);
    }

    /** Same as {@link #createRequest} but with every GLA-125 header-term field set explicitly. */
    private CreatePricingRequestRequest createRequestWithHeaderTerms(List<PricingRequestItemRequest> items,
            String paymentTermMode, Integer creditDays, Integer validityDays, Long printedByDisplayId,
            Long salesRepDisplayId, String deptCode, String unitCode, Boolean omitContactHonorific) {
        return new CreatePricingRequestRequest(
            PricingRequestRecipient.BUYER, null, "Buyer Co.", LocalDate.now().plusDays(14),
            null, "THB", "V185 test", java.util.UUID.randomUUID().toString(),
            paymentTermMode, creditDays, validityDays, printedByDisplayId, salesRepDisplayId,
            deptCode, unitCode, omitContactHonorific, items);
    }

    /** An active employee IN THE SALES DIVISION, for the header-term display-id fields
     * (printedByDisplayId/salesRepDisplayId) — as of the second review pass (finding N5,
     * 2026-09-19) a valid FK alone is no longer enough, since
     * PricingRequestService#requireEligibleDisplayEmployeeId now enforces the same eligible-union
     * check direct-deal's DealQuotationService already does (see
     * th.co.glr.hr.commission.QuotationDisplayNameEligibility). Uses "SA" (the real
     * DivisionAccessPolicy.SALES_DIVISION_CODE, lower-cased by the eligibility predicate), NOT the
     * "SALES" literal an older version of this helper used — see
     * DealQuotationDisplayNameIntegrationTest's own comment on why "SALES" does not match. */
    private long createEmployee(String nameTh) {
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            Math.abs(nameTh.hashCode()) + "@glr.co.th", null, "SA", "ฝ่ายขาย", "แผนกขาย",
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    /** A fully valid, complete new-form item — every owner-ruling-required field populated, no
     * wastage, no box rounding, PIECES mode with 10 pieces. {@code customize} lets a test null out
     * or override exactly the field(s) it cares about. */
    private PricingRequestItemRequest completeItem(java.util.function.UnaryOperator<ItemBuilder> customize) {
        ItemBuilder builder = new ItemBuilder()
            .brand("Toyota").model("Hilux").color("สี").texture("ผิว").size("60x60")
            .thicknessMm(new BigDecimal("10")).sqmPerPiece(new BigDecimal("0.36"))
            .quantityMode(WastageCalculator.QUANTITY_MODE_PIECES).piecesInput(10)
            .wastageMode(WastageCalculator.WASTAGE_MODE_NONE).piecesPerBox(4)
            .roundToFullBox(true)
            // GLA-125: origin country and lead time are now unconditionally required too.
            .originCountry("ไทย-สต็อก").leadTimeMinDays(3).leadTimeMaxDays(7);
        return customize.apply(builder).build();
    }

    /** Tiny fluent builder over {@link PricingRequestItemRequest}'s canonical (35-arg) constructor
     * — a positional call site that long would be unreadable, and this file needs many small
     * variations of it. */
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
        // Opus review #1: neither has an editor input yet (variantId never has; the OLD form's
        // dedicated โรงงาน field that wrote `factory` was removed 2026-09-18), but both must
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
