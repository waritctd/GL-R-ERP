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
    // Standing withholding-tax override (2026-07-24, V88). HR-only (this whole request is HR-gated at
    // the controller). NULLABLE and meaningful: null = no standing override (compute normally);
    // @DecimalMin still permits null and only enforces >= 0 when a value is present.
    @DecimalMin("0.00") BigDecimal withholdingTaxOverride,
    @PastOrPresent LocalDate hireDate,
    @PastOrPresent LocalDate confirmationDate,
    @Size(max = 500) String address,
    @Size(max = 200) String emergencyName,
    @Size(max = 80) String emergencyRelationship,
    @Size(max = 40) String emergencyPhone
) {
    /**
     * Legacy constructor: the signature as it stood before {@code withholdingTaxOverride} (V88) was
     * inserted. Keeps every positional call site that predates the override (a number of ticket /
     * pricing / procurement integration tests build this request positionally) compiling unchanged;
     * {@code withholdingTaxOverride} defaults to {@code null} = "no standing override". Jackson binds
     * incoming JSON via the canonical constructor, so this secondary constructor does not affect the
     * request wire contract.
     */
    public UpsertEmployeeRequest(
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
        BigDecimal directorRemuneration,
        LocalDate hireDate,
        LocalDate confirmationDate,
        String address,
        String emergencyName,
        String emergencyRelationship,
        String emergencyPhone
    ) {
        this(
            code, badge, nameTh, nameEn, nickName, titleTh, genderTh, birthDate, nationality,
            maritalStatus, email, phone, divisionId, divisionTh, departmentTh, positionTh, level,
            locationTh, statusId, salary, directorRemuneration,
            null,
            hireDate, confirmationDate, address, emergencyName, emergencyRelationship, emergencyPhone
        );
    }
}
