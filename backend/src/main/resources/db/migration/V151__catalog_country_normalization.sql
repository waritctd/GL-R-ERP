-- V151: one canonical country, so the selling-price formula's freight lookup can actually join.
--
-- The formula needs F = freight[origin_country, thickness_mm, qty_sqm]. Today the two sides of
-- that join do not speak the same language:
--
--   price_catalog.factories.country        CHAR(2)      'IT'    'ES'    'CN'
--   sales.pricing_freight_rate.origin_country VARCHAR(100) 'Italy' 'Spain' 'China'
--
-- Free text on one side, ISO code on the other, no foreign key, no mapping table. Every freight
-- lookup returns nothing, for all nine factories -- which is why 0% of the 22,455 catalogue rows
-- can currently be priced end to end.
--
-- This migration introduces price_catalog.country as the single source of truth and points BOTH
-- sides at it. The free-text column is dropped rather than left alongside the code: keeping both
-- is exactly the drift that produced the mismatch in the first place.
--
-- The Excel the formula came from groups Italy and Spain onto identical freight rates, but V109
-- correctly seeded them as separate rows, so the codes map 1:1 and no grouping logic is needed.

-- ── 1. The lookup ────────────────────────────────────────────────────────────
CREATE TABLE price_catalog.country (
    country_code CHAR(2) PRIMARY KEY,          -- ISO 3166-1 alpha-2
    name_en      TEXT NOT NULL,
    name_th      TEXT NOT NULL,
    CONSTRAINT chk_country_code_upper CHECK (country_code = upper(country_code))
);

COMMENT ON TABLE price_catalog.country IS
    'ISO 3166-1 alpha-2 lookup shared by price_catalog.factories and sales.pricing_freight_rate. '
    'Adding a supplier country is an INSERT here; both sides reference it.';

-- The four in use today, plus the tile-exporting origins the existing tests already exercised as
-- "a country we do not buy from yet" (Turkey, Vietnam) and their obvious peers.
--
-- Why more than the four: replacing a free-text field with a select removes the CEO's ability to
-- name a country nobody anticipated. Until countries are CEO-editable (a later branch), seeding a
-- realistic set is what keeps onboarding a new supplier from needing a migration. Deliberately not
-- the full 249-row ISO list -- this is a picker, not a reference table.
INSERT INTO price_catalog.country (country_code, name_en, name_th) VALUES
    ('IT', 'Italy',     'อิตาลี'),
    ('ES', 'Spain',     'สเปน'),
    ('CN', 'China',     'จีน'),
    ('TH', 'Thailand',  'ไทย'),
    ('TR', 'Turkey',    'ตุรกี'),
    ('VN', 'Vietnam',   'เวียดนาม'),
    ('IN', 'India',     'อินเดีย'),
    ('ID', 'Indonesia', 'อินโดนีเซีย'),
    ('MY', 'Malaysia',  'มาเลเซีย'),
    ('PT', 'Portugal',  'โปรตุเกส'),
    ('BR', 'Brazil',    'บราซิล'),
    ('PL', 'Poland',    'โปแลนด์');

-- ── 2. Point the catalogue side at it ────────────────────────────────────────
-- factories.country is already CHAR(2) and already carries IT/ES/CN. Fail loudly rather than
-- silently dropping a factory out of every freight lookup if that is not true.
DO $$
DECLARE bad TEXT;
BEGIN
    SELECT string_agg(DISTINCT coalesce(f.country, '(null)'), ', ')
      INTO bad
      FROM price_catalog.factories f
     WHERE f.country IS NULL
        OR NOT EXISTS (SELECT 1 FROM price_catalog.country c WHERE c.country_code = f.country);
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION
            'price_catalog.factories has country values not in price_catalog.country: %. '
            'Add them to the seed above before applying this migration.', bad;
    END IF;
END $$;

ALTER TABLE price_catalog.factories
    ALTER COLUMN country SET NOT NULL,
    ADD CONSTRAINT fk_factories_country
        FOREIGN KEY (country) REFERENCES price_catalog.country(country_code);

-- ── 3. Point the formula side at it ──────────────────────────────────────────
ALTER TABLE sales.pricing_freight_rate
    ADD COLUMN origin_country_code CHAR(2);

UPDATE sales.pricing_freight_rate r
   SET origin_country_code = c.country_code
  FROM price_catalog.country c
 WHERE btrim(lower(r.origin_country)) = lower(c.name_en);

-- Any row the name match missed would otherwise become NOT NULL-violating noise, or worse, be
-- quietly dropped from the lookup. Name the offenders instead.
DO $$
DECLARE bad TEXT;
BEGIN
    SELECT string_agg(DISTINCT origin_country, ', ')
      INTO bad
      FROM sales.pricing_freight_rate
     WHERE origin_country_code IS NULL;
    IF bad IS NOT NULL THEN
        RAISE EXCEPTION
            'sales.pricing_freight_rate has origin_country values that map to no country: %. '
            'Add them to price_catalog.country, or correct the data, before applying.', bad;
    END IF;
END $$;

ALTER TABLE sales.pricing_freight_rate
    ALTER COLUMN origin_country_code SET NOT NULL,
    ADD CONSTRAINT fk_pricing_freight_rate_country
        FOREIGN KEY (origin_country_code) REFERENCES price_catalog.country(country_code),
    DROP COLUMN origin_country;

COMMENT ON COLUMN sales.pricing_freight_rate.origin_country_code IS
    'ISO 3166-1 alpha-2, joins price_catalog.factories.country. Replaced a free-text '
    'origin_country in V151 -- the two never matched, so every freight lookup returned nothing.';

CREATE INDEX idx_pricing_freight_rate_country
    ON sales.pricing_freight_rate(formula_config_id, origin_country_code);
