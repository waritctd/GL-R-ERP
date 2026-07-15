package th.co.glr.hr.payroll;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiExceptionHandler;

/**
 * Standalone MockMvc test of {@link PayrollController}'s request binding, validation, session
 * enforcement (401), month parsing (400), and the day-1 normalization it applies before delegating.
 *
 * <p>Note: {@code @PreAuthorize} role gating (HR/CEO-only) is NOT enforced by standalone MockMvc
 * (no Spring Security filter chain). That authorization boundary — e.g. a wrong-role session getting
 * 403 on {@code GET /api/payroll} — is covered by
 * {@link th.co.glr.hr.config.SecurityAuthorizationIntegrationTest}, which boots the real
 * SecurityFilterChain. This test therefore focuses on the controller's own logic only.
 */
class PayrollControllerTest {
    private final PayrollService payrollService = mock(PayrollService.class);
    private final PayslipDistributionService payslipDistributionService = mock(PayslipDistributionService.class);
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new PayrollController(payrollService, payslipDistributionService, new SessionContext()))
        .setControllerAdvice(new ApiExceptionHandler())
        .setValidator(validator())
        .build();

    @Test
    void currentOrPreviewDelegatesForAValidMonth() throws Exception {
        when(payrollService.currentOrPreview(any(LocalDate.class), any(UserPrincipal.class)))
            .thenReturn(period(7L, LocalDate.of(2026, 7, 1)));

        mvc.perform(get("/api/payroll?payrollMonth=2026-07").session(sessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.period.id").value(7))
            .andExpect(jsonPath("$.period.payrollMonth").value("2026-07-01"));

        verify(payrollService).currentOrPreview(eqMonth(LocalDate.of(2026, 7, 1)), any(UserPrincipal.class));
    }

    @Test
    void missingPayrollMonthIsRejectedWith400() throws Exception {
        mvc.perform(get("/api/payroll").session(sessionFor("hr")))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(payrollService);
    }

    @Test
    void blankPayrollMonthIsRejectedWith400() throws Exception {
        // An empty value binds to "" (present param, so no MissingServletRequestParameter);
        // parseMonth's blank guard rejects it with the "required" message.
        mvc.perform(get("/api/payroll?payrollMonth=").session(sessionFor("hr")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("payrollMonth is required"));

        verifyNoInteractions(payrollService);
    }

    @Test
    void invalidPayrollMonthIsRejectedWith400() throws Exception {
        mvc.perform(get("/api/payroll?payrollMonth=2026-13").session(sessionFor("hr")))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Invalid payroll month"));

        verifyNoInteractions(payrollService);
    }

    @Test
    void currentOrPreviewRequiresASession() throws Exception {
        mvc.perform(get("/api/payroll?payrollMonth=2026-07"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.message").value("Not authenticated"));

        verifyNoInteractions(payrollService);
    }

    @Test
    void previewNormalizesTheMonthToDayOneBeforeDelegating() throws Exception {
        when(payrollService.preview(any(ProcessPayrollRequest.class), any(UserPrincipal.class)))
            .thenReturn(period(null, LocalDate.of(2026, 7, 1)));

        mvc.perform(post("/api/payroll/preview")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payrollMonth\":\"2026-07-15\",\"inputs\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.period.payrollMonth").value("2026-07-01"));

        ArgumentCaptor<ProcessPayrollRequest> captor = ArgumentCaptor.forClass(ProcessPayrollRequest.class);
        verify(payrollService).preview(captor.capture(), any(UserPrincipal.class));
        assertThat(captor.getValue().payrollMonth()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void processNormalizesTheMonthToDayOneBeforeDelegating() throws Exception {
        when(payrollService.process(any(ProcessPayrollRequest.class), any(UserPrincipal.class)))
            .thenReturn(period(7L, LocalDate.of(2026, 7, 1)));

        mvc.perform(post("/api/payroll/process")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"payrollMonth\":\"2026-07-31\",\"inputs\":[]}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.period.id").value(7));

        ArgumentCaptor<ProcessPayrollRequest> captor = ArgumentCaptor.forClass(ProcessPayrollRequest.class);
        verify(payrollService).process(captor.capture(), any(UserPrincipal.class));
        assertThat(captor.getValue().payrollMonth()).isEqualTo(LocalDate.of(2026, 7, 1));
    }

    @Test
    void processRejectsABodyMissingThePayrollMonthWith400() throws Exception {
        mvc.perform(post("/api/payroll/process")
                .session(sessionFor("hr"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inputs\":[]}"))
            .andExpect(status().isBadRequest());

        verifyNoInteractions(payrollService);
    }

    @Test
    void bankExportSetsAttachmentDispositionAndPlainText() throws Exception {
        when(payrollService.bankExport(org.mockito.ArgumentMatchers.eq(7L), any(UserPrincipal.class)))
            .thenReturn("GLR_PAYROLL|2026-07-01|0|0\n");

        mvc.perform(get("/api/payroll/7/bank-export").session(sessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("attachment"),
                    org.hamcrest.Matchers.containsString("glr-payroll-7.txt"))))
            .andExpect(content().string("GLR_PAYROLL|2026-07-01|0|0\n"));

        verify(payrollService).bankExport(org.mockito.ArgumentMatchers.eq(7L), any(UserPrincipal.class));
    }

    @Test
    void payslipPdfSetsAttachmentDispositionAndPdfContentType() throws Exception {
        when(payrollService.payslipPdf(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(55L), any(UserPrincipal.class)))
            .thenReturn("%PDF-test".getBytes());

        mvc.perform(get("/api/payroll/7/lines/55/payslip.pdf").session(sessionFor("hr")))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("attachment"),
                    org.hamcrest.Matchers.containsString("glr-payslip-7-55.pdf"))))
            .andExpect(content().bytes("%PDF-test".getBytes()));

        verify(payrollService).payslipPdf(org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(55L), any(UserPrincipal.class));
    }

    @Test
    void ownPayslipPdfDelegatesUsingSessionUserOnly() throws Exception {
        when(payrollService.ownPayslipPdf(org.mockito.ArgumentMatchers.eq(7L), any(UserPrincipal.class)))
            .thenReturn("%PDF-own".getBytes());

        mvc.perform(get("/api/payroll/7/payslip/me").session(sessionFor("employee")))
            .andExpect(status().isOk())
            .andExpect(content().contentType(MediaType.APPLICATION_PDF))
            .andExpect(header().string("Content-Disposition",
                org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("attachment"),
                    org.hamcrest.Matchers.containsString("glr-my-payslip-7.pdf"))))
            .andExpect(content().bytes("%PDF-own".getBytes()));

        verify(payrollService).ownPayslipPdf(org.mockito.ArgumentMatchers.eq(7L), any(UserPrincipal.class));
    }

    @Test
    void distributePayslipsReturnsAcceptedAndStartsAsyncSend() throws Exception {
        PayslipDistributionResponse response = new PayslipDistributionResponse(7L, 3, 1, 2);
        when(payslipDistributionService.queueDistribution(org.mockito.ArgumentMatchers.eq(7L), any(UserPrincipal.class)))
            .thenReturn(response);

        mvc.perform(post("/api/payroll/7/distribute").session(sessionFor("hr")))
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.periodId").value(7))
            .andExpect(jsonPath("$.totalLines").value(3))
            .andExpect(jsonPath("$.alreadySent").value(1))
            .andExpect(jsonPath("$.queued").value(2));

        verify(payslipDistributionService).queueDistribution(org.mockito.ArgumentMatchers.eq(7L), any(UserPrincipal.class));
        verify(payslipDistributionService).sendPayslips(org.mockito.ArgumentMatchers.eq(7L), any(UserPrincipal.class));
    }

    private static LocalDate eqMonth(LocalDate expected) {
        return org.mockito.ArgumentMatchers.eq(expected);
    }

    private MockHttpSession sessionFor(String role) {
        MockHttpSession session = new MockHttpSession();
        session.setAttribute(SessionContext.SESSION_USER_KEY,
            new UserPrincipal(1L, role + "@glr.co.th", role, role, 1L, true, LocalDate.now(), false, null, false));
        return session;
    }

    private LocalValidatorFactoryBean validator() {
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return validator;
    }

    private PayrollPeriodDto period(Long id, LocalDate month) {
        return new PayrollPeriodDto(
            id, month, month, month.withDayOfMonth(month.lengthOfMonth()),
            month.withDayOfMonth(month.lengthOfMonth()), id == null ? "PREVIEW" : "PROCESSED",
            OffsetDateTime.now(), 1L, 0,
            BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO,
            List.of());
    }
}
