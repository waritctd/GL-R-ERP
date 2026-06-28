package th.co.glr.hr.document;

import java.math.BigDecimal;
import java.util.List;

public record DocumentDraftRequest(
    String       customerName,
    String       customerTaxId,
    String       customerAddress,
    String       projectName,
    String       reference,
    BigDecimal   depositPercent,
    List<String> notes,
    List<DocumentItemRequest> items
) {}
