package th.co.glr.hr.billing;

import java.util.List;

public record BillingNoteCandidatesDto(
    long   customerId,
    String customerName,
    List<BillingNoteCandidateDto> candidates,
    List<BillingNoteCandidateDto> alreadyBilled
) {}
