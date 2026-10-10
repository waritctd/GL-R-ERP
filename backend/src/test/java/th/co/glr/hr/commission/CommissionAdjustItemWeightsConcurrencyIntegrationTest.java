package th.co.glr.hr.commission;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.mock.web.MockMultipartFile;
import th.co.glr.hr.attachment.AttachmentRepository;
import th.co.glr.hr.attachment.FileStorageService;
import th.co.glr.hr.audit.AuditService;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.CeoApproverRepository;
import th.co.glr.hr.notification.NotificationService;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.DealStage;
import th.co.glr.hr.ticket.ItemWeightMultiplierRequest;
import th.co.glr.hr.ticket.TicketItemDto;
import th.co.glr.hr.ticket.TicketItemRequest;
import th.co.glr.hr.ticket.TicketRepository;

/**
 * {@link CommissionService#adjustItemWeights} must serialise concurrent adjusters of the SAME
 * commission record (row lock on {@code sales.commission_record}). Without it, under READ
 * COMMITTED, two managers re-weighting DIFFERENT lines of one deal interleave: each re-reads the
 * deal's items before the other commits, and the last writer freezes an
 * {@code effective_weight_multiplier} (what payroll reads via COALESCE) that ignores the other
 * manager's line -- while the item weights themselves end up correct, so nothing looks wrong.
 *
 * <p><b>Why the race is forced, not hoped for.</b> A plain two-thread start is a tiny window and
 * would pass un-fixed most runs; that would be a fake green. {@link
 * #concurrentAdjustersOfDifferentLines_frozenWeightReflectsBothLines} instead pauses adjuster 1
 * (a Mockito spy on {@link TicketRepository}) right AFTER its re-read of the items and BEFORE its
 * frozen-weight write, for up to {@code PAUSE_SECONDS} or until adjuster 2 has finished -- the
 * exact losing interleaving. Un-fixed, adjuster 2 runs to completion inside that pause and adjuster
 * 1 then overwrites it with a stale blend. Fixed, adjuster 2 is blocked on the row lock, the
 * wait times out, adjuster 1 commits, and adjuster 2 then re-reads both lines. Both paths go
 * through the REAL service behind a REAL {@code @Transactional} proxy ({@link #transactional}) --
 * the harness's hand-wired services have no AOP proxy, so without that wrapper the method would
 * run with autocommit and the row lock would be released at once (see CLAUDE.md / the memory note
 * that {@code @Transactional} is inert in this suite).
 *
 * <p>{@code @EnabledIf} is repeated on the class because it is not {@code @Inherited}.
 */
@EnabledIf(
    value = "th.co.glr.hr.support.PostgresTestSupport#isAvailable",
    disabledReason = "No TEST_DB_URL and no Docker available for Testcontainers Postgres")
class CommissionAdjustItemWeightsConcurrencyIntegrationTest extends AbstractPostgresIntegrationTest {
    private static final long PAUSE_SECONDS = 3;
    private static final LocalDate INVOICE = LocalDate.of(2027, 2, 15);

    private TicketRepository tickets;
    private CommissionRepository commissions;
    private CommissionCalculator calculator;
    private CommissionService service;      // real @Transactional proxy
    private CommissionService plainService; // hand-wired, only for createFromDeal setup
    private long repId;
    private UserPrincipal managerActor;
    private UserPrincipal accountActor;

    @BeforeEach
    void wire() {
        tickets = spy(new TicketRepository(jdbc));
        commissions = new CommissionRepository(jdbc);
        calculator = new CommissionCalculator();
        plainService = new CommissionService(
            commissions, new CommissionAttachmentRepository(jdbc), calculator,
            new FileStorageService("/tmp/glr-adjust-weights-concurrency-uploads"),
            mock(AuditService.class), mock(NotificationService.class), tickets,
            new AttachmentRepository(jdbc), new CeoApproverRepository(jdbc));
        service = transactional(plainService);
        EmployeeRepository employees = new EmployeeRepository(
            jdbc, new EmployeeReferenceRepository(jdbc), new EmployeeCodeGenerator(jdbc));
        repId = employee(employees, "พนักงานขาย ล็อกแถว", "lock-rep@glr.co.th", "SL");
        managerActor = principal(employee(employees, "ผู้จัดการ ล็อกแถว", "lock-mgr@glr.co.th", "SA"), "sales_manager");
        accountActor = principal(employee(employees, "บัญชี ล็อกแถว", "lock-acct@glr.co.th", "ACCT"), "account");
    }

