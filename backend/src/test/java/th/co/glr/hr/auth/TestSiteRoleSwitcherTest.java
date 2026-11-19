package th.co.glr.hr.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.common.ApiException;

class TestSiteRoleSwitcherTest {
    private final EmployeeAuthRepository employees = mock(EmployeeAuthRepository.class);
    private final AuditService audit = mock(AuditService.class);

    private static final EmployeeLoginRecord SALES = record(1L, "demo.sales@demo.invalid", true);
    private static final EmployeeLoginRecord CEO = record(2L, "demo.ceo@demo.invalid", true);
    private static final EmployeeLoginRecord HR = record(3L, "demo.hr@demo.invalid", true);

    private static EmployeeLoginRecord record(long id, String email, boolean active) {
        return new EmployeeLoginRecord(id, "C" + id, email, "N" + id, active, 1L, "SALES", "Sales", null,
            LocalDate.now(), null, false, false);
    }

    private TestSiteRoleSwitcher switcher(String accounts) {
        return new TestSiteRoleSwitcher(employees, audit, accounts);
    }

    private TestSiteRoleSwitcher switcher() {
        return switcher(" Demo.Sales@demo.invalid , demo.ceo@demo.invalid ");
    }

    private static MockHttpServletRequest signedInAs(EmployeeLoginRecord employee) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        SessionContext.signIn(request.getSession(true), SessionAccountResolver.principalOf(employee));
        return request;
    }

    private static UserPrincipal sessionUser(MockHttpServletRequest request) {
        return (UserPrincipal) request.getSession(false).getAttribute(SessionContext.SESSION_USER_KEY);
    }

    @Test
    void listedAccount_switchesToAnotherListedAccount_rotatesSession_andIsAudited() {
        when(employees.findByEmail("demo.ceo@demo.invalid")).thenReturn(Optional.of(CEO));
        MockHttpServletRequest request = signedInAs(SALES);
        request.setRequestedSessionId(request.getSession().getId());
        String before = request.getSession().getId();

        switcher().switchTo(" DEMO.CEO@demo.invalid ", sessionUser(request), request);

        assertThat(sessionUser(request).id()).isEqualTo(2L);
        assertThat(request.getSession().getId()).isNotEqualTo(before);
        verify(audit).record(any(UserPrincipal.class), eq("TEST_SITE_SWITCH"), eq("employee"), eq(2L), any(), any());
    }

    @Test
    void unlistedCaller_isForbidden_andNothingChanges() {
        MockHttpServletRequest request = signedInAs(HR);

        assertThatThrownBy(() -> switcher().switchTo("demo.ceo@demo.invalid", sessionUser(request), request))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(sessionUser(request).id()).isEqualTo(3L);
        verifyNoInteractions(audit);
    }

    @Test
    void unlistedTarget_isForbidden() {
        when(employees.findByEmail("demo.hr@demo.invalid")).thenReturn(Optional.of(HR));
        MockHttpServletRequest request = signedInAs(SALES);

        assertThatThrownBy(() -> switcher().switchTo("demo.hr@demo.invalid", sessionUser(request), request))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
        assertThat(sessionUser(request).id()).isEqualTo(1L);
        verifyNoInteractions(audit);
    }

    @Test
    void inactiveTarget_isRejected() {
        when(employees.findByEmail("demo.ceo@demo.invalid")).thenReturn(Optional.of(record(2L, "demo.ceo@demo.invalid", false)));
        MockHttpServletRequest request = signedInAs(SALES);

        assertThatThrownBy(() -> switcher().switchTo("demo.ceo@demo.invalid", sessionUser(request), request))
            .isInstanceOf(ApiException.class);
        assertThat(sessionUser(request).id()).isEqualTo(1L);
        verifyNoInteractions(audit);
    }

    @Test
    void accounts_listsConfiguredActiveAccounts_forAListedCaller_only() {
        when(employees.findByEmail("demo.sales@demo.invalid")).thenReturn(Optional.of(SALES));
        when(employees.findByEmail("demo.ceo@demo.invalid")).thenReturn(Optional.of(CEO));

        assertThat(switcher().accounts(SessionAccountResolver.principalOf(SALES)))
            .extracting(TestSiteRoleSwitcher.Account::email)
            .containsExactly("demo.sales@demo.invalid", "demo.ceo@demo.invalid");
        assertThatThrownBy(() -> switcher().accounts(SessionAccountResolver.principalOf(HR)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void blankConfig_forbidsEveryone() {
        MockHttpServletRequest request = signedInAs(SALES);
        assertThatThrownBy(() -> switcher("  ").switchTo("demo.ceo@demo.invalid", sessionUser(request), request))
            .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> switcher("").accounts(sessionUser(request))).isInstanceOf(ApiException.class);
    }

    @Test
    void targetWithMustChangePassword_isSignedInWithoutTheFlag() {
        EmployeeLoginRecord flagged = new EmployeeLoginRecord(2L, "C2", "demo.ceo@demo.invalid", "N2", true, 1L,
            "SALES", "Sales", null, LocalDate.now(), null, true, false);
        when(employees.findByEmail("demo.ceo@demo.invalid")).thenReturn(Optional.of(flagged));
        MockHttpServletRequest request = signedInAs(SALES);

        switcher().switchTo("demo.ceo@demo.invalid", sessionUser(request), request);

        assertThat(sessionUser(request).id()).isEqualTo(2L);
        assertThat(sessionUser(request).mustChangePassword()).isFalse();
    }
}
