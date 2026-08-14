package th.co.glr.hr.deposit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.customer.CustomerRepository;
import th.co.glr.hr.customerquotation.CustomerQuotationRepository;
import th.co.glr.hr.employee.EmployeeCodeGenerator;
import th.co.glr.hr.employee.EmployeeReferenceRepository;
import th.co.glr.hr.employee.EmployeeRepository;
import th.co.glr.hr.employee.UpsertEmployeeRequest;
import th.co.glr.hr.notification.NotificationRepository;
import th.co.glr.hr.notification.SalesNotificationMailer;
import th.co.glr.hr.support.AbstractPostgresIntegrationTest;
import th.co.glr.hr.ticket.CreateTicketRequest;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketStatus;

/**
 * {@code DepositNoticeService#issue} used to render the deposit notice <b>inside</b> its own
 * transaction. Rendering the PDF shells out to LibreOffice — {@code LibreOfficePdfConverter} forks
 * {@code soffice} and blocks on {@code proc.waitFor(120, SECONDS)} — so for up to two minutes the
 * transaction sat there holding a pooled connection and, far more importantly, <b>a row lock on the
 * single {@code sales.document_sequence} row for {@code (DEPOSIT_NOTICE, this Thai year)}</b> that
 * {@code nextDocNumber} increments a few statements earlier.
 *
 * <p>That one row is the serialization point for <em>every deposit notice issued in the whole
 * year</em>. Holding it across an external process turns a slow render into a queue nobody can see:
 * the next rep to press "ออกเอกสาร" simply blocks until the first rep's {@code soffice} exits or
 * times out, with no error, no log line and nothing in the UI to explain it.
 *
 * <p><b>Why the render was safe to move, in one paragraph.</b> It participates in no invariant.
 * Both {@code toPdf} and {@code toXlsx} return {@code byte[]} that {@code issue} <em>discards</em>;
 * nothing durable is written ({@code LibreOfficePdfConverter} deletes its temp files in a
 * {@code finally}), and the only DB write in that block is {@code setFilePaths(docId, "rendered",
 * "rendered")} — two literal flag strings, surfacing as {@code DepositNoticeDto.hasPdf/hasXlsx}, which
 * no production frontend code and no other backend test reads. Downloads ({@code getPdf}/{@code
 * getXlsx}) re-render from the persisted document snapshot every time and never consult those
 * columns. The doc number and the {@code DRAFT -> ISSUED} compare-and-set both stay exactly where
 * they were, in the transaction, so neither the sequence race nor the double-issue race that
 * {@code DepositNoticeRepository#issue}'s Javadoc describes is affected.
 *
 * <p><b>What each test proves.</b> {@link #issuingRunsTheRenderOnlyAfterTheTransactionHasCommitted()}
 * is the failing-first case — on unmodified code an independent session still saw {@code DRAFT} while
 * the render was running, i.e. the render was inside the open transaction. It asserts the render RAN
 * as well, because "no other session was blocked by us" is trivially true of a render that was simply
 * deleted. {@link
 * #theIssueResponseNoLongerCarriesTheRenderFlagsButAFreshReadDoes()} pins the one deliberate,
 * API-observable consequence of the move. {@link #aRenderFailureStillLeavesTheDocumentIssued()} pins
 * the swallow semantics, which matter more after the move than before: an exception escaping a
 * {@code TransactionSynchronization#afterCommit} callback propagates to the caller of {@code commit},
 * so the try/catch has to live INSIDE the deferred action or a LibreOffice timeout would start
 * failing a request that previously succeeded. {@link #withNoTransactionAtAllTheRenderStillRunsInline()}
 * is the control: the un-proxied path must still render immediately, or a non-transactional caller
 * silently stops producing documents.
 *
 * <p><b>On the harness.</b> {@link AbstractPostgresIntegrationTest} runs with no Spring context, so a
 * hand-wired service's {@code @Transactional} is inert and a test written the obvious way would
 * observe "no transaction" for the wrong reason. Every transactional case below is driven through
 * {@link AbstractPostgresIntegrationTest#transactional}, which builds a real AOP proxy from the
 * production annotation — and the control test above is what proves that proxy is what makes the
 * difference.
 */
class DepositNoticeIssueRenderOutsideTransactionIntegrationTest extends AbstractPostgresIntegrationTest {

    private DepositNoticeRepository docs;
    private TicketRepository tickets;
    private DepositNoticeRenderer renderer;
    private UserPrincipal salesRep;

