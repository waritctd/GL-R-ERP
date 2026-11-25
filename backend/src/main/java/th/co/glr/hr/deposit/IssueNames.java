package th.co.glr.hr.deposit;

/**
 * เรียน on a deposit notice or remaining invoice (feedback #74). Ploy: "for deposit and notive they should be able to
 * select the same customer for quotation (default) or create an entirely new customer". The rep quotes fast to whoever
 * asks (a contractor, an admin) and the buyer then asks for the notice and the tax invoice in another name.
 *
 * <p>{@code addresseeCustomerId}: a customer-list row picked (or newly created) instead of the quotation's customer;
 * null = the quotation's customer (rule #65). The whole header (name, tax id, address, branch) is that customer's.
 */
public record IssueNames(Long addresseeCustomerId) {

    /** A request's choice, applied by DepositNoticeService#chooseNames. Both null keeps what is stored. */
    public record Choice(Long addresseeCustomerId, Boolean addresseeReset) {
        public static final Choice KEEP = new Choice(null, null);
    }

    static final IssueNames NONE = new IssueNames(null);
}
