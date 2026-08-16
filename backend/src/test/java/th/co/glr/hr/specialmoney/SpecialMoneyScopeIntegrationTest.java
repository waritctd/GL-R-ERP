package th.co.glr.hr.specialmoney;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.config.AppProperties;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * THE AUTHZ EVIDENCE for special-money requests, modelled directly on {@code
 * th.co.glr.hr.attendance.AttendanceScopeIntegrationTest}. Wires the REAL {@link
 * SpecialMoneyService}, the REAL {@link SpecialMoneyRepository}, and the REAL {@link
 * SpecialMoneyPolicyEvaluator} against a real Postgres database -- only {@link AuditService} and
 * {@link NotificationService} are stubbed, since neither participates in the authorization
 * decision.
 *
 * <p>CLAUDE.md records issue #199 (mockApi.js let HR approve OT; the real service returns 403) and
 * PR #238 (mock-driven browser clicking reported as verified role scoping) as exactly the failure
 * mode this test exists to catch. Every case here is written the WRONG way round: can a caller
 * reach data or mutate a row they should not be able to -- and every assertion checks the database
 * itself (a re-read or a row count), not just the HTTP-shaped status code, since a service that
 * throws late after already writing would still "look" correct to a status-code-only assertion.
 *
 * <p><b>Section 11 is the confidentiality rule</b> (owner, 2026-08-10): welfare is confidential to
 * each employee, so a ฝ่าย manager sees only their OWN requests -- not their team's. The older
 * cases here test the out-of-division direction, which was never the leak; section 11 tests the
 * in-division one, which was. Those cases fail against any build where
 * {@code SpecialMoneyService.canAccessEmployee} regains a manager branch or
 * {@code SpecialMoneyFilter} regains a division field.
 */
class SpecialMoneyScopeIntegrationTest extends AbstractPostgresIntegrationTest {

    private SpecialMoneyService service;
    private SpecialMoneyRepository repository;

    private long salesDivision;
    private long factoryDivision;
    private long orphanDivision;
    private long salesManagerEmployeeId;
    private long salesStaffEmployeeId;
    private long factoryStaffEmployeeId;
    private long orphanStaffEmployeeId;
    private long hrEmployeeId;
    private long ceoEmployeeId;

    @BeforeEach
    void wireRealCollaborators() {
        repository = new SpecialMoneyRepository(jdbc, new ObjectMapper());
        service = new SpecialMoneyService(
            repository,
            new SpecialMoneyPolicyEvaluator(),
            mock(AuditService.class),
            mock(NotificationService.class),
            new AppProperties(), new CeoApproverRepository(jdbc));

        salesDivision = insertDivision("SLS", "ฝ่ายขาย");
        factoryDivision = insertDivision("FAC", "ฝ่ายโรงงาน");
        // ฝ่ายขาย has a real ผู้จัดการ, so its staff keep the two-stage manager -> CEO route and the
        // cases below still exercise the manager gate rather than the manager-less shortcut.
        salesManagerEmployeeId = insertEmployee("M001", salesDivision, null, "ผู้จัดการฝ่ายขาย");
        salesStaffEmployeeId = insertEmployee("S001", salesDivision, salesManagerEmployeeId);
        // ฝ่ายโรงงาน needs its own ผู้จัดการ too. Without one, a factory request would have no
        // manager stage and managerCannotApproveRequestOfOutOfDivisionEmployee would start failing
        // at the CEO gate instead of the manager gate -- still green, but no longer evidence that
        // a manager cannot reach outside their own ฝ่าย.
        insertEmployee("M002", factoryDivision, null, "ผู้จัดการฝ่ายโรงงาน");
        factoryStaffEmployeeId = insertEmployee("F001", factoryDivision, null);
        // ฝ่ายซ่อมบำรุง has NO ผู้จัดการ at all -- the manager-less case the CEO shortcut exists for.
        orphanDivision = insertDivision("MNT", "ฝ่ายซ่อมบำรุง");
        orphanStaffEmployeeId = insertEmployee("O001", orphanDivision, null);
        // HR's own employee record deliberately has no reports-to/division-manager link to anyone
        // else in this fixture -- if it did, managesEmployee() could accidentally succeed via that
        // relation instead of via an (absent) HR carve-out, masking exactly the bug this class
        // exists to catch.
        hrEmployeeId = insertEmployee("HR001", null, null);
        ceoEmployeeId = insertEmployee("CEO001", null, null);
    }

