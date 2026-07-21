SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- SPECIAL MONEY REQUESTS: welfare policy ("สวัสดิการ", signed 2018)
-- per-diem, medical, uniform, life-event aid.
--
-- This slice builds the data model and rules engine only. Approved
-- amounts do NOT yet flow into payroll -- that wiring is gated on an
-- external sign-off and is explicitly out of scope here.
--
-- NOT MODELLED: 2.5 0% loan (explicitly out of scope)
-- ---------------------------------------------------------------------

CREATE TABLE hr.special_money_request (
    special_money_request_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id              BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    request_type             VARCHAR(40) NOT NULL,
    event_date               DATE NOT NULL,
    event_end_date           DATE,
    receipt_date             DATE,
    quantity                 NUMERIC(8,2) NOT NULL DEFAULT 1,
    requested_amount         NUMERIC(12,2) NOT NULL,
    approved_amount          NUMERIC(12,2),
    payroll_bucket           VARCHAR(20) NOT NULL,
    policy_version           INTEGER NOT NULL,
    reason                   TEXT NOT NULL,
    detail                   JSONB NOT NULL DEFAULT '{}'::jsonb,
    status                   VARCHAR(20) NOT NULL DEFAULT 'SUBMITTED',
    payroll_month            DATE,
    included_period_id       BIGINT REFERENCES hr.payroll_period(period_id) ON DELETE SET NULL,
    cap_override_reason      TEXT,
    requested_by_id          BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    requested_at             TIMESTAMPTZ NOT NULL DEFAULT now(),
    manager_approved_by      BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    manager_approved_at      TIMESTAMPTZ,
    ceo_approved_by          BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    ceo_approved_at          TIMESTAMPTZ,
    reviewed_by_id           BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    reviewed_at               TIMESTAMPTZ,
    reviewer_note             TEXT,
    cancelled_at              TIMESTAMPTZ,
    created_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at                TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_smr_status CHECK (
        status IN ('SUBMITTED', 'MANAGER_APPROVED', 'APPROVED', 'REJECTED', 'CANCELLED')
    ),
    CONSTRAINT chk_smr_type CHECK (
        request_type IN (
            'UNIFORM_ANNUAL', 'UNIFORM_NEW_STAFF', 'UNIFORM_PREPROBATION_KIT',
            'TRAVEL_PER_DIEM', 'TRAVEL_LODGING',
            'MEDICAL',
            'AID_WEDDING', 'AID_ORDINATION', 'AID_CHILDBIRTH', 'AID_FUNERAL',
            'TRAINING', 'OTHER'
        )
    ),
    CONSTRAINT chk_smr_bucket CHECK (payroll_bucket IN ('PER_DIEM', 'AID', 'NON_TAXABLE')),
    CONSTRAINT chk_smr_amount_positive CHECK (requested_amount > 0),
    CONSTRAINT chk_smr_approved_amount CHECK (approved_amount IS NULL OR approved_amount >= 0),
    CONSTRAINT chk_smr_reason_nonblank CHECK (btrim(reason) <> ''),
    CONSTRAINT chk_smr_date_order CHECK (event_end_date IS NULL OR event_end_date >= event_date),
    CONSTRAINT chk_smr_payroll_month_first CHECK (
        payroll_month IS NULL OR payroll_month = date_trunc('month', payroll_month)::date
    ),
    -- Invariant payroll will depend on: an APPROVED request must already carry the amount and
    -- month it will be paid in. Do not relax this without updating the (not-yet-built) payroll
    -- integration that reads off of it.
    CONSTRAINT chk_smr_approved_complete CHECK (
        status <> 'APPROVED' OR (approved_amount IS NOT NULL AND payroll_month IS NOT NULL)
    )
);

CREATE INDEX idx_smr_employee_type_event
    ON hr.special_money_request(employee_id, request_type, event_date DESC);

