package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationItemDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.CancelRequest;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ItemInput;
import th.co.glr.hr.dealquotation.DealQuotationRequests.UpsertDealQuotationRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.NotificationEmailService;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.QuotationRenderer;
import th.co.glr.hr.ticket.QuotationStatus;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;

/**
 * GLA-75 per-item quotation pictures (V170) — the AUTHZ surface, through the REAL
 * {@link DealQuotationService} and {@link DealQuotationRepository} against a REAL Postgres.
 *
 * <p>The rule under test: picture WRITES (upload / change placement / remove) are gated EXACTLY like
 * editing the quotation — {@code sales} on their OWN deal, {@code sales_manager} or a
 * {@code can_create_quotation} grant on any deal — and only while the quotation is DRAFT. The
 * READ is gated like viewing the quotation.
 *
 * <p>Every negative case is written WRONG-WAY-ROUND (CLAUDE.md "Permission changes must ship
 * evidence"): it asserts the caller CANNOT, and then reads the database back to prove nothing was
 * stored or changed — a 403 thrown after a write had already landed would otherwise pass.
 */
class DealQuotationPictureIntegrationTest extends AbstractPostgresIntegrationTest {
    private DealQuotationService quotationService;
    private CustomerRepository customers;

    private long salesRepId;
    private long qcUserId;
    private UserPrincipal salesActor;
    private UserPrincipal otherSalesActor;
    private UserPrincipal salesManagerActor;
    private UserPrincipal ceoActor;
    private UserPrincipal accountActor;
    private UserPrincipal qcActor;
    private UserPrincipal employeeActor;
    private long ticketId;
    private long otherTicketId;
    private CustomerDto customer;

    @BeforeEach
    void wire() {
        TicketRepository tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        customers = new CustomerRepository(jdbc);
        ContactRepository contacts = new ContactRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        TicketService ticketService = new TicketService(tickets, notifications, new ObjectMapper(), customers,
            new QuotationRenderer(), null, new EmployeeAuthRepository(jdbc));
        NotificationEmailService approvalMailer =
            new NotificationEmailService(new NoOpMailer(), new BrandAssets(), "", "", "https://portal.test");
        quotationService = new DealQuotationService(new DealQuotationRepository(jdbc), tickets, customers, contacts,
            notifications, approvalMailer, new QuotationRenderer(), new EmployeeAuthRepository(jdbc),
            new EmployeeSignatureRepository(jdbc), "https://portal.test",
            // app.quotation.bank-block-line1..3 (#929) — empty: the bank block is not under test here.
            "", "", "");

        salesRepId = createEmployee(employees, "พนักงานขาย รูป", "sales-pic@glr.co.th", "SALES", "แผนกขาย", null);
        long otherSalesId = createEmployee(employees, "พนักงานขาย อื่นรูป", "sales-pic-other@glr.co.th", "SALES", "แผนกขาย", null);
        long salesManagerId = createEmployee(employees, "ผู้จัดการฝ่ายขาย รูป", "sm-pic@glr.co.th", "SALES", "ฝ่ายขาย",
            "ผู้จัดการฝ่ายขาย");
        long ceoId = createEmployee(employees, "ผู้บริหาร รูป", "ceo-pic@glr.co.th", "MD", "ผู้บริหาร", "กรรมการผู้จัดการ");
        long accountId = createEmployee(employees, "บัญชี รูป", "acct-pic@glr.co.th", "ACCT", "ฝ่ายบัญชี", null);
        qcUserId = createEmployee(employees, "QC รูป", "qc-pic@glr.co.th", "QC", "ฝ่าย QC", null);
        long employeeId = createEmployee(employees, "พนักงาน รูป", "emp-pic@glr.co.th", "OTHER", "ฝ่ายอื่น", null);

        salesActor = actor(salesRepId, "sales");
        otherSalesActor = actor(otherSalesId, "sales");
        salesManagerActor = actor(salesManagerId, "sales_manager");
        ceoActor = actor(ceoId, "ceo");
        accountActor = actor(accountId, "account");
        qcActor = actor(qcUserId, "qc");
        employeeActor = actor(employeeId, "employee");

        customer = customers.create("บริษัท รูปภาพ จำกัด", "0100000000777", "1 ถนนรูป", "สนญ.", "02-777-7777");
        ProjectDto project = projects.create(customer.id(), "โครงการรูปภาพ");
        ContactDto contact = contacts.create(customer.id(), "สมศรี", "มีรูป", "จัดซื้อ", "somsri@customer.test",
            "081-777-0000");
        ticketId = ticketService.create(new CreateTicketRequest("ดีลรูป", "NORMAL", customer.name(), customer.id(),
            project.id(), contact.id(), null, null, null), salesActor).summary().id();
        otherTicketId = ticketService.create(new CreateTicketRequest("ดีลรูป อื่น", "NORMAL", customer.name(),
            customer.id(), project.id(), contact.id(), null, null, null), otherSalesActor).summary().id();
    }

