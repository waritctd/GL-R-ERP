package th.co.glr.hr.employee;

public record SensitiveDto(
    String nationalId,
    String taxId,
    String socialSecurityNo,
    String socialSecurityHospital,
    String providentFundNo
) {
    public static SensitiveDto empty() {
        return new SensitiveDto(null, null, null, null, null);
    }
}
