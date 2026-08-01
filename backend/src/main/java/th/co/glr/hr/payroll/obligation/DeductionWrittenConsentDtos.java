package th.co.glr.hr.payroll.obligation;

import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import th.co.glr.hr.payroll.PayrollDeductionKind;

public final class DeductionWrittenConsentDtos {
    private DeductionWrittenConsentDtos() {
    }

    public record DeductionWrittenConsentUpsertRequest(
        @NotNull Long employeeId,
        @NotNull PayrollDeductionKind deductionKind,
        boolean consentOnFile,
        String consentDocumentReference,
        LocalDate consentDate,
        String notes
    ) {}

    public record DeductionWrittenConsentDto(
        long id,
        long employeeId,
        String employeeCode,
        String employeeName,
        PayrollDeductionKind deductionKind,
        boolean consentOnFile,
        String consentDocumentReference,
        LocalDate consentDate,
        String notes,
        Long recordedById,
        OffsetDateTime recordedAt,
        Long updatedById,
        OffsetDateTime updatedAt
    ) {}

    public record DeductionWrittenConsentListResponse(List<DeductionWrittenConsentDto> items) {}
}
