package th.co.glr.hr.employee;

import java.util.List;

public final class EmployeeResponses {
    private EmployeeResponses() {
    }

    public record EmployeesResponse(List<EmployeeDto> employees) {
    }

    public record EmployeeResponse(EmployeeDto employee) {
    }
}
