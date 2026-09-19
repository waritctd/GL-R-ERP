package th.co.glr.hr.factory;

public record FactoryConfigDto(
    long   id,
    String factoryName,
    String email,
    String currency,
    String unit,
    String country,
    /** Free-text country name, set only when {@link #country} is {@code 'ZZ'} (อื่นๆ / "other").
     * Null for every other country — see V184's paired CHECKs on {@code price_catalog.factories}. */
    String countryOther
) {}
