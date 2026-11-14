package th.co.glr.hr.auth;

/** The logged-in user, plus the capabilities that are not derivable from UserPrincipal. It is a hint for
 * rendering only. This record has three construction sites and none in tests. */
public record AuthResponse(UserPrincipal user, boolean admin, boolean canCreateQuotation, boolean canIssueBillingNote,
                           boolean canViewAs, ViewAsInfo viewAs) {
    /** Who is really behind a read-only "view as" session; null on the response unless viewing. */
    public record ViewAsInfo(long actorId, String actorName) {}
}
