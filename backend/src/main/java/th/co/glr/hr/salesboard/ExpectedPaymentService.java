package th.co.glr.hr.salesboard;

import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.ExpectedPaymentDtos.ExpectedPaymentDto;
import th.co.glr.hr.salesboard.ExpectedPaymentDtos.ExpectedPaymentRequest;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketRepository;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Expected payments (spec 2026-11-15 §7.7). Who may do what:
 * <ul>
 *   <li>write (create / edit / cancel) — the deal's owner/keyer (sales), sales_manager, ceo: exactly
 *       {@link TicketService#requireDealWriteAccess}, the gate visits and deal activities use;</li>
 *   <li>read — the writers above, plus account when the deal is inside account's finance scope
 *       ({@link TicketRepository#isInAccountScope}, the predicate that filters account's deal list);</li>
 *   <li>everyone else (import, hr, employee, another rep) — 403.</li>
 * </ul>
 * Only an OPEN row can be edited or cancelled; RECEIVED (set when receipts reach it —
 * TicketRepository#applyReceiptToExpectedPayments) and CANCELLED are final (409). Every write needs an ACTIVE deal. Money is THB, at most 2 dp, &gt; 0.
 */
@Service
public class ExpectedPaymentService {
    static final Set<String> READ_ONLY_ROLES = Set.of("account");
    static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";

    private final ExpectedPaymentRepository payments;
    private final TicketService tickets;
    private final TicketRepository ticketRows;

    public ExpectedPaymentService(ExpectedPaymentRepository payments, TicketService tickets,
                                  TicketRepository ticketRows) {
        this.payments = payments;
        this.tickets = tickets;
        this.ticketRows = ticketRows;
    }

    public List<ExpectedPaymentDto> list(long ticketId, UserPrincipal actor) {
        requireReadAccess(ticketId, actor);
        return payments.findByTicket(ticketId);
    }

    @Transactional
    public ExpectedPaymentDto create(long ticketId, ExpectedPaymentRequest request, UserPrincipal actor) {
        requireActive(requireWriteAccess(ticketId, actor));
        Valid v = validate(request);
        long id = payments.insert(ticketId, v.kind(), v.amount(), v.week(), actor.id());
        return payments.findOnTicket(ticketId, id).orElseThrow();
    }

    @Transactional
    public ExpectedPaymentDto update(long ticketId, long id, ExpectedPaymentRequest request, UserPrincipal actor) {
        requireActive(requireWriteAccess(ticketId, actor));
        ExpectedPaymentDto row = requireRow(ticketId, id);
        Valid v = validate(request);
        // Review N3 (probe Q1): what already came in on a row is fixed — the plan cannot go below it, nor change kind.
        if (row.receivedAmount().signum() > 0) {
            if (v.amount().compareTo(row.receivedAmount()) < 0) {
                throw new ApiException(HttpStatus.CONFLICT, "รับเงินรายการนี้มาแล้ว "
                    + row.receivedAmount().toPlainString() + " บาท จึงลดยอดให้ต่ำกว่านั้นไม่ได้");
            }
            if (!v.kind().equals(row.kind())) {
                throw new ApiException(HttpStatus.CONFLICT, "รายการนี้รับเงินมาบางส่วนแล้ว จึงเปลี่ยนประเภทไม่ได้");
            }
        }
        if (payments.updateOpen(ticketId, id, v.kind(), v.amount(), v.week()) == 0) {
            throw notOpen(row);
        }
        return payments.findOnTicket(ticketId, id).orElseThrow();
    }

    @Transactional
    public ExpectedPaymentDto cancel(long ticketId, long id, UserPrincipal actor) {
        requireActive(requireWriteAccess(ticketId, actor));
        ExpectedPaymentDto row = requireRow(ticketId, id);
        if (payments.cancelOpen(ticketId, id) == 0) {
            throw notOpen(row);
        }
        return payments.findOnTicket(ticketId, id).orElseThrow();
    }

    /** Every write needs an ACTIVE deal: rows on an ON_HOLD / DORMANT / closed deal are read-only (409). */
    private static void requireActive(TicketSummaryDto s) {
        if (!DealLifecycle.ACTIVE.equals(s.lifecycle())) {
            throw new ApiException(HttpStatus.CONFLICT,
                "งานนี้ไม่ได้อยู่ระหว่างดำเนินการ จึงแก้ไขเงินที่คาดว่าจะเข้าไม่ได้");
        }
    }

    private record Valid(String kind, BigDecimal amount, LocalDate week) {}

    private static Valid validate(ExpectedPaymentRequest request) {
        if (request == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ต้องระบุข้อมูลเงินที่คาดว่าจะเข้า");
        }
        String kind = request.kind() == null ? null : request.kind().trim().toUpperCase();
        if (!ExpectedPaymentDtos.KINDS.contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ประเภทต้องเป็น มัดจำ / ส่งของ / ส่วนที่เหลือ");
        }
        BigDecimal amount = request.amount();
        if (amount == null || amount.signum() <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "จำนวนเงินต้องมากกว่า 0");
        }
        if (amount.stripTrailingZeros().scale() > 2 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "จำนวนเงินไม่ถูกต้อง (ทศนิยมไม่เกิน 2 ตำแหน่ง)");
        }
        LocalDate week = request.expectedWeek();
        if (week == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาเลือกสัปดาห์ที่คาด");
        }
        if (week.getDayOfWeek() != DayOfWeek.MONDAY) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "สัปดาห์ที่คาดต้องเป็นวันจันทร์ของสัปดาห์นั้น");
        }
        return new Valid(kind, amount.setScale(2, java.math.RoundingMode.UNNECESSARY), week);
    }

    /** 404 when the deal does not exist; 403 unless a writer, an oversight reader, or account with the deal inside its finance scope. */
    private void requireReadAccess(long ticketId, UserPrincipal actor) {
        if (READ_ONLY_ROLES.contains(actor.role())) {
            ticketRows.findById(ticketId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบงานนี้"));
            if (!ticketRows.isInAccountScope(ticketId)) {
                throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
            }
            return;
        }
        tickets.requireExpectedPaymentsRead(ticketId, actor);
    }

    private TicketSummaryDto requireWriteAccess(long ticketId, UserPrincipal actor) {
        tickets.requireDealWriteAccess(ticketId, actor);
        return ticketRows.findById(ticketId).orElseThrow().summary();
    }

    private ExpectedPaymentDto requireRow(long ticketId, long id) {
        return payments.findOnTicket(ticketId, id)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบรายการเงินที่คาดว่าจะเข้านี้"));
    }

    private static ApiException notOpen(ExpectedPaymentDto row) {
        String state = ExpectedPaymentDtos.RECEIVED.equals(row.status()) ? "รับเงินแล้ว" : "ยกเลิกแล้ว";
        return new ApiException(HttpStatus.CONFLICT, "รายการนี้" + state + " จึงแก้ไขหรือยกเลิกไม่ได้");
    }
}
