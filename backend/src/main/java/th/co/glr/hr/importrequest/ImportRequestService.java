package th.co.glr.hr.importrequest;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.common.ApiException;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.BrandLines;
import th.co.glr.hr.importrequest.ImportRequestQueryRepository.TicketSnapshot;

/**
 * Generates the ใบขอซื้อ (F-SM-001) for a deal, on demand, one form per brand.
 *
 * <p><b>Stateless by design, for now.</b> Nothing is stored: no import-request row, no minted
 * number, no draft/issue lifecycle. See {@link ImportRequestQueryRepository}'s Javadoc for why the
 * stored aggregate ships separately. The practical consequence is that this service <em>cannot</em>
 * guarantee document-number uniqueness — the caller supplies the number — and a reader must not
 * mistake a generated PDF for a recorded, issued document.
 *
 * <p><b>Authorisation.</b> {@link #IR_ROLES} is deliberately the same pair as
 * {@code TicketService.FULFILMENT_ROLES} ({@code import}, {@code ceo}), because this document is
 * the paperwork for exactly the transitions that constant already gates. It is NOT widened to the
 * deal's sales owner: the form is import's internal purchasing instrument, and sales already reads
 * the same quantities on the deal page.
 */
@Service
public class ImportRequestService {

    /** Mirrors {@code TicketService.FULFILMENT_ROLES}. Keep the two in step. */
    static final Set<String> IR_ROLES = Set.of("import", "ceo");

    /**
     * The form's "Request date" is a Thai business date, so it is resolved in Bangkok explicitly —
     * matching {@code AttendanceService.DEFAULT_WORK_DATE_ZONE}. A bare {@code LocalDate.now()}
     * would take the JVM default and print yesterday's date on a UTC host for the first 7 hours of
     * every Thai day.
     */
    private static final ZoneId BANGKOK = ZoneId.of("Asia/Bangkok");

    private final ImportRequestQueryRepository repository;
    private final ImportRequestRenderer renderer;

    public ImportRequestService(ImportRequestQueryRepository repository,
                                ImportRequestRenderer renderer) {
        this.repository = repository;
        this.renderer = renderer;
    }

    /** Brands on this deal, i.e. how many separate F-SM-001 forms it needs. */
    public List<String> brands(long ticketId, UserPrincipal actor) {
        requireRole(actor);
        requireTicket(ticketId);
        return repository.brandLinesForTicket(ticketId).stream()
            .map(BrandLines::brand)
            .filter(b -> b != null && !b.isBlank())
            .toList();
    }

    /**
     * Renders one brand's form.
     *
     * @param docNumber  "ReF. No." — supplied by the caller; blank leaves the rule empty to be
     *                   written by hand, which is how the owner's own IR69068 carries it.
     * @param requiredBy "กำหนดวันที่ต้องการของ" — free text ("Within 21/5/26"), sales-supplied.
     */
    public byte[] render(long ticketId, String brand, String docNumber, String requiredBy,
                         UserPrincipal actor) {
        requireRole(actor);
        TicketSnapshot snapshot = requireTicket(ticketId);
        if (brand == null || brand.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST,
                "ต้องระบุแบรนด์ — ใบขอซื้อหนึ่งใบต่อหนึ่งแบรนด์");
        }

        List<BrandLines> groups = repository.brandLinesForTicket(ticketId);
        // Refuse rather than print a form whose Brand header cannot be filled. A deal line with no
        // brand is a data problem on the deal, and quietly bucketing it under the requested brand
        // would put the wrong thing on a controlled document.
        if (groups.stream().anyMatch(g -> g.brand() == null)) {
            throw new ApiException(HttpStatus.CONFLICT,
                "มีรายการสินค้าที่ยังไม่ได้ระบุแบรนด์ — กรุณาระบุแบรนด์ให้ครบก่อนออกใบขอซื้อ");
        }
        BrandLines group = groups.stream()
            .filter(g -> brand.equals(g.brand()))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "ไม่พบรายการสินค้าของแบรนด์ " + brand + " ในดีลนี้"));

        ImportRequestFormData data = ImportRequestFormAssembler.assemble(
            snapshot, group, docNumber, requiredBy, LocalDate.now(BANGKOK), actor.name());

        try {
            return renderer.render(data);
        } catch (IOException e) {
            // ApiException carries no cause, so the stack is logged here rather than swallowed —
            // a template that fails to load must not surface as a bare 500 with nothing to debug.
            throw new ApiException(HttpStatus.INTERNAL_SERVER_ERROR,
                "สร้างไฟล์ใบขอซื้อไม่สำเร็จ: " + e.getMessage());
        }
    }

    /** Sheets the form will print on — lets a client warn before download. */
    public int pageCount(long ticketId, String brand, String requiredBy, UserPrincipal actor) {
        requireRole(actor);
        TicketSnapshot snapshot = requireTicket(ticketId);
        BrandLines group = repository.brandLinesForTicket(ticketId).stream()
            .filter(g -> g.brand() != null && g.brand().equals(brand))
            .findFirst()
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND,
                "ไม่พบรายการสินค้าของแบรนด์ " + brand + " ในดีลนี้"));
        return ImportRequestRenderer.pagesRequired(ImportRequestFormAssembler.assemble(
            snapshot, group, null, requiredBy, LocalDate.now(BANGKOK), actor.name()));
    }

    private void requireRole(UserPrincipal actor) {
        if (!IR_ROLES.contains(actor.role())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "ไม่มีสิทธิ์เข้าถึงรายการนี้");
        }
    }

    private TicketSnapshot requireTicket(long ticketId) {
        return repository.loadTicketSnapshot(ticketId)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบดีลนี้"));
    }
}
