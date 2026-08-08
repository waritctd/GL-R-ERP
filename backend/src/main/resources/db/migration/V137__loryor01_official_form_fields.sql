-- V137: make hr.tax_allowance_declaration able to hold a complete แบบ ล.ย.01.
--
-- Until now the declaration stored 21 allowance amounts chosen around modern Thai deductions. The
-- employee-facing screen is being restructured to be the government form itself (ฉบับ พ.ศ. 2562,
-- https://www.rd.go.th/fileadmin/tax_pdf/withhold/loryor01_290362.pdf), which asks for a header
-- block, ข้อ 1-15, and a signature. This migration adds every printed slot the table could not hold.
--
-- Owner decisions this implements (2026-08-08), stated here because three of them are deliberate
-- reversals or contract changes and a reviewer must be able to tell them from an accident:
--
--   1. The form is authoritative. Five amounts the app used to collect are NOT on ล.ย.01 --
--      ssf_allowance, thai_esg_allowance, pension_insurance_allowance, maternity_allowance,
--      political_donation. Their columns are KEPT (historical declarations and the 42 real
--      employee_tax_allowance rows must not lose data) but the form no longer collects them.
--   2. ข้อ 9 is fillable AND feeds withholding -- see the provident_fund_allowance block below.
--   3. ข้อ 11 (LTF) is printed but not fillable: abolished after tax year 2019, so no column.
--   4. spouse_allowance stays as a column but is no longer employee-declared. ล.ย.01 asks only for
--      สถานภาพ (ข้อ 1) and whether the spouse has income (ข้อ 2); HR sets the baht figure at review.
--      No derivation is introduced here -- turning a status into ฿60,000 is payroll math and stays
--      a human decision, exactly as it is on paper.

-- ---------------------------------------------------------------------------------------------
-- Header block: ส่วนหัว of the form.
--
-- Stored ON THE DECLARATION rather than read live from the employee master at print time, because
-- a filed ล.ย.01 is a point-in-time legal statement: it must keep saying what the employee attested
-- to and signed, even after they move house. The write-back to the master record happens on HR
-- approval, in the other direction.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE hr.tax_allowance_declaration
    ADD COLUMN IF NOT EXISTS taxpayer_id        VARCHAR(20),   -- เลขประจำตัวผู้เสียภาษีอากร (13 หลัก)
    ADD COLUMN IF NOT EXISTS first_name_th      VARCHAR(100),  -- ผู้มีเงินได้ชื่อ
    ADD COLUMN IF NOT EXISTS last_name_th       VARCHAR(100),  -- ชื่อสกุล
    ADD COLUMN IF NOT EXISTS addr_building      VARCHAR(200),  -- อาคาร
    ADD COLUMN IF NOT EXISTS addr_room_no       VARCHAR(40),   -- ห้องเลขที่
    ADD COLUMN IF NOT EXISTS addr_floor         VARCHAR(40),   -- ชั้นที่
    ADD COLUMN IF NOT EXISTS addr_village       VARCHAR(200),  -- หมู่บ้าน
    ADD COLUMN IF NOT EXISTS addr_house_no      VARCHAR(60),   -- เลขที่
    ADD COLUMN IF NOT EXISTS addr_moo           VARCHAR(20),   -- หมู่ที่
    ADD COLUMN IF NOT EXISTS addr_soi           VARCHAR(200),  -- ตรอก/ซอย
    ADD COLUMN IF NOT EXISTS addr_junction      VARCHAR(120),  -- แยก
    ADD COLUMN IF NOT EXISTS addr_road          VARCHAR(200),  -- ถนน
    ADD COLUMN IF NOT EXISTS addr_sub_district  VARCHAR(120),  -- ตำบล/แขวง
    ADD COLUMN IF NOT EXISTS addr_district      VARCHAR(120),  -- อำเภอ/เขต
    ADD COLUMN IF NOT EXISTS addr_province      VARCHAR(120),  -- จังหวัด
    ADD COLUMN IF NOT EXISTS addr_postal_code   VARCHAR(10);   -- รหัสไปรษณีย์

-- ---------------------------------------------------------------------------------------------
-- ข้อ 1 สถานภาพ / สถานภาพการสมรส, ข้อ 2 สถานะการมีเงินได้ของคู่สมรส.
--
-- All three are nullable: a paper form with no box ticked is a real (if incomplete) filing, and NULL
-- says "not answered" where FALSE would wrongly assert "answered: no".
-- ---------------------------------------------------------------------------------------------
ALTER TABLE hr.tax_allowance_declaration
    ADD COLUMN IF NOT EXISTS marital_state     VARCHAR(24),
    ADD COLUMN IF NOT EXISTS spousal_status    VARCHAR(24),
    ADD COLUMN IF NOT EXISTS spouse_has_income BOOLEAN;

ALTER TABLE hr.tax_allowance_declaration
    DROP CONSTRAINT IF EXISTS chk_tad_marital_state_valid;
ALTER TABLE hr.tax_allowance_declaration
    ADD CONSTRAINT chk_tad_marital_state_valid CHECK (
        marital_state IS NULL
        OR marital_state IN ('SINGLE', 'WIDOWED', 'MARRIED', 'DIED_DURING_YEAR')
    );

ALTER TABLE hr.tax_allowance_declaration
    DROP CONSTRAINT IF EXISTS chk_tad_spousal_status_valid;
ALTER TABLE hr.tax_allowance_declaration
    ADD CONSTRAINT chk_tad_spousal_status_valid CHECK (
        spousal_status IS NULL
        OR spousal_status IN ('MARRIED_WHOLE_YEAR', 'MARRIED_DURING_YEAR',
                              'DIVORCED_DURING_YEAR', 'DIED_DURING_YEAR')
    );

-- ---------------------------------------------------------------------------------------------
-- ข้อ 3 บุตร: the form prints TWO amount boxes, one per tier.
--
-- child_allowance (V105) is the ฿30,000-per-child tier. child_extra_allowance is the ฿60,000 tier
-- for บุตรคนที่สองเป็นต้นไปที่เกิดในหรือหลัง พ.ศ. 2561. The counts already exist as child_count and
-- child_count_double. children_total is the form's own "จำนวนบุตรรวม" box, which is a headcount and
-- not necessarily the number claimed -- hence separate from child_count.
--
-- The form's own field caps each count box at ONE character, so 0-9 is the constraint the document
-- imposes; mirroring it here stops the app accepting a number that could never be printed.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE hr.tax_allowance_declaration
    ADD COLUMN IF NOT EXISTS children_total        SMALLINT,
    ADD COLUMN IF NOT EXISTS child_extra_allowance NUMERIC(12, 2) NOT NULL DEFAULT 0;

ALTER TABLE hr.tax_allowance_declaration
    DROP CONSTRAINT IF EXISTS chk_tad_children_total_valid;
ALTER TABLE hr.tax_allowance_declaration
    ADD CONSTRAINT chk_tad_children_total_valid CHECK (
        children_total IS NULL OR children_total BETWEEN 0 AND 9
    );

-- ---------------------------------------------------------------------------------------------
-- ข้อ 4 ค่าอุปการะเลี้ยงดูบิดามารดา and ข้อ 6 เบี้ยประกันสุขภาพบิดามารดา.
--
-- Two rows each on the form -- ของผู้มีเงินได้ and ของคู่สมรสที่ไม่มีเงินได้ -- with an independent
-- บิดา and มารดา tick per row. Independent, not exclusive: ข้อ 4 prints "หักได้คนละ 30,000 บาท" and
-- ข้อ 6 prints "รวมกันแล้วไม่เกิน 15,000 บาท", wording that only works if both can be ticked.
-- (The template PDF miswires each pair as one radio group; the printed text governs. See
-- LorYor01Renderer's javadoc.)
--
-- ข้อ 4 has its own amount box per row, so the spouse-side row needs a second column;
-- parent_care_allowance (V105) is the ผู้มีเงินได้ row. ข้อ 6 has ONE amount box spanning both rows,
-- so parent_health_insurance_allowance (V105) still covers it and no column is added.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE hr.tax_allowance_declaration
    ADD COLUMN IF NOT EXISTS own_father_supported          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS own_mother_supported          BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS spouse_father_supported       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS spouse_mother_supported       BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS spouse_parent_care_allowance  NUMERIC(12, 2) NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS own_father_health_insured     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS own_mother_health_insured     BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS spouse_father_health_insured  BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS spouse_mother_health_insured  BOOLEAN NOT NULL DEFAULT FALSE;

-- ---------------------------------------------------------------------------------------------
-- ข้อ 10 ชื่อผู้ขายหน่วยลงทุน and ข้อ 15 เงินบริจาคอื่น ๆ (ระบุ) -- printed free-text slots.
--
-- ข้อ 11's ชื่อผู้ขายหน่วยลงทุน gets no column for the same reason its amount does not: LTF is dead.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE hr.tax_allowance_declaration
    ADD COLUMN IF NOT EXISTS rmf_seller_name    TEXT,
    ADD COLUMN IF NOT EXISTS other_donation_note TEXT;

-- ---------------------------------------------------------------------------------------------
-- ข้อ 9 เงินสะสมที่จ่ายเข้ากองทุนสำรองเลี้ยงชีพ / กอช. / กบข. / กองทุนสงเคราะห์ครูโรงเรียนเอกชน.
--
-- THIS REVERSES V99, deliberately and on the owner's instruction (2026-08-08).
--
-- V99 dropped provident_fund_allowance on 2026-07-29 after verifying in production that no employee
-- has a provident_fund_no and that GL&R runs no provident fund. That finding stands for the
-- employer-run PVD -- but ข้อ 9 is broader than PVD: it also covers กองทุนการออมแห่งชาติ (กอช.),
-- which is an individual membership an employee can hold whatever their employer does. The form
-- prints one box for all four funds, so the box has to be fillable for the กอช. case to be
-- declarable at all.
--
-- The column returns to BOTH tables because the declaration promotes into employee_tax_allowance at
-- apply time and the two shapes must match, and the amount feeds PayrollCalculator's ฿500,000
-- retirement cluster -- see that class for the ordering (a contribution taken at source cannot be
-- stopped by the employee, so it exhausts the cluster before the voluntary purchases).
--
-- NOTE for whoever reads V99 next: PayrollProvidentFundRemovalIntegrationTest asserted this column
-- was gone. It is rewritten alongside this migration rather than deleted, so the reversal is
-- recorded on both sides.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE hr.tax_allowance_declaration
    ADD COLUMN IF NOT EXISTS provident_fund_allowance NUMERIC(12, 2) NOT NULL DEFAULT 0;

ALTER TABLE hr.employee_tax_allowance
    ADD COLUMN IF NOT EXISTS provident_fund_allowance NUMERIC(12, 2) NOT NULL DEFAULT 0;

-- Both non-negative guards are multi-column, so they must be dropped and rebuilt to take the new
-- term -- Postgres will not let a CHECK grow in place. Rebuilt verbatim plus the PVD term; V99's
-- own lesson was that touching one of these carelessly silently drops every other invariant in it.
ALTER TABLE hr.employee_tax_allowance
    DROP CONSTRAINT IF EXISTS chk_eta_non_negative;
ALTER TABLE hr.employee_tax_allowance
    ADD CONSTRAINT chk_eta_non_negative CHECK (
        spouse_allowance >= 0 AND child_allowance >= 0 AND parent_care_allowance >= 0 AND
        disabled_care_allowance >= 0 AND maternity_allowance >= 0 AND life_insurance_allowance >= 0 AND
        health_insurance_allowance >= 0 AND parent_health_insurance_allowance >= 0 AND rmf_allowance >= 0 AND
        ssf_allowance >= 0 AND pension_insurance_allowance >= 0 AND thai_esg_allowance >= 0 AND
        home_loan_interest_allowance >= 0 AND education_donation >= 0 AND general_donation >= 0 AND
        political_donation >= 0 AND provident_fund_allowance >= 0
    );

ALTER TABLE hr.tax_allowance_declaration
    DROP CONSTRAINT IF EXISTS chk_tad_non_negative;
ALTER TABLE hr.tax_allowance_declaration
    ADD CONSTRAINT chk_tad_non_negative CHECK (
        spouse_allowance >= 0 AND child_allowance >= 0 AND parent_care_allowance >= 0 AND
        disabled_care_allowance >= 0 AND maternity_allowance >= 0 AND life_insurance_allowance >= 0 AND
        health_insurance_allowance >= 0 AND parent_health_insurance_allowance >= 0 AND rmf_allowance >= 0 AND
        ssf_allowance >= 0 AND pension_insurance_allowance >= 0 AND thai_esg_allowance >= 0 AND
        home_loan_interest_allowance >= 0 AND education_donation >= 0 AND general_donation >= 0 AND
        political_donation >= 0 AND provident_fund_allowance >= 0 AND
        child_extra_allowance >= 0 AND spouse_parent_care_allowance >= 0
    );

-- ---------------------------------------------------------------------------------------------
-- hr.employee_address: five parts of a Thai address the table has never been able to hold.
--
-- ล.ย.01 asks for 13 address slots; employee_address covers 8 (house_no, building, soi, road,
-- subdistrict, district, province, postal_code). Without these five, HR approving a declaration
-- could only write back two thirds of the address the employee just attested to, which is a worse
-- state than not writing back at all -- the master record would look updated while quietly missing
-- หมู่ที่, the part that actually finds a house outside a city.
--
-- Nullable with no default: every existing row keeps meaning exactly what it meant.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE hr.employee_address
    ADD COLUMN IF NOT EXISTS room_no   VARCHAR(40),   -- ห้องเลขที่
    ADD COLUMN IF NOT EXISTS floor     VARCHAR(40),   -- ชั้นที่
    ADD COLUMN IF NOT EXISTS village   VARCHAR(200),  -- หมู่บ้าน
    ADD COLUMN IF NOT EXISTS moo       VARCHAR(20),   -- หมู่ที่
    ADD COLUMN IF NOT EXISTS junction  VARCHAR(120);  -- แยก

COMMENT ON COLUMN hr.tax_allowance_declaration.provident_fund_allowance IS
    'ข้อ 9 ล.ย.01 - กองทุนสำรองเลี้ยงชีพ/กอช./กบข./กองทุนสงเคราะห์ครูโรงเรียนเอกชน. Re-added in V137 after V99 dropped it; see V137 for why.';
COMMENT ON COLUMN hr.tax_allowance_declaration.children_total IS
    'ข้อ 3 ล.ย.01 - จำนวนบุตรรวม (headcount). Not the number claimed; that is child_count.';
COMMENT ON COLUMN hr.tax_allowance_declaration.child_extra_allowance IS
    'ข้อ 3 ล.ย.01 - the 60,000-baht tier (บุตรคนที่สองเป็นต้นไปที่เกิดในหรือหลัง พ.ศ. 2561). child_allowance is the 30,000 tier.';
COMMENT ON COLUMN hr.tax_allowance_declaration.spouse_parent_care_allowance IS
    'ข้อ 4 ล.ย.01 - the ของคู่สมรสที่ไม่มีเงินได้ row. parent_care_allowance is the ของผู้มีเงินได้ row.';
