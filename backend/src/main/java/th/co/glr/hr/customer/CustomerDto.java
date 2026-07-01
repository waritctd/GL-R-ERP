package th.co.glr.hr.customer;

public record CustomerDto(
    long   id,
    String name,
    String taxId,
    String address,
    String branch,
    String phone
) {}
