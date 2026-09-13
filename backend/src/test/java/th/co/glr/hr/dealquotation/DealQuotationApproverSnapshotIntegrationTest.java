package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.poi.hssf.usermodel.HSSFPicture;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.activity.ActivityLogRepository;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.catalog.CatalogRepository;
import th.co.glr.hr.customer.ContactDto;
import th.co.glr.hr.customer.ContactRepository;
import th.co.glr.hr.customer.CustomerDto;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customer.ProjectDto;
import th.co.glr.hr.customer.ProjectRepository;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
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
 * V175 — the approver's printed names and signature image are FROZEN at approval (owner ruling
 * 2026-09-13: "Already-sent quotations never change afterwards"). Real Postgres, through the real
 * {@link DealQuotationService} and {@link DealQuotationRepository}, rendering the real workbook.
 *
 * <p>Written wrong-way-round: every test changes the approver AFTER approval (replace the
 * signature, delete it, rename the employee) and asserts the document does NOT follow. The
 * signature assertion compares the picture bytes embedded in the rendered XLS against the exact
 * stored bytes, so "some picture is present" can never pass for the wrong picture.
 *
 * <p>No authz surface is touched here — who may approve/render is unchanged; this is about which
 * VALUES an already-authorised render prints. Signature images are synthetic drawings only (the
 * repository is public).
 */
class DealQuotationApproverSnapshotIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String MIGRATION = "db/migration/V175__deal_quotation_approver_snapshot.sql";

    private DealQuotationService quotationService;
    private EmployeeSignatureService signatureService;
    private EmployeeSignatureRepository signatureRepository;

    private long managerId;
    private UserPrincipal salesActor;
    private UserPrincipal managerActor;
    private long ticketId;

    @BeforeEach
    void wire() {
        TicketRepository tickets = new TicketRepository(jdbc);
        NotificationRepository notifications = new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP);
        CustomerRepository customers = new CustomerRepository(jdbc);
        ContactRepository contacts = new ContactRepository(jdbc);
        ProjectRepository projects = new ProjectRepository(jdbc);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        TicketService ticketService = new TicketService(tickets, notifications, new ObjectMapper(), customers,
            new QuotationRenderer(), null, new EmployeeAuthRepository(jdbc));
        signatureRepository = new EmployeeSignatureRepository(jdbc);
        Mailer noMail = new Mailer() {
            @Override
            public void send(String to, String subject, String body) {}

            @Override
            public void sendHtml(String to, String subject, String htmlBody, String textBody,
                                 List<InlineImage> inlineImages) {}

            @Override
            public void sendWithAttachment(String to, String subject, String body, String filename, byte[] bytes) {}

            @Override
            public void sendWithAttachments(String to, String subject, String body, List<Attachment> attachments) {}
        };
        quotationService = new DealQuotationService(
            new DealQuotationRepository(jdbc, new CatalogRepository(jdbc)), tickets, customers, contacts,
            notifications, new NotificationEmailService(noMail, new BrandAssets(), "", "", "https://portal.test"),
            new QuotationRenderer(), new EmployeeAuthRepository(jdbc), signatureRepository,
            new CatalogRepository(jdbc), "https://portal.test", "", "", "");
        signatureService = new EmployeeSignatureService(signatureRepository, new ActivityLogRepository(jdbc));

        long salesId = createEmployee(employees, "พนักงานขาย สแนป", "sales-snap@glr.co.th", "SALES", "แผนกขาย", null);
        managerId = createEmployee(employees, "ผู้จัดการ สแนป", "manager-snap@glr.co.th", "SALES", "ฝ่ายขาย",
            "ผู้จัดการฝ่ายขาย");
        renameApprover("สมชาย", "อนุมัติ", "Somchai", "Approver");
        salesActor = actor(salesId, "sales");
        managerActor = actor(managerId, "sales_manager");

        CustomerDto customer = customers.create("บริษัท สแนปช็อต จำกัด", "0100000000175", "175 ถนนทดสอบ", "สนญ.",
            "02-175-0175");
        ProjectDto project = projects.create(customer.id(), "โครงการ สแนปช็อต");
        ContactDto contact = contacts.create(customer.id(), "สมหญิง", "ใจดี", "จัดซื้อ", "c@customer.test",
            "081-000-0175");
        ticketId = ticketService.create(new CreateTicketRequest("ดีล สแนปช็อต", "NORMAL", customer.name(),
            customer.id(), project.id(), contact.id(), null, null, null), salesActor).summary().id();
    }

    // ─────────────────────────────────────────────────────────────────────────────────────

    @Test
    void approvedQuotation_keepsSignatureAAndOldNames_afterReplaceDeleteAndRename() throws Exception {
        signatureService.upload(managerId, png(signatureA()), managerActor);
        byte[] storedA = signatureRepository.find(managerId).orElseThrow().image();
        DealQuotationDto approved = createSubmittedApproved();
        assertThat(approved.approverHasSignature()).isTrue();
        assertThat(approved.approvedByName()).isEqualTo("สมชาย อนุมัติ");
        assertThat(approved.approvedByNameEn()).isEqualTo("Somchai Approver");
        assertThat(signatureBytes(render(approved))).as("sanity: A printed at approval").isEqualTo(storedA);

        // 1. Replace A with B.
        signatureService.upload(managerId, png(signatureB()), managerActor);
        byte[] storedB = signatureRepository.find(managerId).orElseThrow().image();
        assertThat(storedB).as("sanity: B really is a different image").isNotEqualTo(storedA);
        byte[] afterReplace = signatureBytes(render(approved));
        assertThat(afterReplace).as("still A after the approver replaced the signature").isEqualTo(storedA);
        assertThat(afterReplace).isNotEqualTo(storedB);

        // 2. Delete the signature outright.
        signatureService.delete(managerId, managerActor);
        assertThat(signatureRepository.find(managerId)).isEmpty();
        assertThat(signatureBytes(render(approved))).as("still A after the signature was deleted").isEqualTo(storedA);
        assertThat(get(approved).approverHasSignature()).isTrue();

        // 3. Rename the employee (Thai and English).
        renameApprover("เปลี่ยน", "ชื่อใหม่", "Renamed", "Person");
        DealQuotationDto reread = get(approved);
        assertThat(reread.approvedByName()).isEqualTo("สมชาย อนุมัติ");
        assertThat(reread.approvedByNameEn()).isEqualTo("Somchai Approver");
        byte[] xls = render(approved);
        assertThat(signatureNames(xls)).contains("(สมชาย อนุมัติ)").doesNotContain("เปลี่ยน");
        assertThat(signatureBytes(xls)).isEqualTo(storedA);
    }

    @Test
    void draftAndPending_haveNoSignatureImageOrApprover_andNoSnapshotRow() throws Exception {
        signatureService.upload(managerId, png(signatureA()), managerActor);
        DealQuotationDto draft = quotationService.create(ticketId, upsertRequest(), salesActor);
        assertThat(draft.approverHasSignature()).isFalse();
        assertThat(draft.approvedByName()).isNull();
        assertThat(signatureBytes(render(draft))).isNull();

        DealQuotationDto pending = quotationService.submit(draft.id(), salesActor);
        assertThat(pending.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(pending.approverHasSignature()).isFalse();
        assertThat(pending.approvedByName()).isNull();
        assertThat(signatureBytes(render(pending))).isNull();
        assertThat(snapshotRows(draft.id())).isZero();
    }

    @Test
    void revisionChild_approvedAfterTheChange_printsB_whileSupersededParentKeepsA() throws Exception {
        signatureService.upload(managerId, png(signatureA()), managerActor);
        byte[] storedA = signatureRepository.find(managerId).orElseThrow().image();
        DealQuotationDto parent = createSubmittedApproved();

        signatureService.upload(managerId, png(signatureB()), managerActor);
        byte[] storedB = signatureRepository.find(managerId).orElseThrow().image();
        renameApprover("เปลี่ยน", "ชื่อใหม่", "Renamed", "Person");

        DealQuotationDto child = quotationService.createRevision(parent.id(), salesActor);
        assertThat(signatureBytes(render(child))).as("an unapproved revision carries no signature").isNull();
        quotationService.submit(child.id(), salesActor);
        DealQuotationDto approvedChild = quotationService.approve(child.id(), new ApproveRequest(null), managerActor);

        assertThat(approvedChild.approvedByName()).isEqualTo("เปลี่ยน ชื่อใหม่");
        assertThat(signatureBytes(render(approvedChild))).isEqualTo(storedB);

        DealQuotationDto supersededParent = get(parent);
        assertThat(supersededParent.docStatus()).isEqualTo(QuotationStatus.SUPERSEDED);
        assertThat(supersededParent.approvedByName()).isEqualTo("สมชาย อนุมัติ");
        assertThat(signatureBytes(render(supersededParent))).isEqualTo(storedA);
    }

    /** The production case: approved with NO signature on file. The snapshot is still taken (names
     * frozen, image NULL), so a signature uploaded afterwards must NOT appear on it. */
    @Test
    void approvedWithoutASignature_staysUnsigned_evenAfterOneIsUploaded() throws Exception {
        DealQuotationDto approved = createSubmittedApproved();
        assertThat(snapshotRows(approved.id())).as("snapshot taken even with no signature").isEqualTo(1);
        assertThat(approved.approverHasSignature()).isFalse();

        signatureService.upload(managerId, png(signatureA()), managerActor);
        renameApprover("เปลี่ยน", "ชื่อใหม่", "Renamed", "Person");

        DealQuotationDto reread = get(approved);
        assertThat(reread.approverHasSignature()).isFalse();
        assertThat(reread.approvedByName()).isEqualTo("สมชาย อนุมัติ");
        assertThat(signatureBytes(render(approved))).isNull();
    }

    /**
     * Upload-time trim (owner-approved 2026-09-13): a signature drawn small in the middle of a big
     * transparent canvas is stored cropped to its ink, and the printed picture follows the CROPPED
     * shape. The canvas is 1000x400 (2.5:1) but the ink is 300x40, so the stored image is
     * 312x52 (6:1, margin round(300*0.02)=6); the anchored picture's printed box must read as the
     * wide 6:1 ink, not the 2.5:1 frame the renderer used to shrink the ink into.
     */
    @Test
    void trimmedSignature_drivesTheStoredImageAndThePrintedAspectRatio() throws Exception {
        BufferedImage canvas = new BufferedImage(1000, 400, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = canvas.createGraphics();
        g.setColor(new Color(20, 30, 120));
        g.fillRect(350, 180, 300, 40);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(canvas, "png", out);
        signatureService.upload(managerId, png(out.toByteArray()), managerActor);

        BufferedImage stored = ImageIO.read(new ByteArrayInputStream(
            signatureRepository.find(managerId).orElseThrow().image()));
        assertThat(stored.getWidth()).isEqualTo(312);
        assertThat(stored.getHeight()).isEqualTo(52);

        byte[] xls = render(createSubmittedApproved());
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            Sheet sheet = sheet(wb);
            int labels = labelsRow(sheet);
            HSSFPicture picture = null;
            for (var shape : ((HSSFSheet) sheet).getDrawingPatriarch().getChildren()) {
                if (shape instanceof HSSFPicture pic && pic.getClientAnchor().getRow1() <= labels
                    && pic.getClientAnchor().getRow2() >= labels && pic.getClientAnchor().getRow2() <= labels + 1) {
                    picture = pic;
                }
            }
            assertThat(picture).as("signature picture anchored on the rule").isNotNull();
            BufferedImage embedded = ImageIO.read(new ByteArrayInputStream(picture.getPictureData().getData()));
            assertThat(embedded.getWidth()).isEqualTo(312);
            assertThat(embedded.getHeight()).isEqualTo(52);

            var a = picture.getClientAnchor();
            double widthPx = 0;
            for (int c = a.getCol1(); c < a.getCol2(); c++) {
                widthPx += sheet.getColumnWidthInPixels(c);
            }
            widthPx += -a.getDx1() / 1023.0 * sheet.getColumnWidthInPixels(a.getCol1())
                + a.getDx2() / 1023.0 * sheet.getColumnWidthInPixels(a.getCol2());
            double heightPt = 0;
            for (int r = a.getRow1(); r < a.getRow2(); r++) {
                heightPt += rowHeightPt(sheet, r);
            }
            heightPt += -a.getDy1() / 255.0 * rowHeightPt(sheet, a.getRow1())
                + a.getDy2() / 255.0 * rowHeightPt(sheet, a.getRow2());
            double printedRatio = widthPx / (heightPt * 96.0 / 72.0);
            // Loose bounds: anchor pixels are not exactly physical pixels (see
            // QuotationRenderer#fontPixelToAnchorPixelScale), but 6:1 vs 2.5:1 is unmistakable.
            assertThat(printedRatio).as("printed box follows the trimmed 6:1 ink, not the 2.5:1 canvas")
                .isBetween(4.0, 9.0);
        }
    }

    private static double rowHeightPt(Sheet sheet, int r) {
        var row = sheet.getRow(r);
        return row != null ? row.getHeightInPoints() : sheet.getDefaultRowHeightInPoints();
    }

    // ── V175 BACKFILL (owner ruling 2026-09-13, "Freeze them unsigned.") ─────────────────────

    /**
     * A quotation approved BEFORE V175 has no snapshot row — simulated here by deleting the one
     * approve() wrote. Without a snapshot it follows the LIVE values (the pre-V175 behaviour);
     * once V175 runs (the whole shipped file), it is frozen at the values current AT MIGRATION
     * TIME, and a later change no longer reaches it.
     */
    @Test
    void backfill_freezesPreV175ApprovalsAtBackfillTimeValues_andSkipsDraftAndPending() throws Exception {
        signatureService.upload(managerId, png(signatureA()), managerActor);
        DealQuotationDto legacy = createSubmittedApproved();
        DealQuotationDto pending = quotationService.submit(
            quotationService.create(ticketId, upsertRequest(), salesActor).id(), salesActor);
        DealQuotationDto draft = quotationService.create(ticketId, upsertRequest(), salesActor);
        jdbc.update("DELETE FROM sales.quotation_approver_snapshot WHERE quotation_id = :id",
            Map.of("id", legacy.id()));

        // Interim (no snapshot): the live values, exactly as before V175.
        signatureService.upload(managerId, png(signatureB()), managerActor);
        byte[] storedB = signatureRepository.find(managerId).orElseThrow().image();
        renameApprover("ณ วันย้าย", "ข้อมูล", "Migration", "Time");
        assertThat(get(legacy).approvedByName()).isEqualTo("ณ วันย้าย ข้อมูล");
        assertThat(signatureBytes(render(legacy))).as("unsnapshotted row follows live").isEqualTo(storedB);

        runBackfill();
        runBackfill(); // idempotent

        assertThat(snapshotRows(legacy.id())).isEqualTo(1);
        assertThat(snapshotRows(pending.id())).isZero();
        assertThat(snapshotRows(draft.id())).isZero();

        signatureService.delete(managerId, managerActor);
        renameApprover("หลัง", "ย้าย", "After", "Backfill");
        DealQuotationDto reread = get(legacy);
        assertThat(reread.approvedByName()).isEqualTo("ณ วันย้าย ข้อมูล");
        assertThat(reread.approvedByNameEn()).isEqualTo("Migration Time");
        assertThat(reread.approverHasSignature()).isTrue();
        assertThat(signatureBytes(render(legacy))).as("frozen at backfill-time B").isEqualTo(storedB);

        // A re-run after the change must not refresh the frozen row either.
        runBackfill();
        assertThat(get(legacy).approvedByName()).isEqualTo("ณ วันย้าย ข้อมูล");
    }

    @Test
    void backfill_ofAnUnsignedPreV175Approval_freezesItUnsigned() throws Exception {
        DealQuotationDto legacy = createSubmittedApproved();
        jdbc.update("DELETE FROM sales.quotation_approver_snapshot WHERE quotation_id = :id",
            Map.of("id", legacy.id()));
        runBackfill();
        signatureService.upload(managerId, png(signatureA()), managerActor);
        assertThat(get(legacy).approverHasSignature()).isFalse();
        assertThat(signatureBytes(render(legacy))).isNull();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────────────

    /**
     * Re-executes V175 AS SHIPPED — the whole file, verbatim, from the classpath (the DDL is
     * IF NOT EXISTS and COMMENT ON is re-runnable, so a full re-run against the already-migrated
     * schema is exactly "the migration running over pre-existing approved rows"). If the backfill
     * statement were ever commented out or removed again, the backfill tests go red: nothing else
     * inserts the deleted snapshot rows.
     */
    private void runBackfill() throws Exception {
        String text;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(MIGRATION)) {
            assertThat(in).as(MIGRATION + " on the classpath").isNotNull();
            text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        jdbc.getJdbcOperations().execute(text);
    }

    private int snapshotRows(long quotationId) {
        Integer n = jdbc.queryForObject(
            "SELECT count(*) FROM sales.quotation_approver_snapshot WHERE quotation_id = :id",
            Map.of("id", quotationId), Integer.class);
        return n == null ? 0 : n;
    }

    private void renameApprover(String firstTh, String lastTh, String firstEn, String lastEn) {
        jdbc.update("""
            UPDATE hr.employee SET first_name_th = :ft, last_name_th = :lt, first_name_en = :fe, last_name_en = :le
             WHERE employee_id = :id
            """, Map.of("ft", firstTh, "lt", lastTh, "fe", firstEn, "le", lastEn, "id", managerId));
    }

    private DealQuotationDto get(DealQuotationDto q) {
        return quotationService.get(q.id(), salesActor);
    }

    private byte[] render(DealQuotationDto q) {
        return quotationService.renderXlsx(q.id(), salesActor);
    }

    private DealQuotationDto createSubmittedApproved() {
        DealQuotationDto created = quotationService.create(ticketId, upsertRequest(), salesActor);
        quotationService.submit(created.id(), salesActor);
        return quotationService.approve(created.id(), new ApproveRequest(null), managerActor);
    }

    private static int labelsRow(Sheet sheet) {
        for (var row : sheet) {
            var cell = row.getCell(0);
            if (cell != null && cell.getCellType() == CellType.STRING
                && cell.getStringCellValue().contains("ผู้พิมพ์") && cell.getStringCellValue().contains("ผู้สั่งซื้อ")) {
                return row.getRowNum();
            }
        }
        throw new AssertionError("signature labels row not found in the rendered XLS");
    }

    private static Sheet sheet(org.apache.poi.ss.usermodel.Workbook wb) {
        return wb.getSheet("Update") != null ? wb.getSheet("Update") : wb.getSheetAt(0);
    }

    /** The bytes of the picture anchored on the signature rule (same anchor rule as
     * DealQuotationIntegrationTest#signaturePicture), or null when there is none. */
    private static byte[] signatureBytes(byte[] xls) throws Exception {
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            Sheet sheet = sheet(wb);
            int labels = labelsRow(sheet);
            byte[] found = null;
            for (var shape : ((HSSFSheet) sheet).getDrawingPatriarch().getChildren()) {
                if (shape instanceof HSSFPicture pic
                    && pic.getClientAnchor().getRow1() <= labels
                    && pic.getClientAnchor().getRow2() >= labels
                    && pic.getClientAnchor().getRow2() <= labels + 1) {
                    assertThat(found).as("at most one signature picture").isNull();
                    found = pic.getPictureData().getData();
                }
            }
            return found;
        }
    }

    private static String signatureNames(byte[] xls) throws Exception {
        try (var wb = WorkbookFactory.create(new ByteArrayInputStream(xls))) {
            Sheet sheet = sheet(wb);
            return sheet.getRow(labelsRow(sheet) + 1).getCell(0).getStringCellValue();
        }
    }

    private static byte[] signatureA() {
        return HtmlXlsFidelityTest.signaturePng();
    }

    /** A second synthetic signature — a zig-zag, visibly different from A's sine squiggle. */
    private static byte[] signatureB() throws Exception {
        BufferedImage img = new BufferedImage(280, 110, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setColor(new Color(120, 20, 30));
        g.setStroke(new BasicStroke(4f));
        for (int x = 20; x < 260; x += 20) {
            g.drawLine(x, (x / 20) % 2 == 0 ? 30 : 80, x + 20, (x / 20) % 2 == 0 ? 80 : 30);
        }
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static MockMultipartFile png(byte[] bytes) {
        return new MockMultipartFile("file", "sig.png", "image/png", bytes);
    }

    private UpsertDealQuotationRequest upsertRequest() {
        ItemInput item = new ItemInput(null, null, null, "Brand A", "Model A", "White", "Matte", "60x60",
            new BigDecimal("10"), new BigDecimal("0.36"),
            WastageCalculator.QUANTITY_MODE_PIECES, null, 10, WastageCalculator.WASTAGE_MODE_NONE, null, 1,
            new BigDecimal("100.00"), BigDecimal.ZERO, "ไทย-สต็อก", 30, 45, null);
        return new UpsertDealQuotationRequest(null, "P003", "D002", LocalDate.now(), 30, "CREDIT", 30, 30,
            "หมายเหตุทดสอบ", List.of(item));
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
}
