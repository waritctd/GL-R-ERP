package th.co.glr.hr.customer;

/**
 * What a customer document prints for the branch (feedback #72): a branch belongs to a tax-registered customer, so with no
 * tax id on the document there is no branch, in the customer line or the address. Every column defaults to สำนักงานใหญ่, so
 * without this rule an individual's blank address printed "สำนักงานใหญ่". Nothing stored is changed.
 */
public final class CustomerPrintRule {
    private static final String HEAD_OFFICE = "สำนักงานใหญ่";

    private CustomerPrintRule() {}

    /** The branch to print: only with a company's tax id. A person's 13-digit id starts 1-8 (a company's starts 0), and a
     * person has no branch (Ploy, feedback #74). */
    public static String branch(String branch, String taxId) {
        return isBlank(taxId) || isBlank(branch) || isPersonId(taxId) ? null : branch.trim();
    }

    static boolean isPersonId(String taxId) {
        return taxId.replaceAll("[\\s-]", "").matches("[1-8]\\d{12}");
    }

    /** The address with the branch folded in, as the deposit notice prints it. */
    public static String addressWithBranch(String address, String branch, String taxId) {
        String printed = branch(branch, taxId);
        if (isBlank(address)) {
            return printed;
        }
        return printed == null ? address.trim() : address.trim() + " " + printed;
    }

    /**
     * A remaining invoice's or billing note's stored address, which never had the branch joined in: without a tax id it
     * goes only when the whole value IS the branch. A real address that merely ends with the word is left alone.
     */
    public static String addressOnly(String address, String branch, String taxId) {
        if (isBlank(address)) {
            return null;
        }
        String trimmed = address.trim();
        boolean isBranch = trimmed.equals(HEAD_OFFICE) || (!isBlank(branch) && trimmed.equals(branch.trim()));
        return isBlank(taxId) && isBranch ? null : trimmed;
    }

    /**
     * An older deposit notice's stored address, where the customer's address and branch were joined with a space. Without a
     * tax id the default branch comes off only when the value is exactly that join. Anything else was typed or has since
     * changed, and is printed whole ("อาคารธนาคารกรุงเทพ สำนักงานใหญ่" is a place, not a branch).
     */
    public static String joinedAddress(String address, String taxId, String customerAddress, String customerBranch) {
        String kept = addressOnly(address, customerBranch, taxId);
        boolean isDefaultJoin = kept != null && isBlank(taxId) && !isBlank(customerAddress) && !isBlank(customerBranch)
            && HEAD_OFFICE.equals(customerBranch.trim()) && kept.equals(customerAddress.trim() + " " + HEAD_OFFICE);
        return isDefaultJoin ? customerAddress.trim() : kept;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
