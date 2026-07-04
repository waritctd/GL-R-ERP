package th.co.glr.hr.factory;

public record FactoryConfigDto(
    long   id,
    String factoryName,
    String email,
    String currency,
    String unit,
    String country
) {}
