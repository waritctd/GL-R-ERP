package th.co.glr.hr.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;
    private final PasswordResetService passwordResetService;
    private final ViewAsService viewAs;

    public AuthController(AuthService authService, PasswordResetService passwordResetService, ViewAsService viewAs) {
        this.authService = authService;
        this.passwordResetService = passwordResetService;
        this.viewAs = viewAs;
    }

    @PostMapping("/login")
    AuthResponse login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        return viewAs.decorate(authService.login(request, httpRequest), httpRequest.getSession(false));
    }

    @PostMapping("/change-password")
    AuthResponse changePassword(@Valid @RequestBody ChangePasswordRequest request, HttpSession session) {
        return viewAs.decorate(authService.changePassword(request, session), session);
    }

    @PostMapping("/logout")
    ResponseEntity<Void> logout(HttpSession session) {
        authService.logout(session);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me")
    AuthResponse me(HttpSession session) {
        return viewAs.decorate(authService.me(session), session);
    }

    @PostMapping("/forgot-password")
    MessageResponse forgotPassword(@Valid @RequestBody ForgotPasswordRequest request) {
        return passwordResetService.forgotPassword(request);
    }

    @PostMapping("/reset-password")
    MessageResponse resetPassword(@Valid @RequestBody ResetPasswordRequest request) {
        return passwordResetService.resetPassword(request);
    }
}