    // --- 1. list() division scoping -----------------------------------------

    @Test
    void managerCannotListRequestsOutsideOwnDivision() {
        long requestId = submitFuneralRequest(factoryStaffEmployeeId);

        List<SpecialMoneyRequestDto> visible = service.list(
            salesManager(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null);

        assertThat(visible).isEmpty();
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
    }

    // --- 2. approve() is CEO-only, for every employee ------------------------

    @Test
    void noManagerCanApproveAWelfareRequestInsideOrOutsideTheirOwnDivision() {
        long ownDivision = submitFuneralRequest(salesStaffEmployeeId);
        long otherDivision = submitFuneralRequest(factoryStaffEmployeeId);

        // Welfare has no manager stage at all, so a ผู้จัดการ is refused even for their OWN team --
        // the case that would slip through if the CEO-only rule were implemented as a scope check
        // rather than a role check.
        for (long requestId : new long[] {ownDivision, otherDivision}) {
            assertThatThrownBy(() -> service.approve(requestId, null, salesManager()))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("CEO");
            assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
            assertThat(approvedAmountOf(requestId)).isNull();
        }
    }

    // --- 3. list() employee scoping ------------------------------------------

    @Test
    void employeeCannotSeeAnotherEmployeesRequest() {
        submitFuneralRequest(salesManagerEmployeeId);

        List<SpecialMoneyRequestDto> visible = service.list(
            employee(salesStaffEmployeeId, salesDivision),
            LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null);

        assertThat(visible).isEmpty();
    }

    // --- 4. submit() on behalf of a non-report -------------------------------

    @Test
    void employeeCannotSubmitOnBehalfOfNonReport() {
        long countBefore = requestCountFor(factoryStaffEmployeeId);

        assertThatThrownBy(() -> service.submit(
                "AID_FUNERAL",
                funeralRequest(factoryStaffEmployeeId, LocalDate.of(2026, 7, 1)),
                employee(salesStaffEmployeeId, salesDivision)))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ตนเอง");

        assertThat(requestCountFor(factoryStaffEmployeeId)).isEqualTo(countBefore);
    }

    // --- 5. hr cannot approve (issue #199 shape) -----------------------------

    @Test
    void hrCannotApprove() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);

