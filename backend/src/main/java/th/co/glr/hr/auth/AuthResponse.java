package th.co.glr.hr.auth;

/**
 * The logged-in user, plus the capabilities that are not derivable from {@link UserPrincipal}.
 *
 * <p>{@code admin} is a sibling of {@code user} rather than a field on {@link UserPrincipal}
 * deliberately: that record is {@code Serializable} session state with 306 construction sites
 * across the test suite, so widening it to carry one UI hint would be a 300-file mechanical change
 * for no benefit. This record has three construction sites and none in tests.
 *
 * <p>It is a <strong>hint for rendering only</strong>. Every admin endpoint re-checks
 * {@code hr.employee.is_admin} live against the database, so a client that forges this flag gains
 * exactly nothing — see {@code ActivityLogService.requireAdmin}.
 */
public record AuthResponse(UserPrincipal user, boolean admin) {
}