    // ── the happy path the wrong-way cases are measured against ─────────────────────────

    @Test
    void owner_uploadsAPicture_toADraftItem_andTheDtoCarriesAFlagPlacementAndUrl_neverBytes() {
        DealQuotationDto q = draft(ticketId, salesActor, 2);
        long itemId = q.items().get(0).id();
        byte[] png = png(320, 160);

        DealQuotationDto after = quotationService.uploadItemPicture(q.id(), itemId, file("a.png", png), null, salesActor);
        DealQuotationItemDto item = after.items().get(0);
        assertThat(item.hasPicture()).isTrue();
        assertThat(item.picturePlacement()).as("owner's default").isEqualTo("BELOW");
        assertThat(item.pictureUrl()).isEqualTo("/api/deal-quotations/" + q.id() + "/items/" + itemId + "/picture");
        assertThat(after.items().get(1).hasPicture()).isFalse();
        assertThat(after.items().get(1).pictureUrl()).isNull();

        var stored = quotationService.getItemPicture(q.id(), itemId, salesActor);
        assertThat(stored.mimeType()).isEqualTo("image/png");
        assertThat(stored.image()).isEqualTo(png);

        assertThat(quotationService.setItemPicturePlacement(q.id(), itemId, "BESIDE", salesActor)
            .items().get(0).picturePlacement()).isEqualTo("BESIDE");

        // The stored bytes reach the rendered document: the XLS (the row plan BOTH PDF engines
        // print) embeds exactly this picture. A single-picture quotation is the case a dropped
        // first row of the bytes query would lose entirely.
        assertThat(embeddedPictureCount(quotationService.renderXlsx(q.id(), salesActor), png))
            .as("the item's picture is embedded in the rendered XLS").isEqualTo(1);
        byte[] pdf = quotationService.renderPdf(q.id(), salesActor);
        assertThat(new String(pdf, 0, 5, StandardCharsets.US_ASCII)).isEqualTo("%PDF-");

        DealQuotationDto removed = quotationService.removeItemPicture(q.id(), itemId, salesActor);
        assertThat(removed.items().get(0).hasPicture()).isFalse();
        assertThat(pictureRows()).as("the orphaned picture row is deleted").isZero();
    }

    // ── WRITE gate, wrong-way-round ──────────────────────────────────────────────────────

