package th.co.glr.hr.user;

import java.time.LocalDate;
import java.util.List;

public record AppUserRecord(
    long id,
    String email,
    String passwordHash,
    String name,
    Long employeeId,
    boolean active,
    LocalDate createdAt,
    List<String> roles
) {
}
