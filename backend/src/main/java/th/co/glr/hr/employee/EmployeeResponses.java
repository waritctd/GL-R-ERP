package th.co.glr.hr.employee;

import java.util.List;

public final class EmployeeResponses {
    private EmployeeResponses() {
    }

    public record EmployeesResponse(List<EmployeeDto> employees, int page, int size, int total) {
    }

    public record EmployeeResponse(EmployeeDto employee) {
    }
}
