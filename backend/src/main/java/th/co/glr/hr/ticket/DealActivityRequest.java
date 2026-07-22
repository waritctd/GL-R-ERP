package th.co.glr.hr.ticket;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record DealActivityRequest(
    @NotNull LocalDate activityDate,
    @NotBlank String kind,
    @Size(max = 2000) String note
) {}
