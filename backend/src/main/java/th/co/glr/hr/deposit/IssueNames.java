package th.co.glr.hr.deposit;

/**
 * เรียน and ออกในนาม on a deposit notice or remaining invoice (feedback #74). Ploy: "เรียน can be the master ลูกค้า …
 * the person that is ออกในนาม can be a different person", "make everything choosable just put in the default value
 * first".
 *
 * <p>{@code addresseeCustomerId}: เรียน picked from the customer list instead of the quotation's customer (null = the
 * quotation's, rule #65). {@code payerName/Phone/Email}: the ออกในนาม person, a snapshot printed as its own line under
 * เรียน (null name = the same as เรียน); {@code payerCustomerId} when it came from the customer list, null when it is the
 * quotation's contact.
 */
public record IssueNames(Long addresseeCustomerId, Long payerCustomerId, String payerName, String payerPhone,
                         String payerEmail) {

    /** A person as the ออกในนาม line prints them. */
    public record Person(String name, String phone, String email) {}

    /** A request's choice, applied by DepositNoticeService#chooseNames. All null keeps what is stored.
     * {@code payerReset}: {@code DEFAULT} = the quotation's contact, {@code NONE} = the same as เรียน. */
    public record Choice(Long addresseeCustomerId, Boolean addresseeReset, Long payerCustomerId, String payerReset) {
        public static final Choice KEEP = new Choice(null, null, null, null);
    }

    static final IssueNames NONE = new IssueNames(null, null, null, null, null);

    IssueNames withAddressee(Long id) {
        return new IssueNames(id, payerCustomerId, payerName, payerPhone, payerEmail);
    }

    IssueNames withPayer(Long customerId, Person p) {
        return p == null || p.name() == null || p.name().isBlank()
            ? new IssueNames(addresseeCustomerId, null, null, null, null)
            : new IssueNames(addresseeCustomerId, customerId, p.name().trim(), blankToNull(p.phone()), blankToNull(p.email()));
    }

    /** The printed ออกในนาม line, null when the document names none. */
    String payerLine() {
        return DocumentSheet.issueInNameLine(payerName, payerPhone, payerEmail);
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
