package th.co.glr.hr.importdeal;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import th.co.glr.hr.importrequest.ImportRequestDtos.ImportRequestDto;

/**
 * Read shapes for the per-deal IMPORT view ({@code GET /api/import/deals/{id}}).
 *
 * <p>This is a FRESH projection, not a filtered {@code TicketDto}: it is built field by field from
 * the deal and simply never declares a price, cost, margin, discount, commission or weighting
 * component, so there is nothing to forget to strip. That is the point of the view — the import
 * role works a deal's factories, lead times, order emails and delivery progress, and does not
 * need (and, per the owner ruling that mirrors the account finance view, must not be handed) the
 * pricing the sales/CEO side owns. {@code ImportDealDtoNoPricingFieldsTest} pins this by
 * reflection so a future field addition cannot violate it silently.
 *
 * <p>Deliberately absent too: quotations, payments, deposit notices, the deal's raw event feed
 * (its price-override / proposal events quote prices in their notes — only the human COMMENT
 * thread is exposed, see {@link ImportDealCommentDto}) and its document attachments.
 */
public final class ImportDealDtos {
    private ImportDealDtos() {}

    /** The response envelope, matching the {@code {ticket: ...}} / {@code {importRequest: ...}} convention. */
    public record ImportDealResponse(ImportDealDto deal) {}

    public record ImportDealDto(
        long id,
        String code,
        String title,
        /** Legacy ticket status (draft / quotation_issued / ...). Context only. */
        String status,
        String lifecycle,
        String salesStage,
        String customerName,
        String projectName,
        /** The owning sales rep — who import coordinates with. */
        String createdByName,
        /**
         * READ-ONLY delivery status ({@code sales.ticket.fulfillment_status}). Recording a delivery
         * is Sales's/CEO's write (V184, owner decision) — import only sees where it stands.
         */
        String fulfillmentStatus,
        List<ImportDealItemDto> items,
        /** The stored per-factory ใบขอซื้อ rows — the same data {@code ImportRequestService#list} serves. */
        List<ImportRequestDto> importRequests,
        List<ImportDealCommentDto> comments
    ) {}

    /** A deal line: what is being bought and how much of it has been delivered — never at what price. */
    public record ImportDealItemDto(
        long id,
        String brand,
        String model,
        String color,
        String texture,
        String size,
        /** The catalogue product code when the line is catalogue-linked; null for a custom line. */
        String code,
        BigDecimal qty,
        BigDecimal qtySqm,
        /** The line's quantity basis (pcs / sqm / ...). */
        String unit,
        /** Read-only delivered-so-far quantity. */
        BigDecimal qtyDelivered
    ) {}

    public record ImportDealCommentDto(
        long id,
        String actorName,
        String message,
        Instant createdAt
    ) {}
}
