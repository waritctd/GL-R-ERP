package th.co.glr.hr.designer;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.common.ApiException;

/**
 * READ-ONLY ผู้ออกแบบ (designer) directory (V173) for the quotation-editor picker. Owner ruling,
 * 2026-09-12: "อ่านอย่างเดียว อัปเดตจาก Excel" -- there is intentionally NO POST/PUT/DELETE
 * anywhere on this controller and there must never be one; the table is refreshed only by a
 * future re-import migration.
 *
 * <p>Open to any authenticated user, same product decision as {@code CatalogController.search}
 * (#205) -- a sales rep filling in a quotation needs to search this list, and {@link
 * DesignerDto#name} carries nothing more sensitive than a company name already visible in the
 * source spreadsheet reps have always had. Confidentiality here is about the PRINTED DOCUMENT
 * (the owner's "เป็นความลับ" is about what a customer would see on their quotation), not about
 * which employee role may search the directory -- do not read this endpoint's openness as
 * conflicting with that; see {@link DesignerDto}'s own doc for where the actual guarantee lives.
 */
@RestController
@RequestMapping("/api/designers")
public class DesignerController {
    private final DesignerRepository designers;
    private final SessionContext sessions;

    public DesignerController(DesignerRepository designers, SessionContext sessions) {
        this.designers = designers;
        this.sessions  = sessions;
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
}
