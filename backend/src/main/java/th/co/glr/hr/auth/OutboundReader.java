package th.co.glr.hr.auth;

/** Who reads the outbound leg of การขนส่ง (the warehouse queue and trips, which carry no prices): import, the CEO, the sales
 * team and oversight readers. Read only: every write stays the warehouse planner's ({@link WarehouseStaff#isPlanner}). */
public final class OutboundReader {
    private OutboundReader() {
    }

    public static boolean is(UserPrincipal actor, EmployeeAuthRepository employees) {
        if (actor == null) {
            return false;
        }
        return switch (actor.role()) {
            case ImportStaff.ROLE, "ceo", "sales" -> true;
            default -> OversightReader.is(actor, employees::canReadAllSales);
        };
    }
}
