-- R4: CEO Price Calculation Engine
-- fx_rates: อัตราแลกเปลี่ยน CEO อัปเดตรายเดือน (1 row per currency)
CREATE TABLE sales.fx_rates (
    fx_rate_id     BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    currency       VARCHAR(10)   NOT NULL UNIQUE,
    rate_to_thb    NUMERIC(12,6) NOT NULL,
    effective_date DATE          NOT NULL DEFAULT CURRENT_DATE,
    updated_by     BIGINT REFERENCES hr.employee(employee_id),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- price_calc_config: สูตรคำนวณราคา แบบ versioned per country
-- is_current=TRUE = config ที่ใช้งานอยู่ปัจจุบัน
CREATE TABLE sales.price_calc_config (
    config_id                        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    version                          INT           NOT NULL DEFAULT 1,
    country                          VARCHAR(100)  NOT NULL,
    freight_per_sqm                  NUMERIC(12,4) NOT NULL DEFAULT 0,
    insurance_per_sqm                NUMERIC(12,4) NOT NULL DEFAULT 0,
    inland_factory_to_port_per_sqm   NUMERIC(12,4) NOT NULL DEFAULT 0,
    inland_port_to_warehouse_per_sqm NUMERIC(12,4) NOT NULL DEFAULT 0,
    import_duty_pct                  NUMERIC(8,6)  NOT NULL DEFAULT 0,
    margin_pct                       NUMERIC(8,6)  NOT NULL DEFAULT 0.200000,
    is_current                       BOOLEAN       NOT NULL DEFAULT TRUE,
    effective_from                   DATE          NOT NULL DEFAULT CURRENT_DATE,
    updated_by                       BIGINT REFERENCES hr.employee(employee_id),
    updated_at                       TIMESTAMPTZ   NOT NULL DEFAULT now()
);

CREATE INDEX idx_pcc_current ON sales.price_calc_config(country) WHERE is_current;

-- ticket_item: เพิ่ม columns สำหรับราคาที่คำนวณแล้ว
ALTER TABLE sales.ticket_item
    ADD COLUMN calced_cost         NUMERIC(14,4),
    ADD COLUMN calced_price        NUMERIC(14,4),
    ADD COLUMN calc_config_version INT;

-- Seed: อัตราแลกเปลี่ยนเริ่มต้น
INSERT INTO sales.fx_rates (currency, rate_to_thb, effective_date) VALUES
    ('THB', 1.000000,  CURRENT_DATE),
    ('EUR', 38.500000, CURRENT_DATE),
    ('USD', 35.200000, CURRENT_DATE),
    ('JPY',  0.240000, CURRENT_DATE),
    ('CNY',  4.850000, CURRENT_DATE),
    ('GBP', 44.800000, CURRENT_DATE);

-- Seed: config เริ่มต้นแต่ละประเทศ
INSERT INTO sales.price_calc_config
    (version, country,
     freight_per_sqm, insurance_per_sqm,
     inland_factory_to_port_per_sqm, inland_port_to_warehouse_per_sqm,
     import_duty_pct, margin_pct)
VALUES
    -- ไทย: ไม่มีค่าขนส่งทางเรือ/ภาษีนำเข้า แค่ค่าขนส่งภายใน + 20% margin
    (1, 'Thailand',  0,  0,  0, 50, 0.000000, 0.200000),
    -- อิตาลี: ค่าเรือ + ประกัน + ภาษีนำเข้า 5% + 25% margin
    (1, 'Italy',   120, 15, 30, 50, 0.050000, 0.250000);
