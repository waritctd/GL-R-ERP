package th.co.glr.hr.customer;

public record ContactDto(
    long   id,
    long   customerId,
    String firstName,
    String lastName,
    String position,
    String email,
    String phone
) {
    public String fullName() {
        if (lastName == null || lastName.isBlank()) return firstName;
        return firstName + " " + lastName;
    }
}
