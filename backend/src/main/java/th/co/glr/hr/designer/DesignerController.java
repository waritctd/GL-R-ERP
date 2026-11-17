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

/** ผู้ออกแบบ directory for the quotation-editor picker. ⚠️ REVERSAL: this class used to say the directory was
 * READ-ONLY by owner ruling, with intentionally NO POST/PUT/DELETE anywhere and a note that one must never be
 * added without a fresh owner ask. */
@RestController
@RequestMapping("/api/designers")
public class DesignerController {
    private final DesignerRepository designers;
    private final SessionContext sessions;
    // Deal-ENTRY authz only (see DealEntryAccess's own Javadoc) -- create() below uses this; search()/getByCode()
    // do not.
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

    /** Resolve one designer by code, active or not -- lets the quotation editor show who is CURRENTLY selected (by
     * name, in the picker only) even when that designer's own row has since gone active = FALSE, e.g. reopening an
     * old quotation. that never matched a designer row). */
    @GetMapping("/{code}")
    DesignerDto getByCode(@PathVariable String code, HttpSession session) {
        sessions.requireUser(session);
        return designers.findByCode(code)
            .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "ไม่พบผู้ออกแบบรหัสนี้"));
    }

    /** NEW (reversal, see class Javadoc): lets a sales rep add a designer that is not yet in the directory,
     * straight from the quotation editor's DesignerPicker, mirroring CustomerController#create. */
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
