package th.co.glr.hr.importprogress;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Per-factory import-progress shapes. Price-free by design (see {@link ImportStep}) — nothing here
 * carries a supplier price, so unlike the {@code procurement} PO DTOs these are safe for sales to
 * read. Write is still import/ceo only; that gate lives in {@link ImportProgressService}.
 */
public final class ImportProgressDtos {
    private ImportProgressDtos() {}

    public record FactoryImportProgressDto(
        long id,
        long pricingRequestId,
        String pricingRequestCode,
        long ticketId,
        String ticketCode,
        String factoryName,
        String importStep,
        Instant importStepAt,
        LocalDate eta,
        String note,
        Long updatedBy,
        String updatedByName,
        Instant updatedAt
    ) {}

    /**
     * The generated order-email template for one factory — Import copies {@code body}, sends it
     * themselves (outside the system, mirroring the factory-quote email hand-off), then advances
     * the step. English on purpose: the recipient is a foreign supplier.
     */
    public record OrderEmailTemplateDto(
        String factoryName,
        String subject,
        String body
    ) {}
}
