package th.co.glr.hr.dealquotation;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionAccountResolver;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.dealquotation.DealQuotationDtos.DealQuotationDto;
import th.co.glr.hr.dealquotation.DealQuotationRequests.ApproveRequest;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.ticket.QuotationStatus;

/**
 * Who may create / approve a deal quotation, with every actor's principal produced by {@link
 * SessionAccountResolver} from a real employee row (never a hand-written role string). Guards the
 * 2026-10-14 ruling that a can_create_quotation holder outside ฝ่ายขาย acts as {@code sales}: the
 * ฝ่ายขาย grant holder (จินตนา) and the CEO must be unaffected, and acting as sales must not
 * confer approval rights.
 */
class QuotationRolesFromResolvedPrincipalIntegrationTest extends AbstractDealDirectQuotationIntegrationTest {

    private EmployeeRepository employees;
    private SessionAccountResolver resolver;

    @BeforeEach
    void wireResolver() {
        employees = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        resolver = new SessionAccountResolver(new EmployeeAuthRepository(jdbc));
    }

    // Regression guard: passes with or without the roleFor grant rule.
    @Test
    void salesDivisionAssistantManagerWithGrant_isSalesManager_createsOnAnotherRepsDeal_andApproves() {
        UserPrincipal jintana = resolved("ผช.ผจก.ขาย", "jintana-rq@glr.co.th", "SA", "SA-ฝ่ายขาย",
            "ผู้ช่วยผู้จัดการฝ่ายขาย", true);
        assertThat(jintana.role()).isEqualTo("sales_manager");
        long othersDeal = deal(salesActor);

        DealQuotationDto created = quotationService.create(othersDeal, draft(), jintana);
        assertThat(created.docStatus()).isEqualTo(QuotationStatus.DRAFT);

        DealQuotationDto pending = quotationService.submit(
            quotationService.create(othersDeal, draft(), salesActor).id(), salesActor);
        assertThat(pending.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);
        assertThat(quotationService.approve(pending.id(), new ApproveRequest(null), jintana).docStatus())
            .isEqualTo(QuotationStatus.APPROVED);
    }

    // Regression guard: passes with or without the roleFor grant rule.
    @Test
    void executiveResolvesToCeo_andApprovesAPendingQuotation() {
        UserPrincipal ceo = resolved("กรรมการ", "ceo-rq@glr.co.th", "MD", "MD-ผู้บริหารระดับสูง",
            "กรรมการผู้จัดการ", false);
        assertThat(ceo.role()).isEqualTo("ceo");
        DealQuotationDto pending = quotationService.submit(
            quotationService.create(deal(salesActor), draft(), salesActor).id(), salesActor);

        assertThat(quotationService.approve(pending.id(), new ApproveRequest(null), ceo).docStatus())
            .isEqualTo(QuotationStatus.APPROVED);
    }

    // Documents today's fact: role ceo WITHOUT the grant is not in EDIT_ROLES, so it cannot create.
    @Test
    void today_ceoWithoutGrantCannotCreateADealQuotation_403() {
        UserPrincipal ceo = resolved("กรรมการ", "ceo-rq2@glr.co.th", "MD", "MD-ผู้บริหารระดับสูง",
            "กรรมการผู้จัดการ", false);
        long deal = deal(salesActor);

        refusal(() -> quotationService.create(deal, draft(), ceo), HttpStatus.FORBIDDEN);
        assertThat(quotationCount(deal)).isZero();
    }

    @Test
    void qcGrantHolderActingAsSales_createsAndSubmitsOnHerOwnDeal_butIsRefusedApproval() {
        UserPrincipal qc = resolved("QC ผู้ถือสิทธิ์", "qc-rq@glr.co.th", "QC", "QC-QC&ISO", "พนักงาน", true);
        assertThat(qc.role()).isEqualTo("sales");
        long ownDeal = deal(qc);

        DealQuotationDto submitted = quotationService.submit(quotationService.create(ownDeal, draft(), qc).id(), qc);
        assertThat(submitted.docStatus()).isEqualTo(QuotationStatus.PENDING_APPROVAL);

        refusal(() -> quotationService.approve(submitted.id(), new ApproveRequest(null), qc), HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject("SELECT doc_status FROM sales.quotation WHERE quotation_id = :id",
            Map.of("id", submitted.id()), String.class)).isEqualTo(QuotationStatus.PENDING_APPROVAL);
    }

    private UserPrincipal resolved(String name, String email, String divCode, String divName, String position,
                                   boolean grant) {
        long id = createEmployee(employees, name, email, divCode, divName, position);
        jdbc.update("UPDATE hr.employee SET can_create_quotation = :g WHERE employee_id = :id",
            Map.of("g", grant, "id", id));
        return resolver.current(id).orElseThrow();
    }
}
