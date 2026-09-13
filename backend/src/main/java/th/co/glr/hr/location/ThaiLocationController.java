package th.co.glr.hr.location;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.*;
import th.co.glr.hr.auth.SessionContext;

/** Public reference geography, available to authenticated users; contains no customer data. */
@RestController
@RequestMapping("/api/locations")
public class ThaiLocationController {
    private final ThaiLocationRepository locations;
    private final SessionContext sessions;
    public ThaiLocationController(ThaiLocationRepository locations, SessionContext sessions) {
        this.locations = locations; this.sessions = sessions;
    }
    @GetMapping("/provinces")
    public Map<String, List<ThaiLocationRepository.Province>> provinces(HttpSession session) {
        sessions.requireUser(session);
        return Map.of("items", locations.provinces());
    }
    @GetMapping("/provinces/{provinceCode}/districts")
    public Map<String, List<ThaiLocationRepository.District>> districts(@PathVariable String provinceCode, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("items", locations.districts(provinceCode));
    }
    @GetMapping("/districts/{districtCode}/subdistricts")
    public Map<String, List<ThaiLocationRepository.Subdistrict>> subdistricts(@PathVariable String districtCode, HttpSession session) {
        sessions.requireUser(session);
        return Map.of("items", locations.subdistricts(districtCode));
    }
}