    @Test
    void concurrentAdjustersOfDifferentLines_frozenWeightReflectsBothLines() throws Exception {
        long ticketId = dealWithTwoEqualLines();
        long commissionId = createRecord(ticketId);
        List<TicketItemDto> items = tickets.findById(ticketId).orElseThrow().items();
        long lineA = items.get(0).id();
        long lineB = items.get(1).id();

        CountDownLatch adjuster1Paused = new CountDownLatch(1);
        CountDownLatch adjuster2Finished = new CountDownLatch(1);
        AtomicInteger adjuster1FindByIdCalls = new AtomicInteger();
        // adjustItemWeights calls tickets.findById twice: (1) line-ownership check, (2) the
        // post-update re-read that feeds the blend. Pause adjuster 1 right after call (2).
        doAnswer(invocation -> {
            Object result = invocation.callRealMethod();
            if ("adjuster-1".equals(Thread.currentThread().getName())
                    && adjuster1FindByIdCalls.incrementAndGet() == 2) {
                adjuster1Paused.countDown();
                adjuster2Finished.await(PAUSE_SECONDS, TimeUnit.SECONDS);
            }
            return result;
        }).when(tickets).findById(anyLong());

        AtomicReference<Throwable> failure1 = new AtomicReference<>();
        AtomicReference<Throwable> failure2 = new AtomicReference<>();
        Thread adjuster1 = new Thread(() -> {
            try {
                service.adjustItemWeights(commissionId, weights(lineA, 2), managerActor);
            } catch (Throwable t) {
                failure1.set(t);
            }
        }, "adjuster-1");
        Thread adjuster2 = new Thread(() -> {
            try {
                service.adjustItemWeights(commissionId, weights(lineB, 2), managerActor);
            } catch (Throwable t) {
                failure2.set(t);
            } finally {
                adjuster2Finished.countDown();
            }
        }, "adjuster-2");

        adjuster1.start();
        assertThat(adjuster1Paused.await(30, TimeUnit.SECONDS)).as("adjuster 1 reached its pause").isTrue();
        adjuster2.start();
        adjuster1.join(60_000);
        adjuster2.join(60_000);

        assertThat(failure1.get()).as("adjuster 1 failure").isNull();
        assertThat(failure2.get()).as("adjuster 2 failure").isNull();
        // Both lines really did end at weight 2 -- the item weights are never the lie here...
        assertThat(tickets.findById(ticketId).orElseThrow().items())
            .extracting(TicketItemDto::weightMultiplier).containsExactly(2, 2);
        // ...the FROZEN blend, which payroll reads, must reflect BOTH: two equal lines at 2x -> 2.0.
        BigDecimal expected = calculator.itemDerivedWeight(
            inputsOf(tickets.findById(ticketId).orElseThrow().items()), new BigDecimal("100000.00")).orElseThrow();
        assertThat(expected).isEqualByComparingTo("2.000000");
        assertThat(effectiveWeight(commissionId))
            .as("effective_weight_multiplier must be the blend of BOTH lines' final weights (2.0), "
                + "not the stale blend of one (1.5)")
            .isEqualByComparingTo(expected);
    }

