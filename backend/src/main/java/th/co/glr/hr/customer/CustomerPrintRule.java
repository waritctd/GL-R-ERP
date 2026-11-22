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

    /** A stored address that may already hold the default branch at its end: without a tax id that part does not print. */
    public static String storedAddress(String address, String taxId) {
        if (isBlank(address)) {
            return null;
        }
        String trimmed = address.trim();
        if (!isBlank(taxId) || !trimmed.endsWith(HEAD_OFFICE)) {
            return trimmed;
        }
        String rest = trimmed.substring(0, trimmed.length() - HEAD_OFFICE.length()).trim();
        return rest.isEmpty() ? null : rest;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
