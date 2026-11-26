package th.co.glr.hr.digest;

import jakarta.servlet.http.HttpSession;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;

/** Admin-only: send one test digest to a given address, whatever the enabled flag says. */
@RestController
@RequestMapping("/api/admin/weekly-digest")
public class WeeklyDigestController {
    public record TestRequest(String kind, String to, LocalDate weekStart, Long employeeId) {}

    private final WeeklyDigestService service;
    private final SessionContext sessionContext;
    private final EmployeeAuthRepository employees;

    public WeeklyDigestController(WeeklyDigestService service, SessionContext sessionContext,
                                  EmployeeAuthRepository employees) {
        this.service = service;
        this.sessionContext = sessionContext;
        this.employees = employees;
    }

    @PostMapping("/test")
    Map<String, Object> test(@RequestBody TestRequest request, HttpSession session) {
        UserPrincipal actor = sessionContext.requireUser(session);
        if (!employees.isAdmin(actor.id())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
        if (request.to() == null || !request.to().contains("@")
            || !java.util.Set.of("account", "import", "sales", "pending").contains(request.kind())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ระบุประเภท (account/import/sales/pending) และอีเมลปลายทางให้ถูกต้อง");
        }
        var subject = service.sendTest(request.kind(), request.to().trim(), request.weekStart(), request.employeeId());
        return Map.of("sent", subject.isPresent(), "subject", subject.orElse(""));
    }
}
