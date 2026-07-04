package th.co.glr.hr.employee;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EmployeeDto(
    long id,
    String code,
    String badge,
    String nameTh,
    String nameEn,
    String nickName,
    String initials,
    String avatarBg,
    String avatarFg,
    String titleTh,
    String genderTh,
    LocalDate birthDate,
    Integer age,
    String nationality,
    String maritalStatus,
    String email,
    String phone,
    String divisionId,
    String divisionTh,
    String divisionEn,
    String departmentTh,
    String positionTh,
    String positionEn,
    String level,
    String locationTh,
    String statusId,
    String statusTh,
    String statusTone,
    boolean active,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String payType,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    BigDecimal salary,
    LocalDate hireDate,
    LocalDate confirmationDate,
    String reportsTo,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String bank,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    String bankAccount,
    AddressDto currentAddress,
    EmergencyContactDto emergencyContact,
    List<AssignmentDto> assignments,
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    List<SalaryHistoryDto> salaryHistory,
    @JsonInclude(JsonInclude.Include.NON_NULL)
    SensitiveDto sensitive,
    int pendingRequestCount
) {
    public EmployeeDto withPendingRequestCount(int count) {
        return new EmployeeDto(
            id, code, badge, nameTh, nameEn, nickName, initials, avatarBg, avatarFg,
            titleTh, genderTh, birthDate, age, nationality, maritalStatus, email, phone,
            divisionId, divisionTh, divisionEn, departmentTh, positionTh, positionEn, level,
            locationTh, statusId, statusTh, statusTone, active, payType, salary, hireDate,
            confirmationDate, reportsTo, bank, bankAccount, currentAddress, emergencyContact,
            assignments, salaryHistory, sensitive, count
        );
    }

    public EmployeeDto withoutSensitiveSelfServiceFields() {
        return new EmployeeDto(
            id, code, badge, nameTh, nameEn, nickName, initials, avatarBg, avatarFg,
            titleTh, genderTh, birthDate, age, nationality, maritalStatus, email, phone,
            divisionId, divisionTh, divisionEn, departmentTh, positionTh, positionEn, level,
            locationTh, statusId, statusTh, statusTone, active, null, null, hireDate,
            confirmationDate, reportsTo, null, null, currentAddress, emergencyContact,
            assignments, List.of(), null, pendingRequestCount
        );
    }
}
