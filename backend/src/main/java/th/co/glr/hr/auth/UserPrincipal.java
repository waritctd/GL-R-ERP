package th.co.glr.hr.auth;

import java.io.Serializable;
import java.time.LocalDate;

public record UserPrincipal(
    long id,
    String email,
    String name,
    String role,
    Long employeeId,
    boolean active,
    LocalDate createdAt
) implements Serializable {
}
