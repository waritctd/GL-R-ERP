package th.co.glr.hr.employee;

public record EmployeeFilter(
    String search,
    String divisionId,
    String departmentTh,
    String statusId,
    Boolean active
) {
}
