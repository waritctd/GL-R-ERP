package th.co.glr.hr.auth;

/** Who reads the outbound leg of การขนส่ง (the warehouse queue and trips, which carry no prices): the CEO, the sales team
 * and oversight readers. Not import (Ploy 11-27: the warehouse reads import's leg, not the other way round). Read only: every write stays the warehouse planner's ({@link WarehouseStaff#isPlanner}). */
public final class OutboundReader {
    private OutboundReader() {
    }

    public static boolean is(UserPrincipal actor, EmployeeAuthRepository employees) {
        if (actor == null) {
            return false;
        }
        return switch (actor.role()) {
            case "ceo", "sales" -> true;
            case ImportStaff.ROLE -> false;
            default -> OversightReader.is(actor, employees::canReadAllSales);
        };
    }
}
