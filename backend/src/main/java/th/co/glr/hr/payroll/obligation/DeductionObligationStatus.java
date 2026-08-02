package th.co.glr.hr.payroll.obligation;

/**
 * Lifecycle of a {@code hr.deduction_obligation} row.
 *
 * <ul>
 *   <li>{@link #ACTIVE} -- being drawn down every payroll period, clamped to the remaining balance
 *       when {@code instructedTotal} is set.
 *   <li>{@link #COMPLETED} -- cumulative remittance has reached {@code instructedTotal}. Deducts
 *       {@code 0} from here on UNLESS HR has recorded an explicit
 *       {@code overrideContinuePastTotal} (decision 3's required mitigation) -- this state is never
 *       silent, see {@code completionAcknowledgedAt}.
 *   <li>{@link #STOPPED} -- HR ended the obligation deliberately (dispute resolved some other way,
 *       authority notified it in error, employee left, ...). Frozen: never reactivates on its own,
 *       unlike {@link #COMPLETED} which can revert to {@link #ACTIVE} if a later correction drops
 *       cumulative remittance back under the total.
 * </ul>
 */
public enum DeductionObligationStatus {
    ACTIVE,
    COMPLETED,
    STOPPED
}
