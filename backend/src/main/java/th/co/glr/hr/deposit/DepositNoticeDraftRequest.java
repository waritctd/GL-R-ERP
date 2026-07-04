package th.co.glr.hr.deposit;

import java.math.BigDecimal;
import java.util.List;

public record DepositNoticeDraftRequest(
    String       customerName,
    String       customerTaxId,
    String       customerAddress,
    String       projectName,
    String       reference,
    BigDecimal   depositPercent,
    List<String> notes,
    List<DepositNoticeItemRequest> items
) {}
