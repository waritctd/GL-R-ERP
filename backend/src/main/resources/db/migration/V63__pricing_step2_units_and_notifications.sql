UPDATE sales.factory_quote_item
   SET unit_basis = CASE
       WHEN LOWER(unit_basis) IN ('sqm', 'sq.m', 'm2', 'm²', 'per_sqm', 'ตร.ม.') THEN 'PER_SQM'
       WHEN LOWER(unit_basis) IN ('box', 'per_box', 'กล่อง') THEN 'PER_BOX'
       WHEN LOWER(unit_basis) IN ('linear_m', 'linear_meter', 'meter', 'metre', 'm', 'per_linear_m', 'เมตร') THEN 'PER_LINEAR_M'
       ELSE 'PER_PIECE'
   END
 WHERE unit_basis NOT IN ('PER_SQM', 'PER_PIECE', 'PER_BOX', 'PER_LINEAR_M');

ALTER TABLE sales.factory_quote_item
    ADD CONSTRAINT chk_factory_quote_item_unit_basis_canonical
    CHECK (unit_basis IN ('PER_SQM', 'PER_PIECE', 'PER_BOX', 'PER_LINEAR_M'));

UPDATE sales.pricing_costing_item
   SET unit_basis = CASE
       WHEN LOWER(unit_basis) IN ('sqm', 'sq.m', 'm2', 'm²', 'per_sqm', 'ตร.ม.') THEN 'PER_SQM'
       WHEN LOWER(unit_basis) IN ('box', 'per_box', 'กล่อง') THEN 'PER_BOX'
       WHEN LOWER(unit_basis) IN ('linear_m', 'linear_meter', 'meter', 'metre', 'm', 'per_linear_m', 'เมตร') THEN 'PER_LINEAR_M'
       ELSE 'PER_PIECE'
   END
 WHERE unit_basis NOT IN ('PER_SQM', 'PER_PIECE', 'PER_BOX', 'PER_LINEAR_M');

ALTER TABLE sales.pricing_costing_item
    ADD CONSTRAINT chk_pricing_costing_item_unit_basis_canonical
    CHECK (unit_basis IN ('PER_SQM', 'PER_PIECE', 'PER_BOX', 'PER_LINEAR_M'));
