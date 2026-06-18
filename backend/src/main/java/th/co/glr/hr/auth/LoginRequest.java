package th.co.glr.hr.auth;

public record LoginRequest(String email, String password, String role) {
}
