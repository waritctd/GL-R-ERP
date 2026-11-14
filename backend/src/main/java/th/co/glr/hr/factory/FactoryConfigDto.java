package th.co.glr.hr.factory;

public record FactoryConfigDto(
    long   id,
    String factoryName,
    String email,
    String currency,
    String unit,
    String country,
    /** Free-text country name, set only when #country is 'ZZ' (อื่นๆ / "other"). Null for every other country — see
     * V184's paired CHECKs on price_catalog.factories. */
    String countryOther
) {}
