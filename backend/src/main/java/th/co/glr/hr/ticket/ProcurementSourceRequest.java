package th.co.glr.hr.ticket;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ProcurementSourceRequest(@NotNull @Size(min = 1) List<@Valid Line> lines) {
    public record Line(@NotNull Long itemId, boolean fromStock) {}
}