    /**
     * What an INDEPENDENT database session saw at the moment the renderer was called — the whole
     * point of the fixture, and the only honest way to ask "was the transaction still open?".
     *
     * <p>Deliberately not {@code TransactionSynchronizationManager.isActualTransactionActive()}:
     * that reads {@code true} inside an {@code afterCommit} callback too, because Spring runs the
     * synchronizations before it unbinds the transaction resources. It would fail this test against
     * correct code. Asking another session what it can see tests the property that actually matters
     * — whether the row and its locks are committed and out of everyone else's way.
     */
    private final AtomicReference<String> statusSeenByAnotherSession = new AtomicReference<>();
    private final AtomicInteger renderCalls = new AtomicInteger();

    @BeforeEach
    void wireFixture() throws Exception {
        docs = new DepositNoticeRepository(jdbc);
        tickets = new TicketRepository(jdbc);

        // A stub renderer rather than the real one: this test is about WHEN the render runs, not
        // what it produces, and the real toPdf needs a LibreOffice install that CI may not have.
        // It records whether a transaction was active at the moment issue() called it.
        renderer = mock(DepositNoticeRenderer.class);
        when(renderer.toPdf(any())).thenAnswer(invocation -> {
            DepositNoticeDto rendering = invocation.getArgument(0);
            renderCalls.incrementAndGet();
            statusSeenByAnotherSession.set(statusFromAnIndependentSession(rendering.id()));
            return new byte[] {1};
        });
        when(renderer.toXlsx(any())).thenReturn(new byte[] {2});

        long repId = new EmployeeRepository(jdbc, new EmployeeReferenceRepository(jdbc),
            new EmployeeCodeGenerator(jdbc))
            .create(new UpsertEmployeeRequest(
                null, null, "พนักงานขาย ออกเอกสาร", null, null, null, null, null, null, null,
                "rep-dnrender-" + UUID.randomUUID() + "@glr.co.th", null, "SA", "แผนกขาย", "แผนกขาย",
                null, null, null, "ACT", new BigDecimal("30000"),
                null, null, null, null, null, null, null));
        salesRep = new UserPrincipal(repId, repId + "@glr.co.th", "พนักงานขาย ออกเอกสาร", "sales", repId,
            true, LocalDate.now(), false, null, false);
    }

    @Test
    void issuingRunsTheRenderOnlyAfterTheTransactionHasCommitted() {
        long docId = draftDepositNotice();
        DepositNoticeService service = transactional(service());

        service.issue(docId, salesRep);

        assertThat(renderCalls.get())
            .as("guard against a vacuous pass: 'another session already saw the row' is trivially "
                + "unobservable for a render that was deleted outright, which would silently stop "
                + "the document being produced at all")
            .isOne();
        assertThat(statusSeenByAnotherSession.get())
            .as("rendering forks LibreOffice and blocks up to 120s. Inside the transaction that "
                + "time is spent holding a pooled connection AND the one sales.document_sequence "
                + "row for (DEPOSIT_NOTICE, this Thai year) that every other deposit notice issued "
                + "this year must queue behind. An independent session seeing ISSUED proves the "
                + "commit had already happened before the render started, so nothing was waiting "
                + "on us; seeing DRAFT would mean the render is running inside the open transaction")
            .isEqualTo("ISSUED");
    }

    @Test
    void theIssueResponseNoLongerCarriesTheRenderFlagsButAFreshReadDoes() {
        long docId = draftDepositNotice();
        DepositNoticeService service = transactional(service());

        DepositNoticeDto response = service.issue(docId, salesRep);

        assertThat(response.hasPdf())
            .as("the deliberate, API-observable consequence of deferring the render: issue() builds "
                + "its response inside the transaction, before the post-commit render has set the "
                + "flags. Verified unread by production — only demoSales.js and mockApi.js mention "
                + "hasPdf, and both fabricate their own values; downloads re-render on demand and "
                + "never consult pdf_path/xlsx_path")
            .isFalse();
        assertThat(docs.findById(docId).orElseThrow().hasPdf())
            .as("a fresh read after the deferred render must show the flags set — otherwise the "
                + "move quietly turned 'render later' into 'never render'")
            .isTrue();
        assertThat(docs.findById(docId).orElseThrow().hasXlsx()).isTrue();
    }

