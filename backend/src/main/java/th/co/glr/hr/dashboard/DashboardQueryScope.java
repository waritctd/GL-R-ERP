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
     * Scopes by "{@code employee_id = :callerId OR (reports_to_employee_id = :callerId AND
     * is_active = TRUE)}" rather than {@code division_id} -- for leave, whose review authority is
     * the caller's own requests plus their ACTIVE direct reports', not their division (see
     * {@code DashboardService#leaveScope} and {@code DashboardRepository#countLeave} for why this
     * diverges from {@link #division}, which {@code countOvertime} still correctly uses). This is
     * deliberately the SAME shape as the {@code /leave} LIST predicate this scope's only caller (the
     * dashboard leave badge) links to ({@code LeaveRepository#findRequests}'s {@code
     * lr.employee_id = :managerEmployeeId OR e.reports_to_employee_id = :managerEmployeeId}), plus
     * the {@code is_active} guard {@code LeaveRepository#countReviewableSubmitted} also requires --
     * NOT {@code countReviewableSubmitted}'s reviewer-only predicate, which deliberately EXCLUDES the
     * caller's own requests (a manager cannot review their own leave; this dashboard count is a VIEW
     * figure, not an approval-eligibility count, so it must include them). {@code callerId} is
     * stored in the existing {@code employeeId} component -- there is no separate slot for it -- so
     * an OWN_OR_DIRECT_REPORTS scope's {@code employeeId()} is the CALLER's own id, not a target
     * employee's; it is matched against both the row's own owner AND that owner's manager.
     */
    static DashboardQueryScope ownOrDirectReports(Long callerId) {
        if (callerId == null) {
            return none();
        }
        return new DashboardQueryScope(DashboardScopeType.OWN_OR_DIRECT_REPORTS, callerId, null, "ownOrDirectReports");
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

    boolean isOwnOrDirectReports() {
        return type == DashboardScopeType.OWN_OR_DIRECT_REPORTS;
    }

    boolean isSelf() {
        return type == DashboardScopeType.SELF;
    }

    boolean isNone() {
        return type == DashboardScopeType.NONE;
    }
}
