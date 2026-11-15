package th.co.glr.hr.salesboard;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.VisitDtos.CreateVisitRequest;
import th.co.glr.hr.salesboard.VisitDtos.VisitDto;
import th.co.glr.hr.ticket.DealActivityKind;
import th.co.glr.hr.ticket.DealActivityRequest;
import th.co.glr.hr.ticket.TicketService;

/**
 * Visits (spec 2026-11-15 §7.6). Who may do what:
 * <ul>
 *   <li>plan / log / list own — sales, sales_manager, ceo; the owner is always the caller;</li>
 *   <li>a visit on a deal — the caller must be able to write that deal ({@link TicketService#requireDealWriteAccess}:
 *       its owner/keyer, or sales_manager / ceo);</li>
 *   <li>done / cancel — the visit's owner, or sales_manager / ceo;</li>
 *   <li>team list — sales_manager, ceo.</li>
 * </ul>
 * Marking a visit done on a deal writes {@code deal_activity(SITE_VISIT, today)} through
 * {@link TicketService#addActivity}, the one path that resets the {@link QuietRule} clock. "Today" is the
 * Asia/Bangkok date. List ranges are inclusive and at most {@value #MAX_RANGE_DAYS} days.
 */
@Service
public class VisitService {
    static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");
    static final Set<String> VISIT_ROLES = Set.of("sales", "sales_manager", "ceo");
    static final Set<String> TEAM_ROLES = Set.of("sales_manager", "ceo");
    static final int MAX_RANGE_DAYS = 31;
    static final int MAX_CONTACT_LENGTH = 200;
    static final int MAX_NOTE_LENGTH = 2000;

    private static final String FORBIDDEN = "ไม่มีสิทธิ์เข้าถึงรายการนี้";

    private final VisitRepository visits;
    private final TicketService tickets;
    private final Clock clock;

    @Autowired
    public VisitService(VisitRepository visits, TicketService tickets) {
        this(visits, tickets, Clock.system(BANGKOK));
    }

    VisitService(VisitRepository visits, TicketService tickets, Clock clock) {
        this.visits = visits;
        this.tickets = tickets;
        this.clock = clock;
    }

    public List<VisitDto> listOwn(LocalDate from, LocalDate to, UserPrincipal actor) {
        requireRole(actor, VISIT_ROLES);
        requireRange(from, to);
        return visits.findOwn(actor.id(), from, to);
    }

    public List<VisitDto> listTeam(LocalDate from, LocalDate to, UserPrincipal actor) {
        requireRole(actor, TEAM_ROLES);
        requireRange(from, to);
        return visits.findAll(from, to);
    }

    /** The caller's visits in the ISO week (Mon–Sun) containing today — MyWork's นัดเข้าพบสัปดาห์นี้. */
    public List<VisitDto> ownThisWeek(UserPrincipal actor) {
        LocalDate monday = weekStart(today());
        return listOwn(monday, monday.plusDays(6), actor);
    }

    /** Every rep's visits this ISO week — TeamOverview's ตารางเข้าพบสัปดาห์นี้. */
    public List<VisitDto> teamThisWeek(UserPrincipal actor) {
        LocalDate monday = weekStart(today());
        return listTeam(monday, monday.plusDays(6), actor);
    }

    @Transactional
    public VisitDto create(CreateVisitRequest request, UserPrincipal actor) {
        requireRole(actor, VISIT_ROLES);
        String contact = request.contactName() == null ? "" : request.contactName().trim();
        if (contact.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุว่าพบใคร");
        }
        if (contact.length() > MAX_CONTACT_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ชื่อผู้ที่พบยาวเกิน " + MAX_CONTACT_LENGTH + " ตัวอักษร");
        }
        if (request.plannedDate() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "กรุณาระบุวันที่");
        }
        boolean done = Boolean.TRUE.equals(request.done());
        if (done && !request.plannedDate().equals(today())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "บันทึกเข้าพบได้เฉพาะวันนี้ — นัดล่วงหน้าให้ใช้ “นัดเข้าพบ”");
        }
        String note = cleanNote(request.note());
        if (request.ticketId() != null) {
            tickets.requireDealWriteAccess(request.ticketId(), actor);
        }
        long id = visits.insert(actor.id(), request.ticketId(), contact, request.plannedDate(),
            request.plannedTime(), note, done);
        if (done && request.ticketId() != null) {
            logSiteVisit(request.ticketId(), note, actor);
        }
        return require(id);
    }

    @Transactional
    public VisitDto markDone(long visitId, String note, UserPrincipal actor) {
        requireRole(actor, VISIT_ROLES);
        VisitDto visit = requireWritable(visitId, actor);
        String cleaned = cleanNote(note);
        if (visit.ticketId() != null) {
            // Checked before the update so a refusal leaves the visit PLANNED even outside a transaction.
            tickets.requireDealWriteAccess(visit.ticketId(), actor);
        }
        if (visits.markDone(visitId, cleaned) == 0) {
            throw notPlanned(visit);
        }
        if (visit.ticketId() != null) {
            logSiteVisit(visit.ticketId(), cleaned != null ? cleaned : visit.note(), actor);
        }
        return require(visitId);
    }

    @Transactional
    public VisitDto cancel(long visitId, UserPrincipal actor) {
        requireRole(actor, VISIT_ROLES);
        VisitDto visit = requireWritable(visitId, actor);
        if (visits.cancel(visitId) == 0) {
            throw notPlanned(visit);
        }
        return require(visitId);
    }

    LocalDate today() {
        return LocalDate.now(clock.withZone(BANGKOK));
    }

    /** Monday of the ISO week containing {@code day}. */
    static LocalDate weekStart(LocalDate day) {
        return day.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
    }

    private void logSiteVisit(long ticketId, String note, UserPrincipal actor) {
        tickets.addActivity(ticketId, new DealActivityRequest(today(), DealActivityKind.SITE_VISIT, note), actor);
    }

    /** The visit, when the actor is its owner or a sales_manager / ceo; else 403 (and 404 when it does not exist). */
    private VisitDto requireWritable(long visitId, UserPrincipal actor) {
        VisitDto visit = visits.findById(visitId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบนัดเข้าพบนี้"));
        boolean owner = visit.ownerId() == actor.id();
        if (!owner && !TEAM_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
        return visit;
    }

    private VisitDto require(long visitId) {
        return visits.findById(visitId).orElseThrow();
    }

    private static ApiException notPlanned(VisitDto visit) {
        String state = VisitDtos.DONE.equals(visit.status()) ? "บันทึกว่าไปแล้ว" : "ยกเลิกแล้ว";
        return new ApiException(HttpStatus.CONFLICT, "นัดนี้" + state + " จึงเปลี่ยนไม่ได้");
    }

    private static String cleanNote(String note) {
        if (note == null || note.isBlank()) {
            return null;
        }
        String trimmed = note.trim();
        if (trimmed.length() > MAX_NOTE_LENGTH) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "โน้ตยาวเกิน " + MAX_NOTE_LENGTH + " ตัวอักษร");
        }
        return trimmed;
    }

    private static void requireRange(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ช่วงวันที่ไม่ถูกต้อง");
        }
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "ดูได้ครั้งละไม่เกิน " + MAX_RANGE_DAYS + " วัน");
        }
    }

    private static void requireRole(UserPrincipal actor, Set<String> allowed) {
        if (!allowed.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, FORBIDDEN);
        }
    }
}
