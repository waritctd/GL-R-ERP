package th.co.glr.hr.designer;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.DealEntryAccess;
import th.co.glr.hr.auth.EmployeeAuthRepository;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.common.ApiException;

/**
 * ผู้ออกแบบ (designer) directory (V173) for the quotation-editor picker.
 *
 * <p>⚠️ REVERSAL (owner ask relayed 2026-09-26, task "designer-add-from-ui"): this class used to
 * say the directory was READ-ONLY by owner ruling ("อ่านอย่างเดียว อัปเดตจาก Excel", 2026-09-12),
 * with intentionally NO POST/PUT/DELETE anywhere and a note that one must never be added without a
 * fresh owner ask. That fresh ask has now happened: the owner wants a sales rep to be able to add a
 * new designer inline from the quotation editor, mirroring {@code DealCustomerCard}'s
 * "+ เพิ่มลูกค้าใหม่" flow. {@link #create} below is that endpoint. It is deliberately narrow --
 * there is STILL no PUT/DELETE here; editing or removing a designer row (or changing how existing,
 * Excel-imported rows are maintained) remains out of scope until a further owner ask. {@link
 * #create} is gated by {@link DealEntryAccess#requireCanEnterDeal} -- the SAME gate
 * {@code CustomerController#create}/{@code createContact}/{@code createProject} use (sales /
 * sales_manager / a live {@code canCreateQuotation} grant) -- never wider. Real-DB evidence:
 * {@code DesignerCreateAuthzIntegrationTest}.
 *
 * <p>Open to any authenticated user for {@link #search}/{@link #getByCode}, same product decision
 * as {@code CatalogController.search} (#205) -- a sales rep filling in a quotation needs to search
 * this list, and {@link DesignerDto#name} carries nothing more sensitive than a company name
 * already visible in the source spreadsheet reps have always had. Confidentiality here is about
 * the PRINTED DOCUMENT (the owner's "เป็นความลับ" is about what a customer would see on their
 * quotation), not about which employee role may search the directory -- do not read this
 * endpoint's openness as conflicting with that; see {@link DesignerDto}'s own doc for where the
 * actual guarantee lives. That guarantee is unaffected by this reversal: {@link #create} still
 * only ever writes {@code code}/{@code name} into {@code sales.designer}, never anywhere near a
 * rendered document.
 */
@RestController
@RequestMapping("/api/designers")
public class DesignerController {
    private final DesignerRepository designers;
    private final SessionContext sessions;
    // Deal-ENTRY authz only (see DealEntryAccess's own Javadoc) -- create() below uses this;
    // search()/getByCode() do not.
    private final EmployeeAuthRepository employeeAuth;

    public DesignerController(DesignerRepository designers, SessionContext sessions, EmployeeAuthRepository employeeAuth) {
        this.designers = designers;
        this.sessions  = sessions;
        this.employeeAuth = employeeAuth;
    }

    /** Active-only -- see {@link DesignerRepository#search}. */
    @GetMapping
    Map<String, List<DesignerDto>> search(@RequestParam(required = false) String q, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("items", designers.search(q));
    }

    /**
     * Resolve one designer by code, active or not -- lets the quotation editor show who is
     * CURRENTLY selected (by name, in the picker only) even when that designer's own row has
     * since gone {@code active = FALSE}, e.g. reopening an old quotation. 404s when the code is
     * not in the directory at all (a hand-typed D.Co. that never matched a designer row).
     */
    @GetMapping("/{code}")
    DesignerDto getByCode(@PathVariable String code, HttpSession session) {
        sessions.requireUser(session);
        return designers.findByCode(code)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบผู้ออกแบบรหัสนี้"));
    }

    /**
     * NEW (reversal, see class Javadoc): lets a sales rep add a designer that is not yet in the
     * directory, straight from the quotation editor's {@code DesignerPicker}, mirroring
     * {@code CustomerController#create}. Gated by {@link DealEntryAccess#requireCanEnterDeal} --
     * sales / sales_manager / a live {@code canCreateQuotation} grant; every other role 403s. A
     * duplicate {@code code} (the table's PK) is a 409 with a Thai message, not a 500 -- see
     * {@link DesignerRepository#create}'s own doc for why the DB is left to enforce uniqueness
     * rather than a racy SELECT-then-INSERT guess here.
     *
     * <p>Design note (flagged, not blocking): this collects BOTH a {@code code} and a {@code name}
     * from the rep -- {@code code} is the ผู้ออกแบบ PK (e.g. "A001"), entered by hand, exactly like
     * every Excel-imported row. If the owner later wants codes auto-generated instead of
     * hand-entered, that is a follow-up, not implemented here.
     */
    @PostMapping
    Map<String, DesignerDto> create(@Valid @RequestBody CreateDesignerRequest req, HttpSession session) {
        DealEntryAccess.requireCanEnterDeal(sessions.requireUser(session), employeeAuth);
        try {
            return Map.of("designer", designers.create(req.code().trim(), req.name().trim()));
        } catch (DuplicateKeyException e) {
            throw new ApiException(HttpStatus.CONFLICT, "รหัสผู้ออกแบบนี้มีอยู่แล้ว");
        }
    }

    record CreateDesignerRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 255) String name
    ) {}
}
