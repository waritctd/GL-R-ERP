-- ---------------------------------------------------------------------------------------------
-- V138 -- make hr.employee_address wide enough to receive what ล.ย.01 can hold.
--
-- Owner decision #4 (2026-08-08): the ล.ย.01 header is editable by the employee, and HR's approval
-- promotes the confirmed header into the employee master. V137 gave employee_address the five
-- missing Thai address parts, so all 13 slots now EXIST -- but three of them are still NARROWER
-- than the declaration column that feeds them:
--
--     tax_allowance_declaration        hr.employee_address        widened here
--     addr_building     VARCHAR(200)   building  VARCHAR(120)  -> VARCHAR(200)
--     addr_soi          VARCHAR(200)   soi       VARCHAR(120)  -> VARCHAR(200)
--     addr_road         VARCHAR(200)   road      VARCHAR(120)  -> VARCHAR(200)
--
-- Without this, an employee who fills อาคาร / ซอย / ถนน with 121-200 characters -- which the form
-- accepts and stores happily -- would make HR's approve() fail with SQLSTATE 22001 (value too long)
-- at the moment of the write-back, rolling back the whole approval transaction. The declaration
-- would be stuck un-approvable with an error that names a column the HR user never typed into.
--
-- Every other slot already matches: house_no 60, room_no 40, floor 40, village 200, moo 20,
-- junction 120, sub_district/district/province 120, postal_code 10. Checked one by one against
-- V1 and V137 rather than assumed.
--
-- Widening a VARCHAR is a metadata-only change in Postgres (no table rewrite, no lock beyond a
-- brief ACCESS EXCLUSIVE) and cannot fail on existing data -- every current value already fits in
-- the narrower type.
-- ---------------------------------------------------------------------------------------------
ALTER TABLE hr.employee_address
    ALTER COLUMN building TYPE VARCHAR(200),
    ALTER COLUMN soi      TYPE VARCHAR(200),
    ALTER COLUMN road     TYPE VARCHAR(200);

COMMENT ON COLUMN hr.employee_address.building IS
    'อาคาร. Widened to 200 in V138 to match tax_allowance_declaration.addr_building, which feeds it on ล.ย.01 approval.';
COMMENT ON COLUMN hr.employee_address.soi IS
    'ตรอก/ซอย. Widened to 200 in V138 to match tax_allowance_declaration.addr_soi.';
COMMENT ON COLUMN hr.employee_address.road IS
    'ถนน. Widened to 200 in V138 to match tax_allowance_declaration.addr_road.';