    @Test
    void aRepWhoDoesNotOwnTheDeal_cannotUpload_changePlacement_orRemove_andNothingChanges() {
        DealQuotationDto q = draft(ticketId, salesActor, 1);
        long itemId = q.items().get(0).id();
        quotationService.uploadItemPicture(q.id(), itemId, file("a.png", png(50, 50)), "BELOW", salesActor);
        long before = pictureRows();

        assertStatus(HttpStatus.FORBIDDEN,
            () -> quotationService.uploadItemPicture(q.id(), itemId, file("b.png", png(60, 60)), "BESIDE", otherSalesActor));
        assertStatus(HttpStatus.FORBIDDEN,
            () -> quotationService.setItemPicturePlacement(q.id(), itemId, "BESIDE", otherSalesActor));
        assertStatus(HttpStatus.FORBIDDEN, () -> quotationService.removeItemPicture(q.id(), itemId, otherSalesActor));

        assertThat(pictureRows()).as("no picture row written by a refused upload").isEqualTo(before);
        assertThat(storedLink(itemId)).as("link and placement untouched").isEqualTo("BELOW");
        assertThat(quotationService.getItemPicture(q.id(), itemId, salesActor).image()).isEqualTo(png(50, 50));
    }

    @Test
    void nonSalesRoles_cannotWritePictures_evenWhereTheyMayViewOrApproveTheQuotation() {
        DealQuotationDto q = draft(ticketId, salesActor, 1);
        long itemId = q.items().get(0).id();
        long before = pictureRows();
        // ceo APPROVES quotations and account VIEWS them; neither edits. qc has no grant here.
        for (UserPrincipal actor : List.of(ceoActor, accountActor, qcActor, employeeActor)) {
            assertStatus(HttpStatus.FORBIDDEN,
                () -> quotationService.uploadItemPicture(q.id(), itemId, file("a.png", png(40, 40)), null, actor));
            assertStatus(HttpStatus.FORBIDDEN, () -> quotationService.removeItemPicture(q.id(), itemId, actor));
            assertStatus(HttpStatus.FORBIDDEN,
                () -> quotationService.setItemPicturePlacement(q.id(), itemId, "BELOW", actor));
        }
        assertThat(pictureRows()).isEqualTo(before);
        assertThat(storedLink(itemId)).isNull();
    }

    @Test
    void theEditRulesPositiveHalf_salesManagerOnAnyDeal_andAGrantedQcOnAnyDeal_mayWrite() {
        DealQuotationDto q = draft(ticketId, salesActor, 1);
        long itemId = q.items().get(0).id();
        quotationService.uploadItemPicture(q.id(), itemId, file("a.png", png(40, 40)), null, salesManagerActor);
        assertThat(storedLink(itemId)).isEqualTo("BELOW");

        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id", Map.of("id", qcUserId));
        quotationService.setItemPicturePlacement(q.id(), itemId, "BESIDE", qcActor);
        assertThat(storedLink(itemId)).isEqualTo("BESIDE");
    }

    @Test
    void aQuotationThatIsNotDraft_refusesEveryPictureWrite_withA409_andKeepsItsPicture() {
        DealQuotationDto q = draft(ticketId, salesActor, 1);
        long itemId = q.items().get(0).id();
        quotationService.uploadItemPicture(q.id(), itemId, file("a.png", png(70, 35)), "BELOW", salesActor);
        quotationService.submit(q.id(), salesActor);
        long before = pictureRows();

        // PENDING_APPROVAL — the owner herself, and the sales_manager who may edit any deal.
        for (UserPrincipal actor : List.of(salesActor, salesManagerActor)) {
            assertStatus(HttpStatus.CONFLICT,
                () -> quotationService.uploadItemPicture(q.id(), itemId, file("b.png", png(30, 30)), null, actor));
            assertStatus(HttpStatus.CONFLICT, () -> quotationService.removeItemPicture(q.id(), itemId, actor));
            assertStatus(HttpStatus.CONFLICT,
                () -> quotationService.setItemPicturePlacement(q.id(), itemId, "BESIDE", actor));
        }
        // APPROVED.
        quotationService.approve(q.id(), new ApproveRequest(null), salesManagerActor);
        assertStatus(HttpStatus.CONFLICT,
            () -> quotationService.uploadItemPicture(q.id(), itemId, file("b.png", png(30, 30)), null, salesActor));
        assertStatus(HttpStatus.CONFLICT, () -> quotationService.removeItemPicture(q.id(), itemId, salesActor));

        assertThat(pictureRows()).isEqualTo(before);
        assertThat(storedLink(itemId)).as("the approved document keeps what it was approved with").isEqualTo("BELOW");
        assertThat(quotationService.getItemPicture(q.id(), itemId, salesActor).image()).isEqualTo(png(70, 35));
    }

