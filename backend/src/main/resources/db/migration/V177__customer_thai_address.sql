-- Renumbered V176->V177: V175 (approver snapshot) and V176 (quotation_item_sqm_per_box) both
-- landed on develop/main while this branch was in flight (released 2026-09-13, image v2026-09-13.1).
-- Additive: preserve every original free-text address, never guess its administrative codes.
CREATE TABLE customers.thai_provinces (
    code varchar(2) PRIMARY KEY, name_th text NOT NULL, name_en text NOT NULL
);
CREATE TABLE customers.thai_districts (
    code varchar(4) PRIMARY KEY,
    province_code varchar(2) NOT NULL REFERENCES customers.thai_provinces(code),
    name_th text NOT NULL, name_en text NOT NULL,
    UNIQUE (code, province_code)
);
CREATE INDEX ON customers.thai_districts(province_code, name_th);
CREATE TABLE customers.thai_subdistricts (
    code varchar(6) PRIMARY KEY, district_code varchar(4) NOT NULL,
    province_code varchar(2) NOT NULL, name_th text NOT NULL, name_en text NOT NULL,
    FOREIGN KEY (district_code, province_code) REFERENCES customers.thai_districts(code, province_code),
    UNIQUE (code, district_code, province_code)
);
CREATE INDEX ON customers.thai_subdistricts(district_code, name_th);
CREATE INDEX ON customers.thai_subdistricts(province_code);
-- A postcode is not an administrative primary key; multiple postcodes per subdistrict are supported.
CREATE TABLE customers.thai_subdistrict_postal_codes (
    subdistrict_code varchar(6) REFERENCES customers.thai_subdistricts(code),
    postal_code varchar(5) CHECK (postal_code ~ '^[0-9]{5}$'),
    PRIMARY KEY (subdistrict_code, postal_code)
);
ALTER TABLE customers.customer
    ADD COLUMN legacy_address text,
    ADD COLUMN address_line varchar(1500),
    ADD COLUMN province_code varchar(2),
    ADD COLUMN district_code varchar(4),
    ADD COLUMN subdistrict_code varchar(6),
    ADD COLUMN postal_code varchar(5),
    ADD COLUMN province_name_th text,
    ADD COLUMN district_name_th text,
    ADD COLUMN subdistrict_name_th text,
    ADD CONSTRAINT customer_address_hierarchy FOREIGN KEY (subdistrict_code, district_code, province_code)
        REFERENCES customers.thai_subdistricts(code, district_code, province_code) MATCH FULL,
    ADD CONSTRAINT customer_address_postal FOREIGN KEY (subdistrict_code, postal_code)
        REFERENCES customers.thai_subdistrict_postal_codes(subdistrict_code, postal_code),
    ADD CONSTRAINT customer_address_codes_complete CHECK (postal_code IS NULL OR subdistrict_code IS NOT NULL);
UPDATE customers.customer SET legacy_address = address;
CREATE INDEX ON customers.customer(subdistrict_code, district_code, province_code);
