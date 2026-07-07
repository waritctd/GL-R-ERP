package th.co.glr.hr.ticket;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

public record SendFactoryEmailRequest(
    @NotBlank String factory,
    @NotBlank @Email String to,
    @NotBlank String subject,
    @NotBlank String body
) {}
