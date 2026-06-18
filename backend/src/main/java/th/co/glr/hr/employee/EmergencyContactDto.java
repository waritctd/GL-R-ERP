package th.co.glr.hr.employee;

public record EmergencyContactDto(String name, String relationship, String phone) {
    public static EmergencyContactDto empty() {
        return new EmergencyContactDto("-", "-", "-");
    }
}
