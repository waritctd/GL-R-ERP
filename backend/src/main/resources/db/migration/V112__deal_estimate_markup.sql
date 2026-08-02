-- Deal-create modal's "ราคาตั้ง (ประมาณการ)" estimate: the catalog/supplier price is a FACTORY
-- cost, and the owner does not want raw cost shown to a rep as the number they say out loud to a
-- customer -- it must be multiplied by a coarse markup first ("maybe x1.5 x2", the owner's own
-- words) so the on-screen figure approximates what GL&R would actually sell for.
--
-- This is deliberately its OWN single-row config, not sales.price_calc_config.margin_pct. That
-- table IS the real margin policy -- issue #388 locked `sales` out of reading it on purpose, and
-- this feature must not smuggle that policy back out through a different door. This table holds
-- nothing but a display multiplier: no country/effective-dating/landed-cost columns, and (see
-- DealEstimateMarkupController) its READ is open to any authenticated user precisely because it
-- carries none of price_calc_config's sensitive fields.
--
-- Single row by design (id fixed to 1 via CHECK) -- there is exactly one multiplier, not a
-- per-country or per-effective-date series like price_calc_config/fx_rates. The CEO retunes it
-- from CeoSettingsPage; no deploy required either way.
CREATE TABLE sales.deal_estimate_markup (
    id         SMALLINT      PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    multiplier NUMERIC(6,3)  NOT NULL CHECK (multiplier > 0),
    updated_at TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by BIGINT REFERENCES hr.employee(employee_id)
);

-- Seed: the owner's own starting figure ("maybe x1.5 x2") -- CEO can retune from CeoSettingsPage.
INSERT INTO sales.deal_estimate_markup (id, multiplier) VALUES (1, 2.000);