    @Test
    void anAdjusterHoldingTheRecordLock_parksASecondAdjusterBeforeItWritesAnything_untilItCommits() throws Exception {
        long ticketId = dealWithTwoEqualLines();
        long commissionId = createRecord(ticketId);
        long lineB = tickets.findById(ticketId).orElseThrow().items().get(1).id();

        CountDownLatch lockHeld = new CountDownLatch(1);
        CountDownLatch releaseLock = new CountDownLatch(1);
        AtomicReference<Throwable> holderFailure = new AtomicReference<>();
        // Holder: a separate connection/transaction that has taken the record's row lock -- exactly
        // what a concurrent adjuster that got there first holds.
        Thread holder = new Thread(() -> {
            try {
                transactionTemplate.executeWithoutResult(status -> {
                    jdbc.queryForObject(
                        "SELECT commission_id FROM sales.commission_record WHERE commission_id = :id FOR UPDATE",
                        Map.of("id", commissionId), Long.class);
                    lockHeld.countDown();
                    try {
                        releaseLock.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            } catch (Throwable t) {
                holderFailure.set(t);
            }
        }, "lock-holder");
        holder.start();
        assertThat(lockHeld.await(30, TimeUnit.SECONDS)).isTrue();

        CountDownLatch adjusterDone = new CountDownLatch(1);
        AtomicReference<Throwable> adjusterFailure = new AtomicReference<>();
        Thread adjuster = new Thread(() -> {
            try {
                service.adjustItemWeights(commissionId, weights(lineB, 2), managerActor);
            } catch (Throwable t) {
                adjusterFailure.set(t);
            } finally {
                adjusterDone.countDown();
            }
        }, "adjuster");
        adjuster.start();

        // While the lock is held the adjuster must NOT get through.
        assertThat(adjusterDone.await(2, TimeUnit.SECONDS))
            .as("adjustItemWeights must wait for the commission_record row lock").isFalse();
        // ...and it must be parked BEFORE it has written anything. Un-fixed, the adjuster would
        // already have UPDATEd line B (its transaction holds that row's lock, uncommitted) and only
        // stall afterwards on the final frozen-weight UPDATE; a NOWAIT probe on line B tells the two
        // apart without depending on timing.
        org.assertj.core.api.Assertions.assertThatCode(() -> jdbc.queryForObject(
                "SELECT item_id FROM sales.ticket_item WHERE item_id = :id FOR UPDATE NOWAIT",
                Map.of("id", lineB), Long.class))
            .as("a parked adjuster has written nothing: line B must not be row-locked by it")
            .doesNotThrowAnyException();
        releaseLock.countDown();
        assertThat(adjusterDone.await(30, TimeUnit.SECONDS)).as("adjuster completes once the lock is released").isTrue();
        holder.join(30_000);
        assertThat(holderFailure.get()).isNull();
        assertThat(adjusterFailure.get()).isNull();
        assertThat(tickets.findById(ticketId).orElseThrow().items())
            .extracting(TicketItemDto::weightMultiplier).containsExactly(1, 2);
    }

    // ─────────────────────────────────────────────────────────────────────────────────────

    private static ItemWeightMultiplierRequest weights(long itemId, int weight) {
        return new ItemWeightMultiplierRequest(List.of(new ItemWeightMultiplierRequest.Line(itemId, weight)));
    }

    private BigDecimal effectiveWeight(long commissionId) {
        return jdbc.queryForObject(
            "SELECT effective_weight_multiplier FROM sales.commission_record WHERE commission_id = :id",
            Map.of("id", commissionId), BigDecimal.class);
    }

    private static List<CommissionCalculator.ItemStockWeightInput> inputsOf(List<TicketItemDto> items) {
        return items.stream().map(i -> new CommissionCalculator.ItemStockWeightInput(
            i.qty(), i.qtyFromStock(), i.approvedPrice(), i.proposedPrice(), i.weightMultiplier())).toList();
    }

    /** Two identical fully-from-stock lines (10 x 1,000), both weight 1 -> frozen blend starts NULL. */
    private long dealWithTwoEqualLines() {
        List<TicketItemRequest> requests = new ArrayList<>();
        for (int n = 1; n <= 2; n++) {
            requests.add(new TicketItemRequest("Brand" + n, "Model" + n, "สีขาว", null, "60x60", "Factory A",
                BigDecimal.TEN, null, "PIECE", null, null, null, new BigDecimal("1000"), "THB"));
        }
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีลล็อกแถว", "NORMAL", "ลูกค้าล็อกแถว", null, null, null, null, null, requests),
            tickets.nextTicketCode(), repId, "พนักงานขาย ล็อกแถว");
        tickets.updateSalesStage(ticketId, DealStage.CLOSED_PAID);
        for (TicketItemDto item : tickets.findById(ticketId).orElseThrow().items()) {
            jdbc.update("""
                UPDATE sales.ticket_item
                   SET approved_price = 1000, qty_from_stock = 10, weight_multiplier = 1
                 WHERE item_id = :id
                """, new MapSqlParameterSource().addValue("id", item.id()));
        }
        return ticketId;
    }

    private long createRecord(long ticketId) {
        CommissionRecord created = plainService.createFromDeal(
            ticketId, "INV-LOCK-" + UUID.randomUUID().toString().substring(0, 8), INVOICE,
            new BigDecimal("100000.00"), null, null, null, null, null, null, null,
            new MockMultipartFile("invoiceAttachment", "invoice.pdf", "application/pdf", "pdf".getBytes()),
            accountActor);
        assertThat(created.status()).isEqualTo(CommissionStatus.SUBMITTED);
        assertThat(effectiveWeight(created.id())).as("all lines at 1x -> nothing frozen yet").isNull();
        return created.id();
    }

    private long employee(EmployeeRepository employees, String name, String email, String divisionCode) {
        return employees.create(new UpsertEmployeeRequest(
            null, null, name, null, null, null, null, null, null, null,
            email, null, divisionCode, divisionCode, divisionCode,
            "เจ้าหน้าที่", null, null, "ACT", new BigDecimal("30000"), null, null, null, null, null, null, null));
    }

    private static UserPrincipal principal(long employeeId, String role) {
        return new UserPrincipal(employeeId, role + "-lock@glr.co.th", role, role, employeeId, true,
            LocalDate.of(2020, 1, 1), false, null, false);
    }
}
