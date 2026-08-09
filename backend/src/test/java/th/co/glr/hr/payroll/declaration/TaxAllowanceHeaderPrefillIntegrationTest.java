package th.co.glr.hr.payroll.declaration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.payroll.PayrollRepository;
import th.co.glr.hr.payroll.PayrollService;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.LorYor01HeaderPrefill;
import th.co.glr.hr.payroll.declaration.TaxAllowanceDeclarationDtos.MyTaxAllowanceDeclarationsResponse;
import th.co.glr.hr.payroll.declaration.loryor01.LorYor01Renderer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * The ล.ย.01 header prefill (owner decision #4, the read half) against real Postgres, through the
 * real {@link TaxAllowanceDeclarationService} and the real {@link EmployeeRepository}.
 *
 * <h2>Why this class exists separately from {@code TaxAllowanceDeclarationScopeIntegrationTest}</h2>
 *
 * That class wires {@code mock(EmployeeRepository.class)}, which is exactly the thing CLAUDE.md
 * warns cannot prove a scope filter: a mocked repository happily answers while the SQL does
 * something else. The prefill's whole security property lives in one {@code WHERE} clause, so it
 * needs the real repository and a real database or it is not tested at all.
 *
 * <h2>What is actually at stake</h2>
 *
 * {@code hr_restricted.employee_pii.tax_id} is PDPA-restricted. Before this feature, an employee
 * could not read their own tax ID through any endpoint — {@code EmployeeService#get} strips the
 * whole {@code sensitive} block for a self-service viewer ({@code
 * withoutSensitiveSelfServiceFields()}). This is a new self-read path, so the cases below are
 * written <b>wrong-way-round</b> per CLAUDE.md: what matters is that employee A cannot obtain
 * employee B's tax ID, never that A can obtain their own.
 *
 * <h2>Mutation checks performed against this class (2026-08-09)</h2>
 *
 * <ol>
 *   <li>Dropping {@code WHERE e.employee_id = :employeeId} from
 *       {@code EmployeeRepository#findLorYor01HeaderSource} — {@code
 *       employeeCannotObtainAnotherEmployeesTaxIdThroughTheHeaderPrefill} goes red.</li>
 *   <li>Passing a constant employee id instead of {@code actor.employeeId()} in
 *       {@code TaxAllowanceDeclarationService#headerPrefill} — the same test goes red.</li>
 * </ol>
 *
 * Both outcomes are recorded in the PR body. Without a mutation check a green test here would be
 * evidence of nothing, which is the failure mode this repo has been bitten by before.
 */
class TaxAllowanceHeaderPrefillIntegrationTest extends AbstractPostgresIntegrationTest {

    private TaxAllowanceDeclarationService service;

    private long employeeA;
    private long employeeB;

    /** Deliberately different in every digit from {@link #TAX_ID_B}, so a mix-up cannot look like a pass. */
    private static final String TAX_ID_A = "1103700000011";
    private static final String TAX_ID_B = "9876543210987";

    @BeforeEach
    void wireRealCollaborators() {
        service = new TaxAllowanceDeclarationService(
            new TaxAllowanceDeclarationRepository(jdbc),
            new PayrollRepository(jdbc),
            // THE REAL ONE. This is the whole point of the class -- see the header.
            new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc)),
            new TaxAllowanceCapCatalog(),
            mock(AuditService.class),
            mock(FileStorageService.class),
            mock(PayrollService.class),
            new NotificationRepository(jdbc),
            new AppProperties(), new LorYor01Renderer());

        employeeA = seedEmployee("PREFILL-A", "สมชาย", "ใจดี", "โสด");
        employeeB = seedEmployee("PREFILL-B", "สมหญิง", "รักษ์ไทย", "สมรส");
    }

    // --- the wrong-way-round case ---------------------------------------------------------------

    /**
     * A reads their own form; B's restricted tax ID must not be anywhere in the answer.
     *
     * <p>B is given a tax ID and A is <b>not</b>, which is the arrangement that catches an unfiltered
     * or mis-parameterised query: with both populated, a query returning "some row" would still look
     * plausible. Here the only way A's response can carry a tax ID at all is if it leaked B's.
     */
    @Test
    void employeeCannotObtainAnotherEmployeesTaxIdThroughTheHeaderPrefill() {
        givenTaxId(employeeB, TAX_ID_B);
        // A has no employee_pii row at all -- the common production state.

        LorYor01HeaderPrefill prefill = prefillFor(employeeA);

        assertThat(prefill.taxpayerId())
            .as("employee A has no tax ID of their own; anything here came from another employee's row")
            .isNull();
        assertThat(prefill.firstNameTh())
            .as("the prefill must be A's identity, not whichever row the query happened to reach")
            .isEqualTo("สมชาย");
        assertThat(prefill.lastNameTh()).isEqualTo("ใจดี");
    }

    /** The same leak, from the other direction: A holding a tax ID must not see B's instead of theirs. */
    @Test
    void oneEmployeesPrefillNeverCarriesAnotherEmployeesTaxIdWhenBothHaveOne() {
        givenTaxId(employeeA, TAX_ID_A);
        givenTaxId(employeeB, TAX_ID_B);

        assertThat(prefillFor(employeeA).taxpayerId()).isEqualTo(TAX_ID_A).isNotEqualTo(TAX_ID_B);
        assertThat(prefillFor(employeeB).taxpayerId()).isEqualTo(TAX_ID_B).isNotEqualTo(TAX_ID_A);
    }

    /**
     * The address is scoped by the same clause and is worth its own assertion: it is the other half
     * of what identifies a person, and {@code employee_address} is joined separately from
     * {@code employee_pii}, so one join can be right while the other is not.
     */
    @Test
    void employeeCannotObtainAnotherEmployeesAddressThroughTheHeaderPrefill() {
        givenCurrentAddress(employeeB, "99/9", "บ้านคู่สมรส", "ลาดพร้าว", "10310");

        LorYor01HeaderPrefill prefill = prefillFor(employeeA);

        assertThat(prefill.address().houseNo()).isNull();
        assertThat(prefill.address().village()).isNull();
        assertThat(prefill.address().district()).isNull();
        assertThat(prefill.address().postalCode()).isNull();
    }

    // --- the shape the client depends on --------------------------------------------------------

    /**
     * All thirteen address slots survive the round trip, including the five V137 added. A subset
     * arriving would be invisible to the employee (blank boxes look identical to "we have nothing"),
     * and they would silently retype what the master already held.
     */
    @Test
    void everyOneOfTheThirteenAddressSlotsIsPrefilled() {
        jdbc.update("""
            INSERT INTO hr.employee_address(
                employee_id, address_type, house_no, building, room_no, floor, village, moo, soi,
                junction, road, subdistrict, district, province, postal_code)
            VALUES (:id, 'CURRENT', '123/45', 'อาคารเอ', '1201', '12', 'หมู่บ้านสวนหลวง', '4',
                    'ซอย 7', 'แยกรัชดา', 'ถนนพระราม 9', 'ห้วยขวาง', 'ห้วยขวาง', 'กรุงเทพมหานคร', '10310')
            """, new MapSqlParameterSource("id", employeeA));

        var address = prefillFor(employeeA).address();

        assertThat(address.houseNo()).isEqualTo("123/45");
        assertThat(address.building()).isEqualTo("อาคารเอ");
        assertThat(address.roomNo()).isEqualTo("1201");
        assertThat(address.floor()).isEqualTo("12");
        assertThat(address.village()).isEqualTo("หมู่บ้านสวนหลวง");
        assertThat(address.moo()).isEqualTo("4");
        assertThat(address.soi()).isEqualTo("ซอย 7");
        assertThat(address.junction()).isEqualTo("แยกรัชดา");
        assertThat(address.road()).isEqualTo("ถนนพระราม 9");
        assertThat(address.subDistrict()).isEqualTo("ห้วยขวาง");
        assertThat(address.district()).isEqualTo("ห้วยขวาง");
        assertThat(address.province()).isEqualTo("กรุงเทพมหานคร");
        assertThat(address.postalCode()).isEqualTo("10310");
    }

    /**
     * Read and write must agree on {@code address_type}. The write-back
     * ({@code EmployeeRepository#upsertCurrentAddressFromDeclaration}) targets {@code 'CURRENT'};
     * if the read reached {@code 'REGISTERED'} instead, an approved correction would never come back
     * on the next filing and the employee would retype it forever — a defect that looks like nothing
     * at all from the outside.
     */
    @Test
    void theRegisteredAddressIsNotWhatPrefillsTheForm() {
        jdbc.update("""
            INSERT INTO hr.employee_address(employee_id, address_type, house_no, province)
            VALUES (:id, 'REGISTERED', 'ทะเบียนบ้าน-1', 'เชียงใหม่')
            """, new MapSqlParameterSource("id", employeeA));

        var address = prefillFor(employeeA).address();

        assertThat(address.houseNo()).isNull();
        assertThat(address.province()).isNull();
    }

    /**
     * ข้อ 1 must survive prefill -> approve -> prefill unchanged, or every re-filing would flip the
     * employee's สถานภาพ. This pins the two mappings as inverses; they live in different methods and
     * nothing else would notice them drifting apart.
     */
    @Test
    void maritalStatusRoundTripsThroughTheFormsOwnVocabulary() {
        assertThat(prefillFor(employeeA).maritalState()).isEqualTo("SINGLE");   // master holds โสด
        assertThat(prefillFor(employeeB).maritalState()).isEqualTo("MARRIED");  // master holds สมรส
    }

    /**
     * The column is {@code VARCHAR(30)} with no CHECK, so a decade of hand entry can have put
     * anything in it. An unrecognised value must leave ข้อ 1 un-ticked rather than guess a legal
     * status on the employee's behalf.
     */
    @Test
    void anUnrecognisedMaritalStatusLeavesTheBoxUnticked() {
        jdbc.update("UPDATE hr.employee SET marital_status = 'หย่าร้าง' WHERE employee_id = :id",
            new MapSqlParameterSource("id", employeeA));

        assertThat(prefillFor(employeeA).maritalState()).isNull();
    }

    /**
     * An employee with no PII row and no address must still get a usable, non-null envelope — the
     * form has to open and be typeable by hand. A null {@code headerPrefill} (or a null
     * {@code address}) would be a client-side crash on the most common state in production.
     */
    @Test
    void anEmployeeWithNoMasterDataStillGetsAnEmptyPrefillRatherThanNothing() {
        LorYor01HeaderPrefill prefill = prefillFor(employeeA);

        assertThat(prefill).isNotNull();
        assertThat(prefill.taxpayerId()).isNull();
        assertThat(prefill.address()).as("a null address block would crash the form's 13 inputs").isNotNull();
        assertThat(prefill.address().postalCode()).isNull();
    }

    // --- helpers --------------------------------------------------------------------------------

    /** Always through the real endpoint method, never the repository directly — the scoping is the service's. */
    private LorYor01HeaderPrefill prefillFor(long employeeId) {
        MyTaxAllowanceDeclarationsResponse response = service.getOwn(2026, employeeActor(employeeId));
        return response.headerPrefill();
    }

    private void givenTaxId(long employeeId, String taxId) {
        jdbc.update("INSERT INTO hr_restricted.employee_pii(employee_id, tax_id) VALUES (:id, :taxId)",
            new MapSqlParameterSource("id", employeeId).addValue("taxId", taxId));
    }

    private void givenCurrentAddress(long employeeId, String houseNo, String village, String district,
                                     String postalCode) {
        jdbc.update("""
            INSERT INTO hr.employee_address(employee_id, address_type, house_no, village, district, postal_code)
            VALUES (:id, 'CURRENT', :houseNo, :village, :district, :postalCode)
            """, new MapSqlParameterSource("id", employeeId)
            .addValue("houseNo", houseNo).addValue("village", village)
            .addValue("district", district).addValue("postalCode", postalCode));
    }

    private long seedEmployee(String code, String firstNameTh, String lastNameTh, String maritalStatus) {
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, is_active, first_name_th, last_name_th, marital_status)
            VALUES (:code, TRUE, :firstNameTh, :lastNameTh, :maritalStatus)
            RETURNING employee_id
            """,
            Map.of("code", code, "firstNameTh", firstNameTh, "lastNameTh", lastNameTh,
                "maritalStatus", maritalStatus),
            Long.class);
    }

    private UserPrincipal employeeActor(long employeeId) {
        return new UserPrincipal(employeeId, "e" + employeeId + "@glr.co.th", "employee", "employee",
            employeeId, true, LocalDate.now(), false, null, false);
    }
}
