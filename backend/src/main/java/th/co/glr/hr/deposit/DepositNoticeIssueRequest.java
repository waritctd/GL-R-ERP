package th.co.glr.hr.deposit;

import java.time.OffsetDateTime;

/** Optional body of the issue call: the draft's updatedAt as the caller last read it (null skips the check). */
public record DepositNoticeIssueRequest(OffsetDateTime expectedUpdatedAt) {}
