-- Q1: Customer master + document note templates

CREATE TABLE sales.customer (
    customer_id      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name             VARCHAR(200) NOT NULL,
    tax_id           VARCHAR(20),
    address          TEXT,
    branch           VARCHAR(100) NOT NULL DEFAULT 'สำนักงานใหญ่',
    phone            VARCHAR(50),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_customer_name ON sales.customer USING gin(to_tsvector('simple', name));

CREATE TABLE sales.document_note_template (
    note_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    text             TEXT NOT NULL,
    default_selected BOOLEAN NOT NULL DEFAULT FALSE,
    sort_order       INT NOT NULL DEFAULT 0
);

INSERT INTO sales.document_note_template (text, default_selected, sort_order) VALUES
('ราคารวมค่าขนส่งถึงชั้น 1 ของหน่วยงานในเขต กทม. แต่ไม่รวมค่าตัด/ติดตั้ง', TRUE, 1),
('จ่ายเช็คในนาม บจก. จี แอล แอนด์ อาร์ฯ / โอนเข้า กสิกรไทย 003-1-15914-8 (กระแสรายวัน สาขาสุขุมวิท 33)', TRUE, 2),
('กรณีโอนเงินส่ง Pay-in มาที่ e-mail : info@glr.co.th', TRUE, 3);

-- Sample customers for demo
INSERT INTO sales.customer (name, tax_id, address, branch, phone) VALUES
('บริษัท ก้าวหน้า คอนสตรัคชั่น จำกัด',  '0105565012345', '123 ถนนสุขุมวิท แขวงคลองเตย เขตคลองเตย กรุงเทพฯ 10110', 'สำนักงานใหญ่', '02-123-4567'),
('บริษัท ไทยแลนด์ ดีเวลลอปเมนท์ จำกัด', '0105556789012', '456 ถนนรัชดาภิเษก แขวงลาดยาว เขตจตุจักร กรุงเทพฯ 10900',  'สำนักงานใหญ่', '02-234-5678'),
('บริษัท พรีเมียม ดีไซน์ กรุ๊ป จำกัด',   '0105578901234', '789 ถนนพระราม 4 แขวงพระโขนง เขตคลองเตย กรุงเทพฯ 10260',  'สำนักงานใหญ่', '02-345-6789'),
('บริษัท เรืองแสง พร็อพเพอร์ตี้ จำกัด',  '0105591234567', '321 ถนนนวมินทร์ แขวงคลองกุ่ม เขตบึงกุ่ม กรุงเทพฯ 10240', 'สำนักงานใหญ่', '02-456-7890');
