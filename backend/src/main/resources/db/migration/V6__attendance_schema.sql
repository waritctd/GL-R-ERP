SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- ATTENDANCE: sites, devices, raw punches, daily payroll view, imports
-- ---------------------------------------------------------------------

CREATE TABLE hr.attendance_site (
    site_code   VARCHAR(20) PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    timezone    VARCHAR(64) NOT NULL DEFAULT 'Asia/Bangkok',
    is_active   BOOLEAN NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_attendance_site_code_upper CHECK (site_code = upper(site_code))
);

INSERT INTO hr.attendance_site (site_code, name)
VALUES
    ('SHOWROOM', 'GL&R Office / Showroom'),
    ('WAREHOUSE', 'GL&R Warehouse'),
    ('WFH', 'Work From Home')
ON CONFLICT (site_code) DO NOTHING;

CREATE TABLE hr.attendance_device (
    device_id          BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_code        VARCHAR(40) NOT NULL UNIQUE,
    site_code          VARCHAR(20) NOT NULL REFERENCES hr.attendance_site(site_code),
    device_name        VARCHAR(120) NOT NULL,
    model              VARCHAR(80) NOT NULL DEFAULT 'ZKTeco SC700',
    serial_no          VARCHAR(120),
    ip_address         INET,
    tcp_port           INTEGER NOT NULL DEFAULT 4370,
    comm_key_required  BOOLEAN NOT NULL DEFAULT FALSE,
    is_active          BOOLEAN NOT NULL DEFAULT TRUE,
    installed_at       TIMESTAMPTZ,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_attendance_device_port CHECK (tcp_port BETWEEN 1 AND 65535),
    CONSTRAINT chk_attendance_device_code_upper CHECK (device_code = upper(device_code))
);

INSERT INTO hr.attendance_device (device_code, site_code, device_name, ip_address, tcp_port)
VALUES ('SHOWROOM_SC700', 'SHOWROOM', 'Showroom ZKTeco SC700', '192.168.1.201', 4370)
ON CONFLICT (device_code) DO NOTHING;

CREATE TABLE hr.attendance_punch (
    punch_id       BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    device_id      BIGINT REFERENCES hr.attendance_device(device_id) ON DELETE SET NULL,
    site_code      VARCHAR(20) NOT NULL REFERENCES hr.attendance_site(site_code),
    employee_id    BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    badge_code     VARCHAR(20) NOT NULL,
    punch_time     TIMESTAMPTZ NOT NULL,
    work_date      DATE NOT NULL,

    -- Raw SC700 fields from the .dat/live SDK payload. Store them for audit;
    -- do not trust punch_state alone for payroll IN/OUT direction.
    device_status  SMALLINT NOT NULL DEFAULT 1,
    punch_state    SMALLINT NOT NULL DEFAULT 0,
    work_code      VARCHAR(40) NOT NULL DEFAULT '0',
    reserved_value VARCHAR(40) NOT NULL DEFAULT '0',

    punch_source   VARCHAR(20) NOT NULL DEFAULT 'BIOMETRIC',
    ingest_method  VARCHAR(20) NOT NULL DEFAULT 'LIVE_CAPTURE',
    raw_payload    JSONB NOT NULL DEFAULT '{}'::jsonb,
    received_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT chk_attendance_punch_source CHECK (
        punch_source IN ('BIOMETRIC', 'WEB_CHECKIN', 'MANUAL')
    ),
    CONSTRAINT chk_attendance_ingest_method CHECK (
        ingest_method IN ('LIVE_CAPTURE', 'CATCHUP_PULL', 'USB_DAT_IMPORT', 'WEB_PORTAL', 'MANUAL_ENTRY')
    ),
    CONSTRAINT chk_attendance_punch_badge_nonblank CHECK (btrim(badge_code) <> ''),
    CONSTRAINT chk_attendance_punch_state_range CHECK (punch_state BETWEEN 0 AND 255),
    CONSTRAINT chk_attendance_device_status_range CHECK (device_status BETWEEN 0 AND 255)
);

COMMENT ON TABLE hr.attendance_punch IS
  'Append-only raw attendance ledger from SC700 live capture, catch-up pulls, USB .dat import, web check-in, or manual entry.';
COMMENT ON COLUMN hr.attendance_punch.badge_code IS
  'Card/user id from SC700. Resolve to hr.employee.badge_card_no when possible; keep raw punch even if no employee matches yet.';
COMMENT ON COLUMN hr.attendance_punch.punch_state IS
  'Raw SC700 state field. Kept for audit/debugging, not trusted as payroll IN/OUT direction.';
COMMENT ON COLUMN hr.attendance_punch.work_date IS
  'Local business date, normally derived from punch_time in Asia/Bangkok by the importer/agent/backend.';

