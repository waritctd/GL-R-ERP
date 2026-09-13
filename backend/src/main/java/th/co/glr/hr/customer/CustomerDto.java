package th.co.glr.hr.customer;

public record CustomerDto(long id, String name, String taxId, String address, String branch, String phone,
    String addressLine, String provinceCode, String districtCode, String subdistrictCode, String postalCode,
    String provinceNameTh, String districtNameTh, String subdistrictNameTh, String legacyAddress) {
    public CustomerDto(long id, String name, String taxId, String address, String branch, String phone) {
        this(id, name, taxId, address, branch, phone, null, null, null, null, null, null, null, null, null);
    }
}
