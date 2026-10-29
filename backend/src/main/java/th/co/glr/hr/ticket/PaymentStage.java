package th.co.glr.hr.ticket;

import java.util.Set;

public final class PaymentStage {
    private PaymentStage() {}

    /** No payable amount is currently due. */
    public static final String NOT_REQUIRED = "NOT_REQUIRED";
    /** Deposit is required but has not been received. */
    public static final String DEPOSIT_PENDING = "DEPOSIT_PENDING";
    /** Deposit has been received and no balance receipt exists yet. */
    public static final String DEPOSIT_RECEIVED = "DEPOSIT_RECEIVED";
    /** Some balance has been received, but outstanding remains. */
    public static final String PARTIALLY_PAID = "PARTIALLY_PAID";
    /** Balance is due and no balance receipt has been recorded yet. */
    public static final String BALANCE_PENDING = "BALANCE_PENDING";
    /** Paid amount covers the derived payable amount. */
    public static final String FULLY_PAID = "FULLY_PAID";

    /** The customer has not reached the payment step yet (no payment_status, nothing paid). */
    public static final String NOT_STARTED = "NOT_STARTED";
    /** A bypass deposit policy (e.g. a 0% quotation): no deposit is collected, the whole amount is the balance. */
    public static final String NO_DEPOSIT = "NO_DEPOSIT";

    public static final Set<String> VALID = Set.of(
        NOT_STARTED, NO_DEPOSIT, NOT_REQUIRED, DEPOSIT_PENDING, DEPOSIT_RECEIVED, PARTIALLY_PAID, BALANCE_PENDING, FULLY_PAID);
}
