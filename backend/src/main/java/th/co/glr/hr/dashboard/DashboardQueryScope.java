package th.co.glr.hr.dashboard;

record DashboardQueryScope(
    DashboardScopeType type,
    Long employeeId,
    Long divisionId,
    String label
) {
    static DashboardQueryScope all() {
        return new DashboardQueryScope(DashboardScopeType.ALL, null, null, "all");
    }

    static DashboardQueryScope division(Long divisionId) {
        if (divisionId == null) {
            return none();
        }
        return new DashboardQueryScope(DashboardScopeType.DIVISION, null, divisionId, "division");
    }

    static DashboardQueryScope self(Long employeeId) {
        if (employeeId == null) {
            return none();
        }
        return new DashboardQueryScope(DashboardScopeType.SELF, employeeId, null, "self");
    }

    static DashboardQueryScope none() {
        return new DashboardQueryScope(DashboardScopeType.NONE, null, null, "none");
    }

    boolean isAll() {
        return type == DashboardScopeType.ALL;
    }

    boolean isDivision() {
        return type == DashboardScopeType.DIVISION;
    }

    boolean isSelf() {
        return type == DashboardScopeType.SELF;
    }

    boolean isNone() {
        return type == DashboardScopeType.NONE;
    }
}
