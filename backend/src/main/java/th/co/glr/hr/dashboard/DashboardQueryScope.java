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

    /**
     * Scopes by {@code reports_to_employee_id} rather than {@code division_id} -- for leave, whose
     * review authority is the manager's direct reports, not their division (see
     * {@code DashboardService#leaveScope} and {@code DashboardRepository#countLeave} for why this
     * diverges from {@link #division}, which {@code countOvertime} still correctly uses).
     * {@code managerEmployeeId} is stored in the existing {@code employeeId} component -- there is
     * no separate slot for it -- so a REPORTS_TO scope's {@code employeeId()} is the MANAGER's own
     * id, not a target employee's.
     */
    static DashboardQueryScope reportsTo(Long managerEmployeeId) {
        if (managerEmployeeId == null) {
            return none();
        }
        return new DashboardQueryScope(DashboardScopeType.REPORTS_TO, managerEmployeeId, null, "reportsTo");
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

    boolean isReportsTo() {
        return type == DashboardScopeType.REPORTS_TO;
    }

    boolean isSelf() {
        return type == DashboardScopeType.SELF;
    }

    boolean isNone() {
        return type == DashboardScopeType.NONE;
    }
}