    @Test
    void aCancelledDraft_refusesPictureWrites() {
        DealQuotationDto q = draft(ticketId, salesActor, 1);
        long itemId = q.items().get(0).id();
        quotationService.cancel(q.id(), new CancelRequest("ยกเลิก"), salesActor);
        assertStatus(HttpStatus.CONFLICT,
            () -> quotationService.uploadItemPicture(q.id(), itemId, file("a.png", png(30, 30)), null, salesActor));
        assertThat(storedLink(itemId)).isNull();
    }

    @Test
    void anItemOfAnotherQuotation_cannotBeReachedThroughThisQuotationsUrl() {
        DealQuotationDto mine = draft(ticketId, salesActor, 1);
        DealQuotationDto theirs = draft(otherTicketId, otherSalesActor, 1);
        long theirItem = theirs.items().get(0).id();
        quotationService.uploadItemPicture(theirs.id(), theirItem, file("t.png", png(90, 30)), null, otherSalesActor);

        // The caller may edit `mine`, but the item id belongs to `theirs`.
        assertStatus(HttpStatus.NOT_FOUND,
            () -> quotationService.uploadItemPicture(mine.id(), theirItem, file("a.png", png(30, 30)), null, salesActor));
        assertStatus(HttpStatus.NOT_FOUND, () -> quotationService.removeItemPicture(mine.id(), theirItem, salesActor));
        assertStatus(HttpStatus.NOT_FOUND, () -> quotationService.getItemPicture(mine.id(), theirItem, salesActor));
        assertThat(storedLink(theirItem)).as("their picture untouched").isEqualTo("BELOW");
    }

    // ── READ gate, wrong-way-round ───────────────────────────────────────────────────────

    @Test
    void thePictureRead_isGatedLikeViewingTheQuotation() {
        DealQuotationDto q = draft(ticketId, salesActor, 1);
        long itemId = q.items().get(0).id();
        quotationService.uploadItemPicture(q.id(), itemId, file("a.png", png(40, 20)), null, salesActor);

        assertStatus(HttpStatus.FORBIDDEN, () -> quotationService.getItemPicture(q.id(), itemId, otherSalesActor));
        assertStatus(HttpStatus.FORBIDDEN, () -> quotationService.getItemPicture(q.id(), itemId, employeeActor));
        assertStatus(HttpStatus.FORBIDDEN, () -> quotationService.getItemPicture(q.id(), itemId, qcActor));
        // The view roles may read it.
        assertThat(quotationService.getItemPicture(q.id(), itemId, salesManagerActor).image()).isEqualTo(png(40, 20));
        assertThat(quotationService.getItemPicture(q.id(), itemId, accountActor).mimeType()).isEqualTo("image/png");
    }

    // ── magic bytes, through the service ────────────────────────────────────────────────

    @Test
    void aPngNamedTextFile_isRejected_andNothingIsStored() {
        DealQuotationDto q = draft(ticketId, salesActor, 1);
        long itemId = q.items().get(0).id();
        MockMultipartFile fake = new MockMultipartFile("file", "tile.png", "image/png",
            "not a picture".getBytes(StandardCharsets.UTF_8));
        assertStatus(HttpStatus.BAD_REQUEST, () -> quotationService.uploadItemPicture(q.id(), itemId, fake, null, salesActor));
        assertThat(pictureRows()).isZero();
        assertThat(storedLink(itemId)).isNull();
    }

