package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01AddressPayload;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01Details;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationDto;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.TaxAllowanceDeclarationSubmitRequest;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * Owner decision #4: an employee's corrections to the ล.ย.01 header reach the employee master
 * <b>only after HR confirms</b>. This proves that write against real Postgres, through the real
 * {@link TaxAllowanceDeclarationService} and a <b>real {@link EmployeeRepository}</b>.
 *
 * <p><b>Why a new class rather than extending the sibling scope test:</b>
 * {@code TaxAllowanceDeclarationScopeIntegrationTest} wires {@code mock(EmployeeRepository.class)}.
 * A mocked repository would happily "pass" every assertion here while the SQL did something else
 * entirely — exactly the failure mode CLAUDE.md names ("Mockito cannot reach this"). The write-back
 * only means anything if the statements actually execute, so this class uses the real one.
 *
 * <p>Written wrong-way-round throughout: every case asserts a caller <b>cannot</b> reach a master
 * row they should not, and that a refused action leaves the master <b>unchanged</b>. "HR can write
 * their own employee's address" is the uninteresting direction and is asserted only as the control
 * that proves the mechanism works at all.
 *
 * <p>⚠️ This exercises the FIRST production write to {@code hr_restricted} in this codebase. It
 * proves the SQL is correct and that the schema accepts it; it does <b>not</b> prove production's
 * database role holds the grant, because Testcontainers runs as an owner-level role. See the PR
 * body.
 */
class TaxAllowanceHeaderWriteBackIntegrationTest extends AbstractPostgresIntegrationTest {
    private TaxAllowanceDeclarationRepository repository;
    private TaxAllowanceDeclarationService service;

