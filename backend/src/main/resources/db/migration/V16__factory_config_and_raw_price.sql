-- R3: Factory email/currency config + raw price fields on ticket_item

CREATE TABLE sales.factory_config (
    factory_config_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    factory_name      VARCHAR(200) NOT NULL UNIQUE,
    email             VARCHAR(200),
    currency          VARCHAR(10)  NOT NULL DEFAULT 'THB',
    unit              VARCHAR(30)  NOT NULL DEFAULT 'piece', -- 'piece' | 'sqm' | 'box'
    country           VARCHAR(100),
    notes             TEXT,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now()
);

INSERT INTO sales.factory_config (factory_name, email, currency, unit, country) VALUES
  ('SCG Ceramics',      'sales@scg.co.th',         'THB', 'piece', 'Thailand'),
  ('Cotto Industry',    'orders@cotto.co.th',       'THB', 'piece', 'Thailand'),
  ('Duragres Thailand', 'sales@duragres.co.th',     'THB', 'piece', 'Thailand'),
  ('Panaria SpA',       'export@panaria.it',        'EUR', 'sqm',   'Italy');

-- Raw price fields on ticket_item (price in factory's own currency/unit)
ALTER TABLE sales.ticket_item
    ADD COLUMN raw_price    NUMERIC(14,4),
    ADD COLUMN raw_currency VARCHAR(10),
    ADD COLUMN raw_unit     VARCHAR(30);
