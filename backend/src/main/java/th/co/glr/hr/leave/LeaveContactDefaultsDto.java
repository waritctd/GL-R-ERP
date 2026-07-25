package th.co.glr.hr.leave;

/**
 * Paper-form (ใบลาหยุด F-HR-020) autofill defaults for the "contact during leave" block, plus the
 * read-only position/department/division shown alongside it. Backs {@code GET
 * /api/leave/contact-defaults} -- see {@link LeaveRepository#findContactDefaults} and {@link
 * LeaveService#contactDefaults}.
 */
public record LeaveContactDefaultsDto(
    long employeeId,
    String positionTh,
    String departmentTh,
    String divisionTh,
    String contactHouseNo,
    String contactSubdistrict,
    String contactDistrict,
    String contactProvince,
    String contactPhone
) {
}