    private long employeeA;
    private long employeeB;
    private long hrEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new TaxAllowanceDeclarationRepository(jdbc);
        service = new TaxAllowanceDeclarationService(
            repository,
            new PayrollRepository(jdbc),
            // THE POINT OF THIS CLASS: a real EmployeeRepository, so the write-back's SQL runs.
            new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc)),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            mock(FileStorageService.class),
            mock(PayrollService.class),
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            // Not under test here — this class is about the write-BACK path (approve -> employee
            // master), not the submit-side HR notification.
            mock(NotificationService.class),
            new AppProperties(), new LorYor01Renderer());

        employeeA = seedEmployee("TWB-A");
        employeeB = seedEmployee("TWB-B");
        hrEmployeeId = seedEmployee("TWB-HR");
    }

    // ---- the control: the mechanism works at all -------------------------------------------------

    @Test
    void hrApprovalPromotesTheConfirmedHeaderIntoTheEmployeeMaster() {
        long declarationId = submitWithHeader(employeeA, fullHeader()).declarationId();
        approveSigned(declarationId);

        assertThat(addressField(employeeA, "house_no")).isEqualTo("199/2");
        assertThat(addressField(employeeA, "moo")).isEqualTo("4");
        assertThat(addressField(employeeA, "room_no")).isEqualTo("1204");
        assertThat(addressField(employeeA, "junction")).isEqualTo("แยกรัชดา");
        assertThat(addressField(employeeA, "province")).isEqualTo("กรุงเทพมหานคร");
        assertThat(addressField(employeeA, "postal_code")).isEqualTo("10310");
        assertThat(taxIdOf(employeeA)).isEqualTo("1234567890123");
        assertThat(maritalStatusOf(employeeA)).isEqualTo("สมรส");
    }

    /** All five V137 columns must land — the write-back's whole justification was reaching them. */
    @Test
    void theWriteBackReachesAllThirteenAddressSlotsNotJustTheEightThatPredateV137() {
        long declarationId = submitWithHeader(employeeA, fullHeader()).declarationId();
        approveSigned(declarationId);

        assertThat(addressField(employeeA, "room_no")).isEqualTo("1204");
        assertThat(addressField(employeeA, "floor")).isEqualTo("12");
        assertThat(addressField(employeeA, "village")).isEqualTo("หมู่บ้านสุขใจ");
        assertThat(addressField(employeeA, "moo")).isEqualTo("4");
        assertThat(addressField(employeeA, "junction")).isEqualTo("แยกรัชดา");
    }

    // ---- wrong-way-round: refused actions must not touch the master ------------------------------

    /**
     * THE case that matters. A plain employee cannot approve — and must therefore not be able to
     * rewrite their own master address by approving their own declaration. Asserting the 403 alone
     * would be insufficient: the write-back could still have run before the guard.
     */
    @Test
    void anEmployeeCannotApproveTheirOwnDeclarationAndTheMasterStaysUntouched() {
        seedExistingAddress(employeeA, "OLD-HOUSE", "OLD-PROVINCE");
        long declarationId = submitWithHeader(employeeA, fullHeader()).declarationId();
        TaxAllowanceTestSupport.attachSignedForm(jdbc, declarationId);

        assertThatThrownBy(() -> service.approve(declarationId, null, employeeActor(employeeA)))
            .isInstanceOf(ApiException.class);

        assertThat(addressField(employeeA, "house_no"))
            .as("a refused approval must not have written the master row")
            .isEqualTo("OLD-HOUSE");
        assertThat(addressField(employeeA, "province")).isEqualTo("OLD-PROVINCE");
        assertThat(taxIdOf(employeeA))
            .as("hr_restricted must not gain a row from a refused approval")
            .isNull();
    }

    /** A different, unrelated employee cannot approve someone else's declaration either. */
    @Test
    void anUnrelatedEmployeeCannotApproveAndTheOwnersMasterStaysUntouched() {
        seedExistingAddress(employeeA, "OLD-HOUSE", "OLD-PROVINCE");
        long declarationId = submitWithHeader(employeeA, fullHeader()).declarationId();
        TaxAllowanceTestSupport.attachSignedForm(jdbc, declarationId);

        assertThatThrownBy(() -> service.approve(declarationId, null, employeeActor(employeeB)))
            .isInstanceOf(ApiException.class);

        assertThat(addressField(employeeA, "house_no")).isEqualTo("OLD-HOUSE");
        assertThat(taxIdOf(employeeA)).isNull();
    }

    /**
     * The signed ล.ย.01 is the employee's attestation to the header. No signature, no write —
     * otherwise HR could promote a header nobody stood behind.
     */
    @Test
    void approvingWithoutASignedFormIsRefusedAndWritesNothingToTheMaster() {
        seedExistingAddress(employeeA, "OLD-HOUSE", "OLD-PROVINCE");
        long declarationId = submitWithHeader(employeeA, fullHeader()).declarationId();

        assertThatThrownBy(() -> service.approve(declarationId, null, hrActor()))
            .isInstanceOf(ApiException.class);

        assertThat(addressField(employeeA, "house_no")).isEqualTo("OLD-HOUSE");
        assertThat(taxIdOf(employeeA)).isNull();
    }

    /**
     * The write must land on the declaration's OWNER, never on the HR actor who approved it. The
     * employee id comes from the stored declaration, not from anything the caller supplies, and this
     * is what pins that.
     */
    @Test
    void theWriteLandsOnTheDeclarationOwnerNotOnTheApprovingHrUser() {
        long declarationId = submitWithHeader(employeeA, fullHeader()).declarationId();
        approveSigned(declarationId);

        assertThat(taxIdOf(employeeA)).isEqualTo("1234567890123");
        assertThat(taxIdOf(hrEmployeeId))
            .as("approving must not stamp the approver's own PII row")
            .isNull();
        assertThat(addressField(hrEmployeeId, "house_no")).isNull();
        assertThat(addressField(employeeB, "house_no"))
            .as("no bystander employee may be touched")
            .isNull();
    }

    // ---- non-destruction and scope limits --------------------------------------------------------

    /**
     * A blank box means "not answered", not "erase it". A partially-filled header must leave every
     * unanswered slot as HR already had it.
     */
    @Test
    void blankHeaderFieldsPreserveExistingMasterValuesRatherThanErasingThem() {
        seedExistingAddress(employeeA, "OLD-HOUSE", "OLD-PROVINCE");
        LorYor01Details sparse = header(null, "SINGLE", new LorYor01AddressPayload(
            null, null, null, null, "NEW-HOUSE", null, null, null, null, null, null, null, null));

        long declarationId = submitWithHeader(employeeA, sparse).declarationId();
        approveSigned(declarationId);

        assertThat(addressField(employeeA, "house_no")).isEqualTo("NEW-HOUSE");
        assertThat(addressField(employeeA, "province"))
            .as("an unanswered slot must not blank the master value")
            .isEqualTo("OLD-PROVINCE");
    }

    /** Scope is address + สถานภาพ + tax ID. A tax form is not a name-change instrument. */
    @Test
    void theLegalNameIsNeverRewrittenEvenThoughTheFormCarriesIt() {
        jdbc.update("UPDATE hr.employee SET first_name_th = 'เดิม', last_name_th = 'นามสกุลเดิม' "
            + "WHERE employee_id = :id", Map.of("id", employeeA));

        long declarationId = submitWithHeader(employeeA, fullHeader()).declarationId();
        approveSigned(declarationId);

        assertThat(nameOf(employeeA, "first_name_th")).isEqualTo("เดิม");
        assertThat(nameOf(employeeA, "last_name_th")).isEqualTo("นามสกุลเดิม");
    }

    /**
     * {@code hr.employee.marital_status} holds Thai free text everywhere else in this system, and
     * has no CHECK constraint to stop an enum token going in. WIDOWED / DIED_DURING_YEAR are
     * deliberately NOT mapped — see {@code TaxAllowanceDeclarationService#maritalStatusForMaster}.
     */
    @Test
    void maritalStatusIsWrittenAsThaiAndUnmappableStatesLeaveTheMasterAlone() {
        jdbc.update("UPDATE hr.employee SET marital_status = 'โสด' WHERE employee_id = :id",
            Map.of("id", employeeA));
        long single = submitWithHeader(employeeA, header(null, "MARRIED", null)).declarationId();
        approveSigned(single);
        assertThat(maritalStatusOf(employeeA))
            .as("must be the Thai vocabulary the column already holds, never the token MARRIED")
            .isEqualTo("สมรส");

        jdbc.update("UPDATE hr.employee SET marital_status = 'สมรส' WHERE employee_id = :id",
            Map.of("id", employeeB));
        long widowed = submitWithHeader(employeeB, header(null, "DIED_DURING_YEAR", null)).declarationId();
        approveSigned(widowed);
        assertThat(maritalStatusOf(employeeB))
            .as("an unmappable ล.ย.01 state must leave the master for HR to set by hand")
            .isEqualTo("สมรส");
    }

    /** The upsert half: employee_pii has no row for most employees, so an UPDATE would write nothing. */
    @Test
    void theTaxIdWriteInsertsWhenTheEmployeeHasNoPiiRowAtAll() {
        assertThat(jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr_restricted.employee_pii WHERE employee_id = :id",
            Map.of("id", employeeA), Integer.class)).isZero();

        approveSigned(submitWithHeader(employeeA, fullHeader()).declarationId());

        assertThat(taxIdOf(employeeA)).isEqualTo("1234567890123");
    }

    /** ...and the update half, without clobbering the other PII columns on an existing row. */
    @Test
    void theTaxIdWriteUpdatesAnExistingPiiRowWithoutTouchingItsOtherColumns() {
        jdbc.update("""
            INSERT INTO hr_restricted.employee_pii(employee_id, national_id, tax_id, religion)
            VALUES (:id, '9999999999999', 'OLD-TAX-ID', 'พุทธ')
            """, Map.of("id", employeeA));

        approveSigned(submitWithHeader(employeeA, fullHeader()).declarationId());

        assertThat(taxIdOf(employeeA)).isEqualTo("1234567890123");
        assertThat(jdbc.queryForObject(
            "SELECT national_id FROM hr_restricted.employee_pii WHERE employee_id = :id",
            Map.of("id", employeeA), String.class))
            .as("a tax-ID correction must not disturb the rest of the PII row")
            .isEqualTo("9999999999999");
        assertThat(jdbc.queryForObject(
            "SELECT religion FROM hr_restricted.employee_pii WHERE employee_id = :id",
            Map.of("id", employeeA), String.class)).isEqualTo("พุทธ");
    }

    /**
     * V138 widened building/soi/road from VARCHAR(120) to VARCHAR(200) to match the declaration.
     * Without it this approval fails with SQLSTATE 22001 and rolls the whole thing back.
     */
    @Test
    void aTwoHundredCharacterRoadDoesNotOverflowTheMasterColumn() {
        String longRoad = "ถ".repeat(200);
        LorYor01Details wide = header(null, "SINGLE", new LorYor01AddressPayload(
            "อ".repeat(200), null, null, null, "1", null, "ซ".repeat(200), null, longRoad,
            null, null, null, null));

        approveSigned(submitWithHeader(employeeA, wide).declarationId());

        assertThat(addressField(employeeA, "road")).isEqualTo(longRoad);
    }

    // --- helpers ----------------------------------------------------------------------------------

    private LorYor01Details fullHeader() {
        return header("1234567890123", "MARRIED", new LorYor01AddressPayload(
            "อาคารเอ", "1204", "12", "หมู่บ้านสุขใจ", "199/2", "4", "ซอย 5", "แยกรัชดา",
            "ถนนรัชดาภิเษก", "ดินแดง", "ดินแดง", "กรุงเทพมหานคร", "10310"));
    }

    private LorYor01Details header(String taxpayerId, String maritalState, LorYor01AddressPayload address) {
        return new LorYor01Details(
            taxpayerId, "ทดสอบ", "นามสกุล", address,
            maritalState, null, null,
            null, null,
            null, null, null, null, null,
            null, null, null, null,
            null, null, null);
    }

    private TaxAllowanceDeclarationDto submitWithHeader(long employeeId, LorYor01Details header) {
        return service.submitOwn(new TaxAllowanceDeclarationSubmitRequest(
            2026, new BigDecimal("60000"),
            null, null, null, null,
            null, null, null,
            null, null, null, null,
            null, null, null, null,
            null, null, null,
            null,
            null,
            null,
            header), employeeActor(employeeId));
    }

    private void approveSigned(long declarationId) {
        TaxAllowanceTestSupport.attachSignedForm(jdbc, declarationId);
        service.approve(declarationId, null, hrActor());
    }

    private void seedExistingAddress(long employeeId, String houseNo, String province) {
        jdbc.update("""
            INSERT INTO hr.employee_address(employee_id, address_type, house_no, province)
            VALUES (:id, 'CURRENT', :houseNo, :province)
            """, Map.of("id", employeeId, "houseNo", houseNo, "province", province));
    }

    private String addressField(long employeeId, String column) {
        return jdbc.query(
            "SELECT " + column + " FROM hr.employee_address "
                + "WHERE employee_id = :id AND address_type = 'CURRENT'",
            Map.of("id", employeeId),
            rs -> rs.next() ? rs.getString(1) : null);
    }

    private String taxIdOf(long employeeId) {
        return jdbc.query("SELECT tax_id FROM hr_restricted.employee_pii WHERE employee_id = :id",
            Map.of("id", employeeId), rs -> rs.next() ? rs.getString(1) : null);
    }

    private String maritalStatusOf(long employeeId) {
        return jdbc.queryForObject("SELECT marital_status FROM hr.employee WHERE employee_id = :id",
            Map.of("id", employeeId), String.class);
    }

    private String nameOf(long employeeId, String column) {
        return jdbc.queryForObject(
            "SELECT " + column + " FROM hr.employee WHERE employee_id = :id",
            Map.of("id", employeeId), String.class);
    }

    private long seedEmployee(String code) {
        return jdbc.queryForObject(
            "INSERT INTO hr.employee (employee_code, is_active) VALUES (:code, TRUE) RETURNING employee_id",
            Map.of("code", code), Long.class);
    }

    private UserPrincipal employeeActor(long employeeId) {
        return new UserPrincipal(employeeId, "e" + employeeId + "@glr.co.th", "employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }

    private UserPrincipal hrActor() {
        return new UserPrincipal(hrEmployeeId, "hr@glr.co.th", "HR", "hr", hrEmployeeId, true,
            LocalDate.now(), false, null, false);
    }
}
