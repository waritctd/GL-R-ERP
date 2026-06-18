package th.co.glr.hr.employee;

public record AddressDto(String line1, String district, String province, String postalCode) {
    public static AddressDto empty() {
        return new AddressDto("", "", "", "");
    }
}
