package th.co.glr.hr.profile;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import th.co.glr.hr.auth.SessionContext;
import th.co.glr.hr.auth.UserPrincipal;
import th.co.glr.hr.profile.ProfileRequestResponses.ProfileRequestResponse;
import th.co.glr.hr.profile.ProfileRequestResponses.ProfileRequestsResponse;

@RestController
@RequestMapping("/api/profile-requests")
public class ProfileRequestController {
    private final ProfileRequestService profileRequestService;
    private final SessionContext sessions;

    public ProfileRequestController(ProfileRequestService profileRequestService, SessionContext sessions) {
        this.profileRequestService = profileRequestService;
        this.sessions = sessions;
    }

    @GetMapping
    ProfileRequestsResponse list(HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        return new ProfileRequestsResponse(profileRequestService.list(user));
    }

    @PostMapping
    ProfileRequestResponse create(@Valid @RequestBody CreateProfileRequestRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "employee");
        return new ProfileRequestResponse(profileRequestService.create(request, user));
    }

    @PatchMapping("/{id}")
    ProfileRequestResponse update(@PathVariable long id, @Valid @RequestBody UpdateProfileRequestRequest request, HttpSession session) {
        UserPrincipal user = sessions.requireUser(session);
        sessions.requireAnyRole(user, "hr");
        return new ProfileRequestResponse(profileRequestService.update(id, request, user));
    }
}