-- Partial index: only APPROVED rows are ever looked up by payroll month/bucket.
CREATE INDEX idx_smr_payroll
    ON hr.special_money_request(payroll_month, payroll_bucket)
    WHERE status = 'APPROVED';

CREATE INDEX idx_smr_status_requested
    ON hr.special_money_request(status, requested_at DESC);

-- Race-proof "once per lifetime" guard for wedding/ordination aid -- a Java-only check is not
-- enough under concurrent submissions.
CREATE UNIQUE INDEX ux_smr_once_per_lifetime
    ON hr.special_money_request(employee_id, request_type)
    WHERE request_type IN ('AID_WEDDING', 'AID_ORDINATION')
      AND status IN ('SUBMITTED', 'MANAGER_APPROVED', 'APPROVED');

COMMENT ON TABLE hr.special_money_request IS
    'Welfare/special-money requests (per-diem, medical, uniform, life-event aid). Approved amounts are not yet wired into payroll.';
COMMENT ON COLUMN hr.special_money_request.payroll_bucket IS
    'Snapshot of SpecialMoneyType.payrollBucket at submission time (PER_DIEM | AID | NON_TAXABLE).';
COMMENT ON COLUMN hr.special_money_request.policy_version IS
    'Snapshot of the hr.special_money_policy row-set version used to evaluate this request.';
COMMENT ON COLUMN hr.special_money_request.payroll_month IS
    'NULL until CEO approval; assigned at approval time and frozen thereafter.';
COMMENT ON COLUMN hr.special_money_request.cap_override_reason IS
    'Required when approved_amount exceeds the policy cap for this request_type.';

-- ---------------------------------------------------------------------
-- Attachments (receipts / evidence)
-- ---------------------------------------------------------------------

