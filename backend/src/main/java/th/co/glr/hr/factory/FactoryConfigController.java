package th.co.glr.hr.factory;

import jakarta.servlet.http.HttpSession;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;

@RestController
@RequestMapping("/api/factory-configs")
public class FactoryConfigController {
    private final FactoryConfigRepository repo;
    private final SessionContext sessions;

    public FactoryConfigController(FactoryConfigRepository repo, SessionContext sessions) {
        this.repo     = repo;
        this.sessions = sessions;
    }

    @GetMapping
    Map<String, List<FactoryConfigDto>> list(HttpSession session) {
        sessions.requireUser(session);
        return Map.of("factories", repo.findAll());
    }
}
