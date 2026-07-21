package th.co.glr.hr.customerquotation;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public final class CustomerQuotationRequests {
    private CustomerQuotationRequests() {}

    public record CreateCustomerQuotationRequest(
        @Size(max = 2000) String paymentTerms,
        @Size(max = 2000) String leadTime,
        @Size(max = 2000) String deliveryTerms,
        LocalDate validityDate,
        @Size(max = 4000) String customerNotes,
        String clientRequestId
    ) {}

    public record UpdateCustomerQuotationItemRequest(
        @NotNull Long quotationItemId,
        @Size(max = 2000) String description,
        @Size(max = 2000) String itemNotes,
        // null = leave unchanged; server always recomputes final_unit_price/line_subtotal/
        // vat/line_total from approved_unit_price - salesDiscount, never trusts a client total.
        BigDecimal salesDiscount
    ) {}

    public record UpdateCustomerQuotationRequest(
        @Size(max = 2000) String paymentTerms,
        @Size(max = 2000) String leadTime,
        @Size(max = 2000) String deliveryTerms,
        LocalDate validityDate,
        @Size(max = 4000) String customerNotes,
        List<@Valid UpdateCustomerQuotationItemRequest> items
    ) {}

    public record IssueCustomerQuotationRequest(String clientRequestId) {}

    public record CancelCustomerQuotationRequest(@Size(max = 2000) String reason) {}

    public record CreateRevisionRequest(
        @Size(max = 2000) String reason,
        String clientRequestId
    ) {}
}
