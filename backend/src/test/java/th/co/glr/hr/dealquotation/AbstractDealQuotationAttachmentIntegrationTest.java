package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.assertj.core.api.ThrowableAssert;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.FileAttachmentBlobRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;

/**
 * Real-Postgres wiring for the tests of a quotation's เอกสารแนบ: the production
 * {@link DealQuotationAttachmentService} over the production {@link DealQuotationService}, and one
 * caller for every audience the rule names.
 *
 * <p><b>Files are seeded straight into the tables, not through the upload under test.</b> A test
 * about who may READ or DELETE a file must not depend on the upload working, and must fail on its
 * own refusal when the guard is missing. {@link #seed} writes the row and the bytes exactly as the
 * upload is required to. {@code DealQuotationAttachmentIntegrationTest
 * #theOwner_attachesAFileToADraft_andTheRowAndTheBytesAreStored} pins that shape, so a change to
 * what the upload writes fails there first.
 */
abstract class AbstractDealQuotationAttachmentIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {

    /** {@code hr.file_attachment.domain} of a quotation's file. A literal on purpose. The tests
     * state the contract and do not read it back from the code under test. */
    protected static final String DOMAIN = "deal_quotation";

    protected static final String PDF = "application/pdf";
    protected static final byte[] VENDOR_QUOTE = "%PDF-1.4 vendor cost 85,000.00 THB".getBytes(StandardCharsets.UTF_8);

    protected DealQuotationAttachmentService attachmentService;
    protected FileStorageService fileStorage;

    /** A second rep, who owns no deal of {@link #salesActor}'s. */
    protected UserPrincipal otherSalesActor;
    /** Approves quotations and never edits them. */
    protected UserPrincipal ceoActor;
    protected UserPrincipal accountActor;
    protected UserPrincipal importActor;
    /** Role {@code qc} holding {@code can_create_quotation}, as production's one grant holder does. */
    protected UserPrincipal grantedQcActor;
    /** Role {@code qc} without the grant, and role {@code employee}. Neither may open a quotation. */
    protected UserPrincipal qcActor;
    protected UserPrincipal employeeActor;
    protected UserPrincipal accountWithGrantActor;
    protected UserPrincipal importWithGrantActor;

    @BeforeEach
    void wireAttachmentService() {
        fileStorage = new FileStorageService("/tmp/glr-deal-quotation-attachment-test-uploads");
        attachmentService = new DealQuotationAttachmentService(quotationService,
            new DealQuotationAttachmentRepository(jdbc, new FileAttachmentBlobRepository(jdbc)), fileStorage);

        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        otherSalesActor = principal(employee(employees, "พนักงานขาย อีกคน", "sales-att-2@glr.co.th", "SALES", "แผนกขาย"), "sales");
        ceoActor = principal(employee(employees, "ผู้บริหาร เอกสารแนบ", "ceo-att@glr.co.th", "MD", "ผู้บริหาร"), "ceo");
        accountActor = principal(employee(employees, "บัญชี เอกสารแนบ", "acct-att@glr.co.th", "ACCT", "ฝ่ายบัญชี"), "account");
        importActor = principal(employee(employees, "นำเข้า เอกสารแนบ", "import-att@glr.co.th", "PCIM", "ฝ่ายนำเข้า"), "import");
        grantedQcActor = principal(granted(employee(employees, "คิวซี ได้สิทธิ์", "qc-grant-att@glr.co.th", "QC", "ฝ่าย QC")), "qc");
        qcActor = principal(employee(employees, "คิวซี ไม่มีสิทธิ์", "qc-att@glr.co.th", "QC", "ฝ่าย QC"), "qc");
        employeeActor = principal(employee(employees, "พนักงาน ทั่วไป", "emp-att@glr.co.th", "OTHER", "ฝ่ายอื่น"), "employee");
        accountWithGrantActor = principal(
            granted(employee(employees, "บัญชี ได้สิทธิ์", "acct-grant-att@glr.co.th", "ACCT", "ฝ่ายบัญชี")), "account");
        importWithGrantActor = principal(
            granted(employee(employees, "นำเข้า ได้สิทธิ์", "import-grant-att@glr.co.th", "PCIM", "ฝ่ายนำเข้า")), "import");
    }

    // ── quotations ───────────────────────────────────────────────────────────────────────────

    /** A DRAFT on a deal {@code owner} created. */
    protected DealQuotationDto draftOwnedBy(UserPrincipal owner) {
        return quotationService.create(deal(owner), draft(), owner);
    }

    /** Puts the row in {@code status} directly. Only for the status sweep, whose point is that
     * the gate is an allow-list over every value the column admits; a status a direct quotation
     * really reaches is always reached through the service elsewhere. */
    protected void forceStatus(long quotationId, String status) {
        jdbc.update("UPDATE sales.quotation SET doc_status = :status WHERE quotation_id = :id",
            Map.of("status", status, "id", quotationId));
    }