    @Test
    void aRenderFailureStillLeavesTheDocumentIssued() {
        // doAnswer, not when(...): when(renderer.toPdf(any())) would CALL toPdf to build the
        // matcher, firing the counting stub installed in wireFixture and leaving renderCalls at 1
        // before this test has done anything.
        doAnswer(invocation -> {
            renderCalls.incrementAndGet();
            throw new RuntimeException("LibreOffice timed out after 120 s");
        }).when(renderer).toPdf(any());
        long docId = draftDepositNotice();
        DepositNoticeService service = transactional(service());

        // Must not throw. An exception escaping afterCommit propagates to the caller of commit, so
        // without the try/catch living inside the deferred action a LibreOffice timeout would start
        // failing an issue() that previously succeeded — a strictly worse outcome than the bug fixed.
        DepositNoticeDto response = service.issue(docId, salesRep);

        assertThat(renderCalls.get()).isOne();
        assertThat(response.docNumber())
            .as("the render is non-fatal, exactly as before this moved out of the transaction: the "
                + "doc number is minted and kept")
            .isNotNull();
        assertThat(docs.findById(docId).orElseThrow().status()).isEqualTo("ISSUED");
        assertThat(docs.findById(docId).orElseThrow().hasPdf())
            .as("a failed render must leave the flags unset — they mean 'this rendered once', and "
                + "setting them anyway would be a lie a reader cannot detect")
            .isFalse();
    }

    @Test
    void withNoTransactionAtAllTheRenderStillRunsInline() {
        long docId = draftDepositNotice();
        // Raw, un-proxied: no transaction, so there is no commit to defer to. Mirrors
        // FileStorageService#deleteOnCommit's own "no transaction means do it now" branch, and is
        // the control that proves the proxied tests above are not passing trivially.
        DepositNoticeService service = service();

        service.issue(docId, salesRep);

        assertThat(renderCalls.get()).isOne();
        assertThat(docs.findById(docId).orElseThrow().hasPdf())
            .as("with no synchronization active the deferred hook would never run, so the render "
                + "must happen inline — otherwise a non-transactional caller silently stops "
                + "producing documents entirely")
            .isTrue();
    }

    // ── wiring ────────────────────────────────────────────────────────────────────────────

    /**
     * Reads the notice's status on a connection that is NOT the caller's, by running the query on a
     * separate thread: Spring binds a transaction's connection to the thread that started it, so a
     * fresh thread gets a fresh connection out of the pool and therefore its own MVCC snapshot.
     *
     * <p>A plain {@code SELECT} never blocks on a row-exclusive lock in Postgres — it just reads the
     * last committed version — so when the render runs inside the open transaction this returns
     * {@code DRAFT} promptly rather than hanging. The short timeout is belt-and-braces: a wedged
     * build is far more expensive to diagnose than a fast red.
     */
    private String statusFromAnIndependentSession(long docId) {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            return executor.submit(() -> jdbc.queryForObject(
                "SELECT status FROM sales.deposit_notice WHERE deposit_notice_id = :id",
                Map.of("id", docId), String.class)).get(10, TimeUnit.SECONDS);
        } catch (Exception exception) {
            throw new IllegalStateException("independent-session read failed", exception);
        } finally {
            executor.shutdownNow();
        }
    }

    private DepositNoticeService service() {
        return new DepositNoticeService(docs, tickets, new NotificationRepository(jdbc, SalesNotificationMailer.NO_OP),
            renderer, new RemainingInvoiceRenderer(), new CustomerRepository(jdbc),
            new CustomerQuotationRepository(jdbc));
    }

    /**
     * A DRAFT deposit notice on a ticket that satisfies every gate in {@code issue}: owned by the
     * fixture rep, {@code quotation_issued}, payment track at {@code CUSTOMER_CONFIRMED}, and the
     * {@code REQUIRED} deposit policy + {@code ACTIVE} lifecycle that V51 defaults every new ticket
     * to. Built with two direct column writes rather than by driving the whole pricing →
     * quotation → order-confirmation chain (as {@code DepositNoticeIssueGuardIntegrationTest} does),
     * because none of that chain bears on WHEN the render runs — the question under test.
     */
    private long draftDepositNotice() {
        long ticketId = tickets.create(
            new CreateTicketRequest("ดีล ออกใบแจ้งรับมัดจำ", "NORMAL", null, null, null, null, null, null, null),
            tickets.nextTicketCode(), salesRep.id(), salesRep.name());
        jdbc.update("""
            UPDATE sales.ticket SET status = :status, payment_status = 'CUSTOMER_CONFIRMED'
             WHERE ticket_id = :id
            """, Map.of("status", TicketStatus.QUOTATION_ISSUED, "id", ticketId));

        List<DepositNoticeItemRequest> items = List.of(new DepositNoticeItemRequest(
            1, "กระเบื้อง ทดสอบ", new BigDecimal("10"), "แผ่น",
            new BigDecimal("100.00"), null, new BigDecimal("100.00")));
        return docs.createDraft(ticketId, new DepositNoticeDraftRequest(
            "ACME", "0100000000000", "Bangkok", "Showroom", "REF-RENDER",
            new BigDecimal("0.50"), List.of(), items), items);
    }
}
