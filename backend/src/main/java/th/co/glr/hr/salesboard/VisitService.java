package th.co.glr.hr.salesboard;

import th.co.glr.hr.ticket.QuietRule;
import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.salesboard.VisitDtos.CreateVisitRequest;
import th.co.glr.hr.salesboard.VisitDtos.VisitDto;
import th.co.glr.hr.ticket.DealLifecycle;
import th.co.glr.hr.ticket.TicketService;
import th.co.glr.hr.ticket.TicketSummaryDto;

/**
 * Visits (spec 2026-11-15 §7.6). Who may do what:
 * <ul>
 *   <li>plan / log / list own — sales, sales_manager, ceo; the owner is always the caller;</li>
 *   <li>a visit on a deal — the caller must be able to write that deal ({@link TicketService#requireDealWriteAccess}:
 *       its owner/keyer, or sales_manager / ceo);</li>
 *   <li>done / cancel — the visit's owner (even after the deal moved to another rep), or sales_manager / ceo;
 *       done never ahead of the planned date;</li>
 *   <li>team list — sales_manager, ceo;</li>
 *   <li>a deal's planned visits (slice 6.5) — whoever may open the deal, narrowed to the visits their own list or the
 *       team list already shows them;</li>
 *   <li>ตามต่อ on a done visit (slice 6.4) — sets the deal's next follow-up date only where the visit's activity
 *       lands on the deal ({@link TicketService#requireVisitFollowUpWritable}).</li>
 * </ul>
 * Marking a visit done on a deal writes {@code deal_activity(SITE_VISIT, today)} attributed to the visit's owner
 * through {@link TicketService#recordVisitActivity}, which resets the {@link QuietRule} clock; until then a PLANNED
 * visit dated today or later already holds the deal out of quiet ({@link QuietRule#upcomingVisitSql}). "Today" is the
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
    static final int DUPLICATE_LOG_SECONDS = 60;
    /** A plan further ahead than this is refused (review NEW-1). */
    static final int MAX_DAYS_AHEAD = 180;

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
        if (request.plannedDate().isAfter(today().plusDays(MAX_DAYS_AHEAD))) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "นัดล่วงหน้าได้ไม่เกิน " + MAX_DAYS_AHEAD + " วัน");
        }
        boolean done = Boolean.TRUE.equals(request.done());
        if (done && !request.plannedDate().equals(today())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "บันทึกเข้าพบได้เฉพาะวันนี้ — นัดล่วงหน้าให้ใช้ “นัดเข้าพบ”");
        }
        String note = cleanNote(request.note());
        if (request.ticketId() != null) {
            TicketSummaryDto deal = tickets.requireDealWriteAccess(request.ticketId(), actor);
            if (!DealLifecycle.ACTIVE.equals(deal.lifecycle())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "งานนี้ไม่ได้เปิดอยู่ จึงนัดเข้าพบในงานนี้ไม่ได้");
            }
        }
        LocalDate nextFollowUpAt = request.nextFollowUpAt();
        if (nextFollowUpAt != null) {
            // Slice 6.4: ตามต่อ belongs to a visit that happened (the log dialog), on a deal, and only where the visit's
            // activity lands on that deal. All checked before anything is written.
            if (!done) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "ตั้งวันตามต่อได้เมื่อบันทึกว่าเข้าพบแล้วเท่านั้น");
            }
            requireFollowUpTarget(request.ticketId());
            tickets.requireVisitFollowUpWritable(request.ticketId(), actor.id(), nextFollowUpAt, today(), actor);
        }
        if (done) {
            // A double tap or a retried request on a slow phone must not log the same visit twice (review LOW).
            Optional<VisitDto> same = visits.findRecentDoneLog(actor.id(), request.ticketId(), contact,
                request.plannedDate(), DUPLICATE_LOG_SECONDS);
            if (same.isPresent()) {
                return same.get();
            }
        }
        long id = visits.insert(actor.id(), request.ticketId(), contact, request.plannedDate(),
            request.plannedTime(), note, done);
        if (done && request.ticketId() != null) {
            // Same rule as a tick (review NEW-A): only the deal's current owner's visit goes onto the deal. A keyer,
            // or a manager logging their own visit on a rep's deal, keeps it on the visit (dealMovedToName says so).
            tickets.recordVisitActivity(request.ticketId(), today(), note, actor.id(), true, nextFollowUpAt);
        }
        return require(id);
    }

    /**
     * Slice 6.5: the deal's PLANNED visits for the deal page's history — the intersection of two existing rules, so it
     * exposes no visit its caller cannot already read: the caller must be able to open the deal
     * ({@link TicketService#requireDealReadAccess}, the rule of GET /api/tickets/{id}), and the rows are scoped exactly
     * as this service's lists are — every owner's for sales_manager / ceo ({@link #listTeam}), the caller's own for
     * sales ({@link #listOwn}). The owner scope is in the repository's WHERE. A visit outside it is absent, not
     * redacted. Order: {@link VisitRepository}'s list ORDER BY; no LIMIT (the lists have none either).
     */
    public List<VisitDto> plannedOnDeal(long ticketId, UserPrincipal actor) {
        requireRole(actor, VISIT_ROLES);
        tickets.requireDealReadAccess(ticketId, actor);
        return TEAM_ROLES.contains(actor.role())
            ? visits.findPlannedOnDeal(ticketId)
            : visits.findPlannedOnDealOwnedBy(ticketId, actor.id());
    }

    @Transactional
    public VisitDto markDone(long visitId, String note, UserPrincipal actor) {
        return markDone(visitId, note, null, actor);
    }

    /**
     * Ticks a visit done. Slice 6.4: an optional {@code nextFollowUpAt} (ตามต่อ) also sets the visit's deal's next
     * follow-up date — refused (400) on a visit with no deal, and otherwise only where
     * {@link TicketService#requireVisitFollowUpWritable} allows it. Checked before the tick, so a refusal writes nothing.
     */
    @Transactional
    public VisitDto markDone(long visitId, String note, LocalDate nextFollowUpAt, UserPrincipal actor) {
        requireRole(actor, VISIT_ROLES);
        VisitDto visit = requireWritable(visitId, actor);
        String cleaned = cleanNote(note);
        if (VisitDtos.PLANNED.equals(visit.status()) && visit.plannedDate().isAfter(today())) {
            throw new ApiException(HttpStatus.CONFLICT, "ยังไม่ถึงวันนัด จึงบันทึกว่าเข้าพบแล้วไม่ได้");
        }
        if (nextFollowUpAt != null) {
            requireFollowUpTarget(visit.ticketId());
            tickets.requireVisitFollowUpWritable(visit.ticketId(), visit.ownerId(), nextFollowUpAt, today(), actor);
        }
        if (visits.markDone(visitId, cleaned) == 0) {
            throw notPlanned(visit);
        }
        if (visit.ticketId() != null) {
            // The visit's owner went, so the activity is theirs; whoever ticked on their behalf is noted (review M3).
            String activityNote = cleaned != null ? cleaned : visit.note();
            if (visit.ownerId() != actor.id()) {
                activityNote = (activityNote == null ? "" : activityNote + " · ") + "บันทึกโดย " + actor.name();
            }
            // Written only while the visit's owner still holds the deal (review NEW-2); otherwise the response's
            // dealMovedToName tells the UI the tick stayed on the visit.
            tickets.recordVisitActivity(visit.ticketId(), today(), activityNote, visit.ownerId(), true, nextFollowUpAt);
        }
        return require(visitId);
    }

    /** A follow-up date is the deal's; a visit with no deal has nowhere to put it (slice 6.4: refused, not ignored). */
    private static void requireFollowUpTarget(Long ticketId) {
        if (ticketId == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "การเข้าพบนี้ไม่ได้อยู่ในงาน จึงตั้งวันตามต่อไม่ได้");
        }
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