CREATE TABLE hr.special_money_request_attachment (
    attachment_id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    special_money_request_id  BIGINT NOT NULL REFERENCES hr.special_money_request(special_money_request_id) ON DELETE CASCADE,
    file_name                 VARCHAR(255) NOT NULL,
    storage_path               TEXT NOT NULL,
    mime_type                  VARCHAR(100),
    size_bytes                 BIGINT,
    uploaded_by_id              BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    uploaded_at                 TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_smr_attachment_request
    ON hr.special_money_request_attachment(special_money_request_id);

COMMENT ON TABLE hr.special_money_request_attachment IS
    'Evidence (receipts, photos, documents) attached to a special-money request.';

-- ---------------------------------------------------------------------
-- Policy amounts (effective-dated). Shapes live in SpecialMoneyType (Java);
-- this table holds only the numbers.
-- ---------------------------------------------------------------------

CREATE TABLE hr.special_money_policy (
    policy_id       INTEGER GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    request_type    VARCHAR(40) NOT NULL,
    policy_key      VARCHAR(60) NOT NULL,
    -- Most policy settings are money or counts, so they live in `amount`. A few are identifiers
    -- (e.g. a hr.department.source_code, which is VARCHAR and need not be numeric) and belong in
    -- `text_value`. Exactly one of the two carries the setting.
    amount          NUMERIC(12,2),
    text_value      VARCHAR(120),
    effective_from  DATE NOT NULL,
    effective_to    DATE,
    version         INTEGER NOT NULL DEFAULT 1,
    updated_by_id   BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_smp_type_key_effective UNIQUE (request_type, policy_key, effective_from),
    CONSTRAINT chk_smp_value_present CHECK (amount IS NOT NULL OR text_value IS NOT NULL)
);

COMMENT ON TABLE hr.special_money_policy IS
    'Effective-dated welfare policy amounts. Rule shapes (which keys apply to which type) live in SpecialMoneyType.java; this table only snapshots the numbers over time.';

-- Seed: 2018-06-08 welfare policy figures.
INSERT INTO hr.special_money_policy (request_type, policy_key, amount, effective_from) VALUES
    ('MEDICAL', 'cap', 3000, DATE '2018-06-08'),

    ('AID_WEDDING',    'cap', 5000, DATE '2018-06-08'),
    ('AID_ORDINATION', 'cap', 5000, DATE '2018-06-08'),
    ('AID_CHILDBIRTH', 'cap', 5000, DATE '2018-06-08'),
    ('AID_FUNERAL',    'cap', 5000, DATE '2018-06-08'),

    ('UNIFORM_ANNUAL', 'cap',              1300, DATE '2018-06-08'),
    ('UNIFORM_ANNUAL', 'per_piece_shirt',   300, DATE '2018-06-08'),
    ('UNIFORM_ANNUAL', 'per_piece_trouser', 350, DATE '2018-06-08'),
    ('UNIFORM_ANNUAL', 'max_pieces',          4, DATE '2018-06-08'),

    ('UNIFORM_NEW_STAFF', 'max_pieces', 6, DATE '2018-06-08'),

    ('UNIFORM_PREPROBATION_KIT', 'tshirt',      220, DATE '2018-06-08'),
    ('UNIFORM_PREPROBATION_KIT', 'tshirt_qty',    3, DATE '2018-06-08'),
    ('UNIFORM_PREPROBATION_KIT', 'trouser',     300, DATE '2018-06-08'),
    ('UNIFORM_PREPROBATION_KIT', 'trouser_qty',   3, DATE '2018-06-08'),
    ('UNIFORM_PREPROBATION_KIT', 'shoes',       400, DATE '2018-06-08'),
    ('UNIFORM_PREPROBATION_KIT', 'shoes_qty',     1, DATE '2018-06-08'),
    ('UNIFORM_PREPROBATION_KIT', 'belt',        700, DATE '2018-06-08'),
    ('UNIFORM_PREPROBATION_KIT', 'belt_qty',      1, DATE '2018-06-08'),

    ('TRAVEL_PER_DIEM', 'rate_driver', 400, DATE '2018-06-08'),
    ('TRAVEL_PER_DIEM', 'rate_loader', 200, DATE '2018-06-08'),
    ('TRAVEL_PER_DIEM', 'rate_asia',   600, DATE '2018-06-08'),
    ('TRAVEL_PER_DIEM', 'rate_other',  800, DATE '2018-06-08');

-- Placeholder: the real hr.department.source_code for the sales-support department that
-- UNIFORM_PREPROBATION_KIT gates on is unknown in this repo and must be read from the
-- production DB before this request type can be enabled. Seeded at 0 / disabled.
-- Deliberately seeded with an EMPTY text_value: the type stays disabled until a human fills in the
-- real code. An empty string is the "not configured" signal the evaluator checks for.
INSERT INTO hr.special_money_policy (request_type, policy_key, text_value, effective_from) VALUES
    ('UNIFORM_PREPROBATION_KIT', 'sales_support_department_code', '', DATE '2018-06-08');

COMMENT ON COLUMN hr.special_money_policy.text_value IS
    'Non-numeric policy settings. Currently only UNIFORM_PREPROBATION_KIT/sales_support_department_code, seeded EMPTY: the real hr.department.source_code for the sales-support department must be read from the production DB and filled in before that request type is enabled. It is a VARCHAR and need not be numeric, which is why it cannot live in `amount`.';

-- ---------------------------------------------------------------------
-- Excluded provinces (domestic per-diem does not apply -- treated as local)
-- ---------------------------------------------------------------------

CREATE TABLE hr.special_money_excluded_province (
    province_name_th VARCHAR(120) PRIMARY KEY
);

INSERT INTO hr.special_money_excluded_province (province_name_th) VALUES
    ('สมุทรปราการ'),
    ('สมุทรสาคร'),
    ('นนทบุรี'),
    ('นครปฐม'),
    ('ปทุมธานี'),
    ('ฉะเชิงเทรา'),
    ('ลาดกระบัง');
