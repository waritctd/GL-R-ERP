package th.co.glr.hr.employee;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record UpsertEmployeeRequest(
    @Size(max = 20) String code,
    @Size(max = 20) String badge,
    @Size(max = 200) String nameTh,
    @Size(max = 200) String nameEn,
    @Size(max = 60) String nickName,
    @Size(max = 50) String titleTh,
    @Size(max = 20) String genderTh,
    @Past LocalDate birthDate,
    @Size(max = 60) String nationality,
    @Size(max = 30) String maritalStatus,
    @Email @Size(max = 255) String email,
    @Size(max = 40) String phone,
    @Size(max = 10) String divisionId,
    @Size(max = 120) String divisionTh,
    @Size(max = 120) String departmentTh,
    @Size(max = 120) String positionTh,
    @Size(max = 60) String level,
    @Size(max = 255) String locationTh,
    @Size(max = 10) String statusId,
    @DecimalMin("0.00") BigDecimal salary,
    @DecimalMin("0.00") BigDecimal directorRemuneration,
    @PastOrPresent LocalDate hireDate,
    @PastOrPresent LocalDate confirmationDate,
    @Size(max = 500) String address,
    @Size(max = 200) String emergencyName,
    @Size(max = 80) String emergencyRelationship,
    @Size(max = 40) String emergencyPhone
) {
}