        assertThatThrownBy(() -> service.approve(requestId, null, hr()))
            .isInstanceOf(ApiException.class);
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
    }

    // --- 6. hr cannot submit on behalf of an arbitrary employee --------------

    @Test
    void hrCannotSubmitOnBehalfOfArbitraryEmployee() {
        long countBefore = requestCountFor(salesStaffEmployeeId);

        assertThatThrownBy(() -> service.submit(
                "AID_FUNERAL",
                funeralRequest(salesStaffEmployeeId, LocalDate.of(2026, 7, 1)),
                hr()))
            .isInstanceOf(ApiException.class);

        assertThat(requestCountFor(salesStaffEmployeeId)).isEqualTo(countBefore);
    }

    // --- 7. nobody below the CEO can approve, whatever their relationship ----

    @Test
    void nobodyBelowCeoCanApproveAWelfareRequest() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);

        // Every non-CEO shape at once: the requester's own ผู้จัดการ, HR (issue #199), and the
        // requester themselves. "CEO-only" must be a role gate, not a scope gate -- if it were a
        // scope gate, the first of these would pass.
        for (UserPrincipal notTheCeo : List.of(
                salesManager(), hr(), employee(salesStaffEmployeeId, salesDivision))) {
            assertThatThrownBy(() -> service.approve(requestId, null, notTheCeo))
                .isInstanceOf(ApiException.class);
        }
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
        assertThat(approvedAmountOf(requestId)).isNull();
    }

    // --- 8. the CEO approves in ONE stage, for every shape of employee -------

    @Test
    void ceoApprovesStraightFromSubmittedForEveryEmployee() {
        // Three shapes that used to route differently: staff under a ผู้จัดการ, staff in a ฝ่าย with
        // no ผู้จัดการ at all, and a ผู้จัดการ's own request. Welfare must treat all three
        // identically -- one CEO stage, no manager stage anywhere.
        for (long employeeId : new long[] {
                salesStaffEmployeeId, orphanStaffEmployeeId, salesManagerEmployeeId}) {
            long requestId = submitFuneralRequest(employeeId);
            attachEvidence(requestId);

            service.approve(requestId, null, ceo());

            assertThat(statusOf(requestId)).isEqualTo("APPROVED");
            assertThat(approvedAmountOf(requestId)).isEqualByComparingTo(new BigDecimal("5000"));
            // NULL because no manager reviewed it. Payroll and audit read these columns, so a
            // forged manager stamp here would be a lie in the record, not a cosmetic detail.
            assertThat(managerApprovedByOf(requestId)).isNull();
            assertThat(ceoApprovedByOf(requestId)).isNotNull();
        }
    }

    @Test
    void ceoCannotApproveAnEvidenceRequiredTypeWithNothingAttached() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);

        // เงินช่วยเหลืองานศพ requires ใบมรณบัตร. Until 2026-08 there was no upload endpoint at all, so
        // this money was always approved with an empty document trail.
        assertThatThrownBy(() -> service.approve(requestId, null, ceo()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("เอกสารหลักฐาน");
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
        assertThat(approvedAmountOf(requestId)).isNull();

        // ...and goes through once the evidence exists, so the gate is about evidence and not a
        // blanket refusal.
        attachEvidence(requestId);
        service.approve(requestId, null, ceo());
        assertThat(statusOf(requestId)).isEqualTo("APPROVED");
    }

    @Test
    void evidenceCanOnlyBeAttachedByTheRequesterAndOnlyBeforeADecision() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);

        // A ผู้จัดการ in the same ฝ่าย must not slip documents into someone else's claim (they can
        // no longer even see it); nor may the CEO manufacture the evidence they then approve
        // against.
        assertThatThrownBy(() -> service.requireCanAttach(requestId, salesManager()))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.requireCanAttach(requestId, ceo()))
            .isInstanceOf(ApiException.class);

        // The owner may, while it is still SUBMITTED.
        service.requireCanAttach(requestId, employeeOwner(salesStaffEmployeeId));

        attachEvidence(requestId);
        service.approve(requestId, null, ceo());
        // ...but not after a decision: the evidence a decision rested on must not change under it.
        assertThatThrownBy(() -> service.requireCanAttach(requestId, employeeOwner(salesStaffEmployeeId)))
            .isInstanceOf(ApiException.class);
    }

    @Test
    void evidenceIsNotReadableByAnUnrelatedEmployee() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);
        attachEvidence(requestId);

        // Medical receipts and death certificates: readable by the employee, HR and the CEO -- and
        // nobody else. The ฝ่าย manager used to be on that list and no longer is (see
        // managerCannotReachAnInDivisionTeamMembersWelfareThroughAnyReadPath below).
        assertThatThrownBy(() -> service.listAttachments(requestId, employee(factoryStaffEmployeeId, factoryDivision)))
            .isInstanceOf(ApiException.class);
        assertThat(service.listAttachments(requestId, ceo())).hasSize(1);
        assertThat(service.listAttachments(requestId, hr())).hasSize(1);
    }

    @Test
    void ceoCanRejectFromSubmittedToo() {
        long requestId = submitFuneralRequest(orphanStaffEmployeeId);

        service.reject(requestId, new ReviewSpecialMoneyRequest("ไม่เข้าเงื่อนไข", null, null), ceo());

        // Approve-only would be a trap door: the sole reviewer must also be able to refuse.
        assertThat(statusOf(requestId)).isEqualTo("REJECTED");
        assertThat(approvedAmountOf(requestId)).isNull();
    }

    @Test
    void ceoCanStillClearALegacyManagerApprovedRow() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);
        // Nothing can reach MANAGER_APPROVED through the service any more, so this forces the state
        // a pre-change row would be sitting in. Without the legacy branch such rows would be
        // stranded permanently -- approve() would 409 them as "already reviewed".
        parkInLegacyManagerApprovedState(requestId);
        attachEvidence(requestId);

        service.approve(requestId, null, ceo());

        assertThat(statusOf(requestId)).isEqualTo("APPROVED");
        assertThat(approvedAmountOf(requestId)).isEqualByComparingTo(new BigDecimal("5000"));
    }

    // --- 9. usage() quota scoping ---------------------------------------------

    @Test
    void employeeCannotReadAnotherEmployeesUsageQuota() {
        assertThatThrownBy(() -> service.usage(
                factoryStaffEmployeeId, 2026, employee(salesStaffEmployeeId, salesDivision)))
            .isInstanceOf(ApiException.class);
    }

    // --- 10. cancel() scoping ---------------------------------------------------

    @Test
    void employeeCannotCancelAnotherEmployeesRequest() {
        long requestId = submitFuneralRequest(salesManagerEmployeeId);

        assertThatThrownBy(() -> service.cancel(requestId, null, employee(salesStaffEmployeeId, salesDivision)))
            .isInstanceOf(ApiException.class);
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
    }

    // --- 11. welfare is confidential to each employee (owner ruling, 2026-08-10) ---
    //
    // The cases above already proved a manager cannot reach OUTSIDE their ฝ่าย. The ones below are
    // the ones that matter now: a ผู้จัดการ must not reach a team member INSIDE their own ฝ่าย
    // either. Every one of these passed -- i.e. leaked -- before this change.

    /**
     * THE regression test. One in-division team member, every read path in this service, each
     * asserted to come back empty or 403.
     *
     * <p>Written as one method on purpose: the four paths share a single decision
     * ({@code canAccessEmployee}) but reach the database through four different queries, and a fix
     * applied to {@code list()} alone would leave the other three open. Splitting them into four
     * tests would let three stay green while the suite still looked healthy.
     */
    @Test
    void managerCannotReachAnInDivisionTeamMembersWelfareThroughAnyReadPath() {
        // salesStaff reports into salesManager AND shares salesDivision -- the exact relationship
        // that used to grant division-wide read.
        long requestId = submitFuneralRequest(salesStaffEmployeeId);
        long attachmentId = attachEvidenceReturningId(requestId);

        // (a) the list: zero rows, not "their own plus the team's".
        assertThat(service.list(salesManager(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null))
            .isEmpty();

        // (b) asking for that employee explicitly: 403, not a filtered-to-nothing 200.
        assertThatThrownBy(() -> service.list(
                salesManager(), LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
                salesStaffEmployeeId, null, null))
            .isInstanceOf(ApiException.class);

        // (c) the quota: how much medical/funeral money a colleague has drawn this year is itself
        // confidential, even without the individual rows.
        assertThatThrownBy(() -> service.usage(salesStaffEmployeeId, 2026, salesManager()))
            .isInstanceOf(ApiException.class);

        // (d) the evidence -- the worst of the four. These files are death certificates, birth
        // certificates and medical receipts.
        assertThatThrownBy(() -> service.listAttachments(requestId, salesManager()))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.resolveAttachmentForDownload(attachmentId, salesManager()))
            .isInstanceOf(ApiException.class);

        // ...and the row is still there, so this is scoping and not an accidental delete.
        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
    }

    /**
     * The positive control for the test above. Without this, deleting the read paths entirely would
     * also pass, and "nobody can see anything" is not the requirement -- hr/ceo must still see
     * everything.
     */
    @Test
    void hrAndCeoStillSeeEveryEmployeesWelfareIncludingTheEvidence() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);
        long attachmentId = attachEvidenceReturningId(requestId);

        for (UserPrincipal viewer : List.of(hr(), ceo())) {
            assertThat(service.list(viewer, LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null))
                .extracting(SpecialMoneyRequestDto::id)
                .contains(requestId);
            assertThat(service.usage(salesStaffEmployeeId, 2026, viewer)).isNotNull();
            assertThat(service.listAttachments(requestId, viewer)).hasSize(1);
            assertThat(service.resolveAttachmentForDownload(attachmentId, viewer)).isNotNull();
        }
    }

    /** ...and the employee themselves is of course unaffected. */
    @Test
    void theEmployeeStillSeesTheirOwnWelfareRequest() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);

        assertThat(service.list(
                employee(salesStaffEmployeeId, salesDivision),
                LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31), null, null, null))
            .extracting(SpecialMoneyRequestDto::id)
            .containsExactly(requestId);
    }

    /**
     * Submit-on-behalf is gone, so the manager cannot get at the row by CREATING it either. This is
     * the path that would otherwise defeat the read scoping entirely: file it yourself and you are
     * {@code requested_by_id}.
     */
    @Test
    void managerCannotSubmitOnBehalfOfAnInDivisionTeamMember() {
        long countBefore = requestCountFor(salesStaffEmployeeId);

        assertThatThrownBy(() -> service.submit(
                "AID_FUNERAL",
                funeralRequest(salesStaffEmployeeId, LocalDate.of(2026, 7, 1)),
                salesManager()))
            .isInstanceOf(ApiException.class)
            .hasMessageContaining("ตนเอง");

        assertThat(requestCountFor(salesStaffEmployeeId)).isEqualTo(countBefore);
    }

    /**
     * The picker must agree with the gate above. Offering a name that {@code submit()} then refuses
     * turns a dropdown choice into a 403 -- and, worse, leaks the ฝ่าย roster into a screen whose
     * whole point is now "you, and only you".
     */
    @Test
    void managerEmployeePickerOffersOnlyThemselves() {
        assertThat(service.employeeOptions(salesManager()))
            .extracting(SpecialMoneyEmployeeOption::employeeId)
            .containsExactly(salesManagerEmployeeId);
        assertThat(service.employeeOptions(salesManager()))
            .allSatisfy(option -> assertThat(option.directReport()).isFalse());
    }

    /**
     * The residual path on rows that predate the change: {@code requested_by_id} pointing at
     * somebody other than the employee. {@code cancel()} returns the full DTO, so letting the old
     * filer through would hand them exactly the confidential row this change closes.
     */
    @Test
    void aLegacyOnBehalfFilerCanNoLongerCancelOrAttachToTheRow() {
        long requestId = submitFuneralRequest(salesStaffEmployeeId);
        parkAsLegacyOnBehalfRow(requestId, salesManagerEmployeeId);

        assertThatThrownBy(() -> service.cancel(requestId, null, salesManager()))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.requireCanAttach(requestId, salesManager()))
            .isInstanceOf(ApiException.class);

        assertThat(statusOf(requestId)).isEqualTo("SUBMITTED");
        // The employee is not locked out of their own legacy row by that.
        service.requireCanAttach(requestId, employeeOwner(salesStaffEmployeeId));
    }

    // --- helpers --------------------------------------------------------------

    private long submitFuneralRequest(long employeeId) {
        SpecialMoneyRequestDto created = service.submit(
            "AID_FUNERAL",
            funeralRequest(employeeId, LocalDate.of(2026, 7, 1)),
            employeeOwner(employeeId));
        return created.id();
    }

    private SubmitSpecialMoneyRequest funeralRequest(long employeeId, LocalDate eventDate) {
        return new SubmitSpecialMoneyRequest(
            employeeId,
            eventDate,
            null,
            null,
            BigDecimal.ONE,
            new BigDecimal("5000"),
            "Funeral aid",
            Map.of("relation", "parent"));
    }

    private String statusOf(long requestId) {
        return repository.findById(requestId).orElseThrow().status();
    }

    private BigDecimal approvedAmountOf(long requestId) {
        return repository.findById(requestId).orElseThrow().approvedAmount();
    }

    /**
     * Attaches a row to {@code hr.special_money_request_attachment} directly. AID_FUNERAL is an
     * evidence-required type, so approval now refuses without one -- these tests are about the
     * authorization rules, and the evidence gate has its own coverage.
     */
    private void attachEvidence(long requestId) {
        jdbc.update("""
            INSERT INTO hr.special_money_request_attachment
                (special_money_request_id, file_name, storage_path, mime_type, size_bytes)
            VALUES (:id, 'mor-ana-bat.pdf', '/tmp/test/mor-ana-bat.pdf', 'application/pdf', 1024)
            """, Map.of("id", requestId));
    }

    /** As {@link #attachEvidence}, but hands back the id so the download gate can be exercised. */
    private long attachEvidenceReturningId(long requestId) {
        return jdbc.queryForObject("""
            INSERT INTO hr.special_money_request_attachment
                (special_money_request_id, file_name, storage_path, mime_type, size_bytes)
            VALUES (:id, 'mor-ana-bat.pdf', '/tmp/test/mor-ana-bat.pdf', 'application/pdf', 1024)
            RETURNING attachment_id
            """, Map.of("id", requestId), Long.class);
    }

    /**
     * Repoints {@code requested_by_id} at somebody other than the employee -- the shape of a row
     * filed on-behalf before that capability was removed. The service can no longer produce one,
     * so the gates that used to key on it need this to be testable at all.
     */
    private void parkAsLegacyOnBehalfRow(long requestId, long filerEmployeeId) {
        jdbc.update("""
            UPDATE hr.special_money_request
               SET requested_by_id = :filerId
             WHERE special_money_request_id = :id
            """, Map.of("id", requestId, "filerId", filerEmployeeId));
    }

    /** Forces the state a row written before the manager stage was removed would be sitting in. */
    private void parkInLegacyManagerApprovedState(long requestId) {
        jdbc.update("""
            UPDATE hr.special_money_request
               SET status = 'MANAGER_APPROVED',
                   manager_approved_by = :managerId,
                   manager_approved_at = now()
             WHERE special_money_request_id = :id
            """, Map.of("id", requestId, "managerId", salesManagerEmployeeId));
    }

    /** Read straight from the table, not the DTO: the columns are the thing payroll and audit read. */
    private Long managerApprovedByOf(long requestId) {
        return jdbc.queryForObject(
            "SELECT manager_approved_by FROM hr.special_money_request WHERE special_money_request_id = :id",
            Map.of("id", requestId), Long.class);
    }

    private Long ceoApprovedByOf(long requestId) {
        return jdbc.queryForObject(
            "SELECT ceo_approved_by FROM hr.special_money_request WHERE special_money_request_id = :id",
            Map.of("id", requestId), Long.class);
    }

    private long requestCountFor(long employeeId) {
        return jdbc.queryForObject(
            "SELECT COUNT(*) FROM hr.special_money_request WHERE employee_id = :employeeId",
            Map.of("employeeId", employeeId), Long.class);
    }

    private UserPrincipal hr() {
        return new UserPrincipal(1L, "hr@glr.co.th", "hr", "hr", hrEmployeeId, true,
            LocalDate.now(), false, null, false);
    }

    /**
     * The CEO must carry a real {@code employeeId}: {@code approve()} resolves the actor before any
     * gate runs, so a null one throws "account not linked to an employee" first. The previous
     * fixture had null here, which meant the old ceoCannotApproveRequestStillInSubmittedState was
     * green because of an unlinked account, not because of the authorization rule it named.
     */
    private UserPrincipal ceo() {
        return new UserPrincipal(2L, "ceo@glr.co.th", "ceo", "ceo", ceoEmployeeId, true,
            LocalDate.now(), false, null, false);
    }

    private UserPrincipal salesManager() {
        return new UserPrincipal(3L, "mgr@glr.co.th", "mgr", "employee", salesManagerEmployeeId, true,
            LocalDate.now(), false, salesDivision, true);
    }

    private UserPrincipal employee(long employeeId, long divisionId) {
        return new UserPrincipal(4L, "emp@glr.co.th", "emp", "employee", employeeId, true,
            LocalDate.now(), false, divisionId, false);
    }

    /** The employee submitting their own request -- resolves the caller's own division via the seed data. */
    private UserPrincipal employeeOwner(long employeeId) {
        Long divisionId = employeeId == factoryStaffEmployeeId ? factoryDivision : salesDivision;
        return new UserPrincipal(5L, "owner@glr.co.th", "owner", "employee", employeeId, true,
            LocalDate.now(), false, divisionId, false);
    }

    private long insertDivision(String code, String name) {
        return jdbc.queryForObject("""
            INSERT INTO hr.division (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING division_id
            """, Map.of("code", code, "name", name), Long.class);
    }

    private long insertEmployee(String code, Long divisionId, Long reportsToEmployeeId) {
        return insertEmployee(code, divisionId, reportsToEmployeeId, null);
    }

    /**
     * {@code positionNameTh} is what makes an employee a ผู้จัดการ <em>in the database</em>, which
     * is what {@code ManagerApproverRepository} reads. Leaving it null models a non-manager.
     *
     * <p>It must agree with the {@code manager} flag on the {@link UserPrincipal} the test uses for
     * that person: in production both derive from the same position via
     * {@code DivisionAccessPolicy}, so a fixture where they disagree is testing a state that cannot
     * occur — and would quietly send requests down the wrong approval route.
     */
    private long insertEmployee(String code, Long divisionId, Long reportsToEmployeeId, String positionNameTh) {
        Map<String, Object> params = new java.util.HashMap<>();
        params.put("code", code);
        params.put("divisionId", divisionId);
        params.put("reportsTo", reportsToEmployeeId);
        params.put("hireDate", LocalDate.of(2015, 1, 1));
        params.put("positionId", positionNameTh == null ? null : insertPosition(code, positionNameTh));
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, badge_card_no, first_name_th, last_name_th,
                                     division_id, reports_to_employee_id, position_id, hire_date, is_active)
            VALUES (:code, :code, 'ทดสอบ', :code, :divisionId, :reportsTo, :positionId, :hireDate, TRUE)
            RETURNING employee_id
            """, params, Long.class);
    }

    private long insertPosition(String code, String nameTh) {
        return jdbc.queryForObject("""
            INSERT INTO hr.position (source_code, name_th, is_active)
            VALUES (:code, :name, TRUE) RETURNING position_id
            """, Map.of("code", code, "name", nameTh), Long.class);
    }
}