CREATE UNIQUE INDEX ux_attendance_punch_device_badge_time
    ON hr.attendance_punch(device_id, badge_code, punch_time)
    WHERE device_id IS NOT NULL;

CREATE UNIQUE INDEX ux_attendance_punch_site_source_badge_time
    ON hr.attendance_punch(site_code, punch_source, badge_code, punch_time)
    WHERE device_id IS NULL;

CREATE INDEX idx_employee_badge_card_no
    ON hr.employee(badge_card_no)
    WHERE badge_card_no IS NOT NULL AND btrim(badge_card_no) <> '';

CREATE INDEX idx_attendance_punch_employee_date
    ON hr.attendance_punch(employee_id, work_date, punch_time);

CREATE INDEX idx_attendance_punch_site_date
    ON hr.attendance_punch(site_code, work_date, punch_time);

CREATE INDEX idx_attendance_punch_badge_date
    ON hr.attendance_punch(badge_code, work_date, punch_time);

CREATE INDEX idx_attendance_punch_unresolved_badge
    ON hr.attendance_punch(badge_code, punch_time)
    WHERE employee_id IS NULL;

CREATE TABLE hr.attendance_daily (
    attendance_daily_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    employee_id         BIGINT NOT NULL REFERENCES hr.employee(employee_id) ON DELETE CASCADE,
    work_date           DATE NOT NULL,
    site_code           VARCHAR(20) NOT NULL REFERENCES hr.attendance_site(site_code),
    check_in_punch_id   BIGINT REFERENCES hr.attendance_punch(punch_id) ON DELETE SET NULL,
    check_out_punch_id  BIGINT REFERENCES hr.attendance_punch(punch_id) ON DELETE SET NULL,
    check_in            TIMESTAMPTZ,
    check_out           TIMESTAMPTZ,
    total_minutes       INTEGER,
    late_minutes        INTEGER NOT NULL DEFAULT 0,
    early_leave_minutes INTEGER NOT NULL DEFAULT 0,
    overtime_minutes    INTEGER NOT NULL DEFAULT 0,
    punch_count         INTEGER NOT NULL DEFAULT 0,
    is_absent           BOOLEAN NOT NULL DEFAULT FALSE,
    is_manual_override  BOOLEAN NOT NULL DEFAULT FALSE,
    notes               TEXT,
    calculated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_attendance_daily_employee_date UNIQUE (employee_id, work_date),
    CONSTRAINT chk_attendance_daily_checkout_after_checkin CHECK (
        check_in IS NULL OR check_out IS NULL OR check_out >= check_in
    ),
    CONSTRAINT chk_attendance_daily_minutes_nonnegative CHECK (
        (total_minutes IS NULL OR total_minutes >= 0)
        AND late_minutes >= 0
        AND early_leave_minutes >= 0
        AND overtime_minutes >= 0
        AND punch_count >= 0
    )
);

CREATE INDEX idx_attendance_daily_date_site
    ON hr.attendance_daily(work_date, site_code);

CREATE INDEX idx_attendance_daily_employee
    ON hr.attendance_daily(employee_id, work_date DESC);

CREATE TABLE hr.attendance_import_file (
    import_id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    site_code             VARCHAR(20) NOT NULL REFERENCES hr.attendance_site(site_code),
    device_id             BIGINT REFERENCES hr.attendance_device(device_id) ON DELETE SET NULL,
    source_file_name      VARCHAR(255) NOT NULL,
    file_hash             CHAR(64) NOT NULL UNIQUE,
    file_size_bytes       BIGINT,
    row_count             INTEGER NOT NULL DEFAULT 0,
    inserted_punch_count  INTEGER NOT NULL DEFAULT 0,
    skipped_punch_count   INTEGER NOT NULL DEFAULT 0,
    error_count           INTEGER NOT NULL DEFAULT 0,
    imported_by_employee_id BIGINT REFERENCES hr.employee(employee_id) ON DELETE SET NULL,
    imported_at           TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_attendance_import_hash_hex CHECK (file_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT chk_attendance_import_counts_nonnegative CHECK (
        row_count >= 0
        AND inserted_punch_count >= 0
        AND skipped_punch_count >= 0
        AND error_count >= 0
    )
);

CREATE INDEX idx_attendance_import_site_time
    ON hr.attendance_import_file(site_code, imported_at DESC);

CREATE TABLE hr.attendance_import_error (
    import_error_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    import_id       BIGINT NOT NULL REFERENCES hr.attendance_import_file(import_id) ON DELETE CASCADE,
    line_no         INTEGER NOT NULL,
    raw_line        TEXT,
    error_code      VARCHAR(80) NOT NULL,
    error_message   TEXT NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_attendance_import_error_line CHECK (line_no > 0)
);

CREATE INDEX idx_attendance_import_error_import
    ON hr.attendance_import_error(import_id, line_no);
