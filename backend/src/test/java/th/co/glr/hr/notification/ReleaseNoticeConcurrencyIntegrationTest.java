package th.co.glr.hr.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.audit.AuditLogRepository;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.brand.BrandAssets;
import th.co.glr.hr.mail.Mailer;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Change;
import th.co.glr.hr.notification.ReleaseNoticeDtos.Request;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;

/**
 * At-most-once under real concurrency: two CEO/HR presses racing on real Postgres, each through its own
 * transaction. The per-release advisory lock is what serialises the read of "who is not emailed yet" with the
 * write of the attempt rows; without it both presses see the same people as due and both mail them. These tests
 * are the only ones that can see that lock (single-threaded tests pass without it). First written by the
 * adversarial review of this change (2026-11-17), brought into the suite in fix round 1.
 */
class ReleaseNoticeConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final String RELEASE_ID = "2026-11-17";
    private static final Request REQUEST = new Request(RELEASE_ID, "อัปเดต",
        List.of(new Change("หน้าใหม่", List.of("sales"), "เหตุผลสั้น ๆ")), RELEASE_ID, null);
    private static final Request OPT_IN = new Request(RELEASE_ID, "อัปเดต",
        List.of(new Change("หน้าใหม่", List.of("sales"), "เหตุผลสั้น ๆ")), RELEASE_ID, Boolean.TRUE);

    private Mailer mailer;
    private ReleaseNoticeService service;
    private long hrId;
    private long ceoId;
    private final List<Long> reps = new ArrayList<>();

    @BeforeEach
    void wire() {
        mailer = mock(Mailer.class);
        when(mailer.delivers()).thenReturn(true);
        NotificationEmailService emailService =
            new NotificationEmailService(mailer, new BrandAssets(), "", "", "https://glr-hub.com");
        AuditService audit = new AuditService(new AuditLogRepository(jdbc), new ObjectMapper());
        service = transactional(new ReleaseNoticeService(new EmployeeAuthRepository(jdbc),
            new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP), new ReleaseNoticeRepository(jdbc),
            new ReleaseNoticeDelivery(emailService, audit, new ReleaseNoticeRepository(jdbc), transactionManager),
            audit, transactionManager, Duration.ofMinutes(30)));
        long sa = division("SA", "SA-ฝ่ายขาย");
        long hrDivision = division("HR", "HR-บุคคล");
        long md = division("MD", "MD-ผู้บริหารระดับสูง");
        reps.clear();
        for (int i = 0; i < 6; i++) {
            reps.add(employee("REP-" + i, sa, "rep" + i + "@example.com", "ขาย" + i));
        }
        hrId = employee("HR-1", hrDivision, "hr@example.com", "บุคคล");
        ceoId = employee("CEO-1", md, "ceo@example.com", "ซีอีโอ");
    }

    @Test
    void twoManagersPressingPublishAtOnce_mailEachPersonOnce() throws Exception {
        race(() -> service.publishWithEmail(actor(hrId, "hr"), REQUEST),
            () -> service.publishWithEmail(actor(ceoId, "ceo"), REQUEST));

        assertThat(count("RELEASE_NOTE_EMAILED")).isEqualTo(6);
        assertThat(count("RELEASE_NOTE_EMAIL_SENT")).isEqualTo(6);
        verify(mailer, times(6)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM hr.notification WHERE type = 'RELEASE_NOTE'", Map.of(),
            Integer.class)).isEqualTo(6);
    }

    @Test
    void publishRacingTheRetry_mailsEachPersonOnce() throws Exception {
        race(() -> service.publishWithEmail(actor(hrId, "hr"), REQUEST),
            () -> service.email(actor(ceoId, "ceo"), REQUEST));

        assertThat(count("RELEASE_NOTE_EMAILED")).isEqualTo(6);
        verify(mailer, times(6)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
    }

    @Test
    void twoOptInResendsAtOnce_mailEachUnconfirmedPersonOnce() throws Exception {
        for (long rep : reps) {
            jdbc.update("""
                INSERT INTO hr.audit_log (action, entity, entity_id, after_json, at)
                VALUES ('RELEASE_NOTE_EMAILED', 'employee', :id, jsonb_build_object('releaseId', :releaseId),
                        now() - interval '2 hours')
                """, Map.of("id", rep, "releaseId", RELEASE_ID));
        }
        assertThat(service.audience(actor(hrId, "hr"), REQUEST).email().unconfirmed()).isEqualTo(6);

        race(() -> service.email(actor(hrId, "hr"), OPT_IN), () -> service.email(actor(ceoId, "ceo"), OPT_IN));

        verify(mailer, times(6)).sendHtml(anyString(), anyString(), anyString(), anyString(), anyList());
        assertThat(count("RELEASE_NOTE_EMAILED")).isEqualTo(12);
    }

    private List<Object> race(Supplier<Object> first, Supplier<Object> second) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            Future<Object> a = pool.submit(() -> {
                go.await();
                return first.get();
            });
            Future<Object> b = pool.submit(() -> {
                go.await();
                return second.get();
            });
            go.countDown();
            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private int count(String action) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM hr.audit_log WHERE action = :action",
            Map.of("action", action), Integer.class);
    }

    private static UserPrincipal actor(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "@example.com", "ผู้ทดสอบ", role, employeeId, true,
            LocalDate.of(2026, 1, 1), false, null, false);
    }

    private long division(String code, String name) {
        return jdbc.queryForObject("INSERT INTO hr.division (source_code, name_th) VALUES (:code, :name) "
            + "RETURNING division_id", Map.of("code", code, "name", name), Long.class);
    }

    private long employee(String code, long divisionId, String email, String firstName) {
        Map<String, Object> params = new HashMap<>();
        params.put("code", code);
        params.put("first", firstName);
        params.put("email", email);
        params.put("division", divisionId);
        return jdbc.queryForObject("""
            INSERT INTO hr.employee (employee_code, first_name_th, email, division_id, is_active, can_create_quotation)
            VALUES (:code, :first, :email, :division, true, false) RETURNING employee_id
            """, params, Long.class);
    }
}