    // ── draft save (full item replace) and revisions ────────────────────────────────────

    @Test
    void aDraftSave_keepsThePictureOfEveryItemSentBackWithItsId_andDropsTheRest() {
        DealQuotationDto q = draft(ticketId, salesActor, 2);
        long first = q.items().get(0).id();
        long second = q.items().get(1).id();
        quotationService.uploadItemPicture(q.id(), first, file("a.png", png(60, 30)), "BESIDE", salesActor);
        quotationService.uploadItemPicture(q.id(), second, file("b.png", png(30, 60)), "BELOW", salesActor);

        // Item 1 is sent back with its id (reordered to the END); item 2 is sent without one.
        DealQuotationDto saved = quotationService.update(q.id(), upsert(List.of(
            withId(sampleItem("200.00", 3), null), withId(sampleItem("100.00", 10), first))), salesActor);

        DealQuotationItemDto keptRow = saved.items().get(1);
        assertThat(keptRow.id()).as("the save minted new item ids").isNotEqualTo(first);
        assertThat(keptRow.hasPicture()).as("item sent back with its id keeps its picture; stored rows: %s",
            jdbc.queryForList("SELECT quotation_item_id, seq, picture_id, picture_placement FROM sales.quotation_item"
                + " WHERE quotation_id = :id ORDER BY seq", Map.of("id", q.id()))).isTrue();
        assertThat(keptRow.picturePlacement()).isEqualTo("BESIDE");
        assertThat(quotationService.getItemPicture(q.id(), keptRow.id(), salesActor).image()).isEqualTo(png(60, 30));
        assertThat(saved.items().get(0).hasPicture()).as("item sent without an id is a new item").isFalse();
        assertThat(pictureRows()).as("the dropped picture's row is deleted").isEqualTo(1);
    }

    @Test
    void aDraftSave_cannotAttachAnotherQuotationsPicture_bySendingItsItemId() {
        DealQuotationDto mine = draft(ticketId, salesActor, 1);
        DealQuotationDto theirs = draft(otherTicketId, otherSalesActor, 1);
        long theirItem = theirs.items().get(0).id();
        quotationService.uploadItemPicture(theirs.id(), theirItem, file("t.png", png(90, 30)), null, otherSalesActor);

        DealQuotationDto saved = quotationService.update(mine.id(),
            upsert(List.of(withId(sampleItem("100.00", 10), theirItem))), salesActor);
        assertThat(saved.items().get(0).hasPicture()).isFalse();
        assertThat(storedLink(theirItem)).isEqualTo("BELOW");
    }

