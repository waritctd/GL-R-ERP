package th.co.glr.hr.payroll.obligation;

import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import org.springframework.http.HttpStatus;
import th.co.glr.hr.payroll.PayrollDeductionKind;
import th.co.glr.hr.payroll.obligation.PayrollDeductionShortfallDtos.PayrollDeductionShortfallDto;

/**
 * HR/CEO read access to the shortfall ledger (issue #376). Read-only by design -- a shortfall row
 * is written exclusively by {@link DeductionObligationService#recordGarnishmentShortfalls} as a
 * side effect of {@code PayrollService#process}, never edited directly; correcting one means
 * reprocessing the payroll month that produced it.
 */
@Service
public class PayrollDeductionShortfallService {
    private static final Set<String> VIEW_ROLES = Set.of("hr", "ceo");

    private final PayrollDeductionShortfallRepository repository;

    public PayrollDeductionShortfallService(PayrollDeductionShortfallRepository repository) {
        this.repository = repository;
    }

    public List<PayrollDeductionShortfallDto> list(Long employeeId, PayrollDeductionKind kind, UserPrincipal actor) {
        requireRole(actor, VIEW_ROLES);
        return repository.findAll(employeeId, kind);
    }

    private void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (actor == null || !allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Forbidden");
        }
    }
}
