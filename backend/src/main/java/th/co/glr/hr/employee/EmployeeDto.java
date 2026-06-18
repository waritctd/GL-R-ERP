package th.co.glr.hr.employee;

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
    String payType,
    BigDecimal salary,
    LocalDate hireDate,
    LocalDate confirmationDate,
    String reportsTo,
    String bank,
    String bankAccount,
    AddressDto currentAddress,
    EmergencyContactDto emergencyContact,
    List<AssignmentDto> assignments,
    List<SalaryHistoryDto> salaryHistory,
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
}