    protected String statusOf(long quotationId) {
        return jdbc.queryForObject("SELECT doc_status FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", quotationId), String.class);
    }

    // ── files ────────────────────────────────────────────────────────────────────────────────

    protected static MockMultipartFile pdf(String fileName) {
        return file(fileName, PDF, VENDOR_QUOTE);
    }

    protected static MockMultipartFile file(String fileName, String contentType, byte[] content) {
        return new MockMultipartFile("file", fileName, contentType, content);
    }

    /** A stored file of {@code quotationId}, written the way the upload must write it. */
    protected long seed(long quotationId, String fileName, byte[] content, UserPrincipal uploader) {
        return seed(quotationId, fileName, content, uploader, Instant.now());
    }

    protected long seed(long quotationId, String fileName, byte[] content, UserPrincipal uploader, Instant uploadedAt) {
        Long id = jdbc.queryForObject("""
            INSERT INTO hr.file_attachment
                (domain, owner_id, file_name, file_path, mime_type, file_size, uploaded_by, uploaded_at, storage_state)
            VALUES
                (:domain, :owner, :name, :key, :mime, :size, :by, :at, 'DATABASE')
            RETURNING attachment_id
            """,
            new MapSqlParameterSource()
                .addValue("domain", DOMAIN)
                .addValue("owner", quotationId)
                .addValue("name", fileName)
                .addValue("key", "deal-quotation/" + quotationId + "/" + java.util.UUID.randomUUID() + ".pdf")
                .addValue("mime", PDF)
                .addValue("size", (long) content.length)
                .addValue("by", uploader.id())
                .addValue("at", java.sql.Timestamp.from(uploadedAt)),
            Long.class);
        jdbc.update("INSERT INTO hr.file_attachment_blob (attachment_id, content) VALUES (:id, :content)",
            new MapSqlParameterSource().addValue("id", id).addValue("content", content));
        return id;
    }

    /** Every file row of every domain: a refused write must add none anywhere. */
    protected long fileRows() {
        return jdbc.queryForObject("SELECT count(*) FROM hr.file_attachment", Map.of(), Long.class);
    }

    protected long blobRows() {
        return jdbc.queryForObject("SELECT count(*) FROM hr.file_attachment_blob", Map.of(), Long.class);
    }

    protected long filesOf(long quotationId) {
        return jdbc.queryForObject(
            "SELECT count(*) FROM hr.file_attachment WHERE domain = :domain AND owner_id = :id",
            Map.of("domain", DOMAIN, "id", quotationId), Long.class);
    }

    protected boolean rowExists(long attachmentId) {
        return jdbc.queryForObject("SELECT count(*) FROM hr.file_attachment WHERE attachment_id = :id",
            Map.of("id", attachmentId), Long.class) == 1;
    }

    protected byte[] storedBytes(long attachmentId) {
        List<byte[]> found = jdbc.query("SELECT content FROM hr.file_attachment_blob WHERE attachment_id = :id",
            Map.of("id", attachmentId), (rs, rowNum) -> rs.getBytes("content"));
        return found.isEmpty() ? null : found.get(0);
    }

    /** The name the employee table holds for {@code employeeId}, read the way a quotation reads
     * its own {@code createdByName}. */
    protected String employeeName(long employeeId) {
        return jdbc.queryForObject("""
            SELECT NULLIF(TRIM(CONCAT_WS(' ', first_name_th, last_name_th)), '')
              FROM hr.employee WHERE employee_id = :id
            """, Map.of("id", employeeId), String.class);
    }

    /** The bytes {@code reader} is served for {@code attachmentId}. */
    protected byte[] served(long attachmentId, UserPrincipal reader) {
        DealQuotationAttachmentService.AttachmentFile file = attachmentService.download(attachmentId, reader);
        assertThat(file).as("%s is served file %s", reader.role(), attachmentId).isNotNull();
        return file.content();
    }

    // ── refusals ─────────────────────────────────────────────────────────────────────────────

    /** {@code call} must be refused with {@code status}. {@code who} names the caller and the
     * action in the failure, so a missing guard reads as what it lets through. */
    protected static ApiException refused(String who, HttpStatus status, ThrowableAssert.ThrowingCallable call) {
        Throwable thrown = catchThrowable(call);
        assertThat(thrown).as("%s must be refused with %s", who, status).isInstanceOf(ApiException.class);
        ApiException refusal = (ApiException) thrown;
        assertThat(refusal.getStatus()).as("%s, refused with \"%s\"", who, refusal.getMessage()).isEqualTo(status);
        return refusal;
    }

    // ── people ───────────────────────────────────────────────────────────────────────────────

    private static long employee(EmployeeRepository employees, String nameTh, String email,
                                 String divisionSourceCode, String divisionNameTh) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, nameTh, null, null, null, null, null, null, null,
            email, null, divisionSourceCode, divisionNameTh, divisionNameTh,
            null, null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private long granted(long employeeId) {
        jdbc.update("UPDATE hr.employee SET can_create_quotation = TRUE WHERE employee_id = :id",
            Map.of("id", employeeId));
        return employeeId;
    }

    protected static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, employeeId + "@glr.co.th", "Actor " + employeeId, role, employeeId,
            true, LocalDate.now(), false, null, false);
    }
}
