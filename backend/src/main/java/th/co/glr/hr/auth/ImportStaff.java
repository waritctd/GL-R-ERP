package th.co.glr.hr.auth;

/** The import team (effective role {@code import}). The CEO is not import staff. */
public final class ImportStaff {
    public static final String ROLE = "import";

    private ImportStaff() {
    }

    public static boolean is(UserPrincipal actor) {
        return actor != null && ROLE.equals(actor.role());
    }
}