    @Test
    void aRevision_copiesItsParentsPictures_andChangingTheChildsPictureLeavesTheParentsAlone() {
        DealQuotationDto q = draft(ticketId, salesActor, 2);
        long parentItem = q.items().get(1).id();
        byte[] png = png(120, 80);
        quotationService.uploadItemPicture(q.id(), parentItem, file("a.png", png), "BESIDE", salesActor);
        quotationService.submit(q.id(), salesActor);
        quotationService.approve(q.id(), new ApproveRequest(null), salesManagerActor);

        DealQuotationDto child = quotationService.createRevision(q.id(), salesActor);
        assertThat(child.docStatus()).isEqualTo(QuotationStatus.DRAFT);
        DealQuotationItemDto childItem = child.items().get(1);
        assertThat(childItem.hasPicture()).isTrue();
        assertThat(childItem.picturePlacement()).isEqualTo("BESIDE");
        assertThat(child.items().get(0).hasPicture()).isFalse();
        assertThat(quotationService.getItemPicture(child.id(), childItem.id(), salesActor).image()).isEqualTo(png);

        // Remove it on the child: the approved parent must still print it.
        quotationService.removeItemPicture(child.id(), childItem.id(), salesActor);
        assertThat(quotationService.getItemPicture(q.id(), parentItem, salesActor).image()).isEqualTo(png);
        assertThat(pictureRows()).as("still referenced by the parent, so not deleted").isEqualTo(1);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────────────

    private DealQuotationDto draft(long ticket, UserPrincipal creator, int items) {
        List<ItemInput> inputs = new java.util.ArrayList<>();
        for (int i = 0; i < items; i++) inputs.add(sampleItem("100.00", 10 + i));
        return quotationService.create(ticket, upsert(inputs), creator);
    }

    private static int embeddedPictureCount(byte[] xls, byte[] picture) {
        try (var wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(new java.io.ByteArrayInputStream(xls))) {
            var sheet = (org.apache.poi.hssf.usermodel.HSSFSheet) wb.getSheetAt(0);
            int n = 0;
            for (var shape : sheet.getDrawingPatriarch().getChildren()) {
                if (shape instanceof org.apache.poi.hssf.usermodel.HSSFPicture pic
                    && java.util.Arrays.equals(pic.getPictureData().getData(), picture)) n++;
            }
            return n;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private long pictureRows() {
        return jdbc.queryForObject("SELECT count(*) FROM sales.quotation_item_picture", Map.of(), Long.class);
    }

    /** The stored placement of this item (null when it has no picture). */
    private String storedLink(long itemId) {
        return jdbc.queryForObject("SELECT picture_placement FROM sales.quotation_item WHERE quotation_item_id = :id",
            Map.of("id", itemId), String.class);
    }

    private static void assertStatus(HttpStatus status, Runnable action) {
        assertThatThrownBy(action::run).isInstanceOf(ApiException.class).hasFieldOrPropertyWithValue("status", status);
    }

    private static MockMultipartFile file(String name, byte[] bytes) {
        return new MockMultipartFile("file", name, "image/png", bytes);
    }

    private UpsertDealQuotationRequest upsert(List<ItemInput> items) {
        return new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30, null, items);
    }

    private static ItemInput sampleItem(String unitPrice, int pieces) {
        return new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), null, WastageCalculator.QUANTITY_MODE_PIECES, null, pieces,
            WastageCalculator.WASTAGE_MODE_NONE, null, 1, new BigDecimal(unitPrice), BigDecimal.ZERO, "ไทย-สต็อก",
            30, 45, null);
    }

    private static ItemInput withId(ItemInput in, Long id) {
        return new ItemInput(in.locationLabel(), in.catalogPriceId(), in.productCode(), in.brand(), in.model(),
            in.color(), in.texture(), in.sizeText(), in.thicknessMm(), in.sqmPerPiece(), in.quantityMode(),
            in.areaSqm(), in.piecesInput(), in.wastageMode(), in.wastageValue(), in.piecesPerBox(), in.unitPrice(),
            in.discountPct(), in.originCountry(), in.leadTimeMinDays(), in.leadTimeMaxDays(), in.itemNotes(),
            in.lineType(), in.description(), in.quantity(), in.unit(), in.specialPriceSqm(), in.directNetPrice(),
            in.adjustmentPct(), in.adjustmentDeadline(), in.adjustmentAmount(), id);
    }

    /** Deterministic per size, so two calls with the same size are byte-equal. */
    private static byte[] png(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(30, 120, 170));
        g.fillRect(0, 0, width, height);
        g.setColor(Color.WHITE);
        for (int x = 0; x < width; x += 10) g.drawLine(x, 0, x, height);
        g.dispose();
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private long createEmployee(EmployeeRepository employees, String nameTh, String email,
                                String divisionSourceCode, String divisionNameTh, String positionTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            positionTh, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }

    private static final class NoOpMailer implements Mailer {
        @Override public void send(String to, String subject, String body) {}
        @Override public void sendHtml(String to, String subject, String htmlBody, String textBody,
                                       List<InlineImage> inlineImages) {}
        @Override public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {}
        @Override public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {}
    }
}
