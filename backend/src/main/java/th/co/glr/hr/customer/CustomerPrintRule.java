package th.co.glr.hr.customer;

/**
 * What a customer document prints for the branch (feedback #72): a branch belongs to a tax-registered customer, so with no
 * tax id on the document there is no branch, in the customer line or the address. Every column defaults to สำนักงานใหญ่, so
 * without this rule an individual's blank address printed "สำนักงานใหญ่". Nothing stored is changed.
 */
public final class CustomerPrintRule {
    private static final String HEAD_OFFICE = "สำนักงานใหญ่";

    private CustomerPrintRule() {}

    /** The branch to print: only with a tax id. */
    public static String branch(String branch, String taxId) {
        return isBlank(taxId) || isBlank(branch) ? null : branch.trim();
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
     * tax id only the customer's own default branch comes off the end, and never from an address that is the customer's
     * address as it stands ("อาคารธนาคารกรุงเทพ สำนักงานใหญ่" is a place, not a branch).
     */
    public static String joinedAddress(String address, String taxId, String customerAddress, String customerBranch) {
        String kept = addressOnly(address, customerBranch, taxId);
        if (kept == null || !isBlank(taxId) || isBlank(customerBranch) || !HEAD_OFFICE.equals(customerBranch.trim())
            || !kept.endsWith(" " + HEAD_OFFICE) || (!isBlank(customerAddress) && kept.equals(customerAddress.trim()))) {
            return kept;
        }
        return kept.substring(0, kept.length() - HEAD_OFFICE.length()).trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
