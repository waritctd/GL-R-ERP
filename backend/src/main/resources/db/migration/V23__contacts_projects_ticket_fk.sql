-- R1: contacts + projects + ticket FK links

CREATE TABLE sales.contact (
    contact_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES sales.customer(customer_id) ON DELETE CASCADE,
    first_name  VARCHAR(100) NOT NULL,
    last_name   VARCHAR(100),
    position    VARCHAR(100),
    email       VARCHAR(200),
    phone       VARCHAR(50),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_contact_customer ON sales.contact(customer_id);

CREATE TABLE sales.project (
    project_id  BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    customer_id BIGINT NOT NULL REFERENCES sales.customer(customer_id) ON DELETE CASCADE,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_project_customer ON sales.project(customer_id);

ALTER TABLE sales.ticket
    ADD COLUMN customer_id BIGINT REFERENCES sales.customer(customer_id),
    ADD COLUMN project_id  BIGINT REFERENCES sales.project(project_id),
    ADD COLUMN contact_id  BIGINT REFERENCES sales.contact(contact_id);

-- Demo contacts
INSERT INTO sales.contact (customer_id, first_name, last_name, position, email, phone) VALUES
(1, 'วิภา',   'สมิทธ์',    'ผู้จัดการโครงการ', 'wipa@kaona.co.th',    '081-111-2222'),
(1, 'ธนพล',   'อภิชัย',    'วิศวกรโยธา',       'thanaphon@kaona.co.th','082-333-4444'),
(2, 'ปรีชา',  'วงศ์สกุล',  'จัดซื้อ',          'preecha@tld.co.th',    '083-555-6666'),
(3, 'สุภาพร', 'ทองดี',     'ผู้อำนวยการ',       'supaporn@pdg.co.th',  '084-777-8888'),
(4, 'กมล',    'เรืองศรี',  'ผู้จัดการ',         'kamol@rp.co.th',       '085-999-0000');

-- Demo projects
INSERT INTO sales.project (customer_id, name) VALUES
(1, 'โครงการ Central Ladprao ชั้น B1'),
(1, 'โครงการ The Mall Bangkapi'),
(2, 'โครงการ Asoke Tower ชั้น 12-15'),
(3, 'โครงการ PDG HQ Renovation'),
(4, 'โครงการ Rueangchat Condo Phase 2');
