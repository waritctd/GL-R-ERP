package th.co.glr.hr.dashboard;

import java.math.BigDecimal;

/** One sales rep's net cash received this Bangkok month. */
public record CashByRepDto(String repName, BigDecimal amount) {}
