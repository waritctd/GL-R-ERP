package th.co.glr.hr.employee;

import java.math.BigDecimal;
import java.time.LocalDate;

public record UpsertEmployeeRequest(
    String code,
    String badge,
    String nameTh,
    String nameEn,
    String nickName,
    String titleTh,
    String genderTh,
    LocalDate birthDate,
    String nationality,
    String maritalStatus,
    String email,
    String phone,
    String divisionId,
    String divisionTh,
    String departmentTh,
    String positionTh,
    String level,
    String locationTh,
    String statusId,
    BigDecimal salary,
    LocalDate hireDate,
    LocalDate confirmationDate,
    String address,
    String emergencyName,
    String emergencyRelationship,
    String emergencyPhone
) {
}
