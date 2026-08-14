-- V912: five dedicated e2e test personas for the sales-pipeline suite.
--
-- WHY THIS EXISTS. frontend/e2e-real/ can now target a deployed UAT (npm run test:e2e:uat), and it
-- needs one login per role in the sales chain. It cannot use the V900 personas and it must not use
-- real employees:
--
--   • The @uat.glr personas V900 seeded are GONE from the deployed UAT database. Measured
--     2026-08-15: zero rows match '%@uat.glr', while flyway_schema_history still records
--     V900-V911 as applied. UAT's hr.employee was rebuilt from PRODUCTION on top of the seed, so
--     the migrations are "applied" and their rows are not there. Do not assume a seeded row
--     exists because its migration ran -- on this database that inference is false.
--
--   • What replaced them is 207 real employees, whose addresses include personal gmail.com and
--     hotmail.com accounts. Driving destructive journey tests as a real person's account is wrong
--     independently of whether it works, and their addresses cannot go in a test fixture in a
--     PUBLIC repository.
--
-- So: synthetic accounts, on a domain that can never receive mail (@e2e.invalid -- .invalid is
-- reserved by RFC 2606 precisely for this), owned by nobody.
--
-- ─────────────────────────────────────────────────────────────────────────────────────────────
-- ⚠️ THESE ACCOUNTS CANNOT LOG IN UNTIL SOMEONE SETS A PASSWORD. THAT IS DELIBERATE.
--
-- password_hash is left NULL. AuthService requires passwordEncoder.matches(raw, password_hash),
-- so a NULL hash is a 401 for every input -- the accounts exist, resolve to the right roles, and
-- authenticate to nothing.
--
-- The alternative was to commit a hash, and that is exactly what must not happen here. This
-- repository is public and this database now holds production-derived personal data; a working
-- credential in the repo is a public access path to it. The existing Uat@2026 pattern (published
-- in V900/V907/V908) is the thing NOT to copy, and it is only harmless today because the accounts
-- it unlocks no longer exist.
--
-- SETUP, once, by whoever runs UAT -- not in this file, not in any file:
--
--     UPDATE hr.employee
--        SET password_hash = '<bcrypt hash>', must_change_password = FALSE
--      WHERE employee_code LIKE 'E2E-%';
--
--   Generate the hash with `htpasswd -nbBC 10 x '<password>' | cut -d: -f2`, and give the same
--   plaintext to the suite as E2E_UAT_PASSWORD. Choose a password used NOWHERE else: it unlocks
--   five accounts that can read this database's employee data.
-- ─────────────────────────────────────────────────────────────────────────────────────────────
--
-- ROLES ARE DERIVED, NOT STORED. DivisionAccessPolicy.roleFor() computes the role at login from
-- the employee's division code and position text, in a precedence ladder:
--     md OR position~'กรรมการ' -> ceo | hr | pcim -> import | ac -> account | wh | qc
--     | sa -> sales_manager if position~'ผู้จัดการ' else sales | otherwise employee
-- So the division and position chosen below ARE the role. Two consequences worth stating:
--   • The sales position must NOT contain ผู้จัดการ, or that persona silently becomes a
--     sales_manager and every "a plain rep cannot do this" assertion inverts.
--   • No position here may contain กรรมการ, which short-circuits to ceo before the sa branch is
--     ever reached. Note กรรมการผู้จัดการ contains BOTH tokens -- hence the plain wording below.
--
-- RESOLVED BY PREDICATE, NEVER BY id. Surrogate ids differ across every database this may run on.
-- The DO block below fails LOUDLY if a required division is absent rather than inserting a NULL
-- division_id, because a NULL division resolves to `employee` and would leave the suite asserting
-- role behaviour against accounts that hold no role at all -- green, and meaningless.
--
-- NOT REBUILD-PROOF. If UAT is rebuilt from production again, these rows go the same way V900's
-- did while this migration stays recorded as applied. That is a property of the rebuild process,
-- not something a migration can fix. Re-run this file's INSERTs by hand if the suite starts
-- reporting that the personas are missing.

-- ── 1. Divisions must exist. Fail loudly if not. ─────────────────────────────────────────────
DO $$
DECLARE
    v_missing text;
BEGIN
    SELECT string_agg(needed.code, ', ' ORDER BY needed.code)
      INTO v_missing
      FROM (VALUES ('SA'), ('MD'), ('PCIM'), ('AC')) AS needed(code)
     WHERE NOT EXISTS (
             SELECT 1 FROM hr.division d
              WHERE upper(btrim(coalesce(d.source_code, ''))) = needed.code
           );

    IF v_missing IS NOT NULL THEN
        RAISE EXCEPTION
            'V912: cannot seed e2e personas -- hr.division has no row with source_code in (%). '
            'DivisionAccessPolicy derives a role from the division code, so a persona without one '
            'resolves to `employee` and every role assertion in the e2e suite would be vacuous. '
            'Seed the missing division(s) first.', v_missing;
    END IF;
END $$;

-- ── 2. Dedicated positions ───────────────────────────────────────────────────────────────────
-- Own positions rather than reusing whatever this database happens to carry: the position TEXT is
-- an input to role derivation, so borrowing a production row would make the suite's roles depend
-- on someone else's naming.
INSERT INTO hr.position (source_code, name_th, is_active)
SELECT v.code, v.name_th, TRUE
  FROM (VALUES
        -- Must NOT contain ผู้จัดการ -> resolves to `sales`.
        ('E2E-SA',   'พนักงานขาย (E2E)'),
        -- Must contain ผู้จัดการ, and must NOT contain กรรมการ -> resolves to `sales_manager`.
        ('E2E-SAM',  'ผู้จัดการฝ่ายขาย (E2E)'),
        -- The three below take their role from the DIVISION; the text is descriptive only.
        ('E2E-PCM', 'เจ้าหน้าที่จัดซื้อต่างประเทศ (E2E)'),
        ('E2E-AC',   'เจ้าหน้าที่บัญชี (E2E)'),
        ('E2E-MD',   'ผู้บริหารระดับสูง (E2E)')
       ) AS v(code, name_th)
 WHERE NOT EXISTS (SELECT 1 FROM hr.position p WHERE p.source_code = v.code);

-- ── 3. The five personas ─────────────────────────────────────────────────────────────────────
-- department_id, location_id, status_id and level_id are left NULL: every one is nullable in V1,
-- none is read by DivisionAccessPolicy or AuthService, and resolving them would couple this
-- migration to reference rows that the production rebuild may or may not have carried over.
INSERT INTO hr.employee (
    employee_code, first_name_th, last_name_th, email,
    division_id, position_id,
    password_hash, must_change_password,
    pay_type, hire_date, is_active
)
SELECT v.code, v.first_name, v.last_name, v.email,
       d.division_id, p.position_id,
       NULL,          -- see the setup note in this file's header
       FALSE,         -- a forced change would block the suite on its first login
       'M', DATE '2026-01-01', TRUE
  FROM (VALUES
        ('E2E-0001', 'อีทูอี', 'ฝ่ายขาย',       'e2e-sales@e2e.invalid',        'SA',   'E2E-SA'),
        ('E2E-0002', 'อีทูอี', 'ผู้จัดการขาย',   'e2e-salesmgr@e2e.invalid',     'SA',   'E2E-SAM'),
        ('E2E-0003', 'อีทูอี', 'จัดซื้อนำเข้า',  'e2e-import@e2e.invalid',       'PCIM', 'E2E-PCM'),
        ('E2E-0004', 'อีทูอี', 'บัญชี',          'e2e-account@e2e.invalid',      'AC',   'E2E-AC'),
        ('E2E-0005', 'อีทูอี', 'ผู้บริหาร',      'e2e-ceo@e2e.invalid',          'MD',   'E2E-MD')
       ) AS v(code, first_name, last_name, email, division_code, position_code)
  JOIN hr.division d
    ON upper(btrim(coalesce(d.source_code, ''))) = v.division_code
  JOIN hr.position p
    ON p.source_code = v.position_code
ON CONFLICT (employee_code) DO NOTHING;

-- ── 4. Prove the roles actually derive as intended ───────────────────────────────────────────
-- Re-implements DivisionAccessPolicy's ladder for exactly these five rows and refuses to leave a
-- persona misfiled. Without this the failure surfaces much later and much more confusingly: the
-- suite logs in fine, global-setup's `user.role !== role` check trips, and whoever reads it has to
-- work backwards to a division code chosen here.
DO $$
DECLARE
    v_wrong text;
BEGIN
    SELECT string_agg(format('%s expected %s got %s', x.code, x.expected, x.derived), '; ')
      INTO v_wrong
      FROM (
            SELECT e.employee_code AS code,
                   v.expected,
                   CASE
                     WHEN upper(btrim(coalesce(d.source_code,''))) = 'MD'
                       OR coalesce(p.name_th,'') LIKE '%กรรมการ%'                      THEN 'ceo'
                     WHEN upper(btrim(coalesce(d.source_code,''))) = 'PCIM'            THEN 'import'
                     WHEN upper(btrim(coalesce(d.source_code,''))) = 'AC'              THEN 'account'
                     WHEN upper(btrim(coalesce(d.source_code,''))) = 'SA'
                       AND replace(coalesce(p.name_th,''), ' ', '') LIKE '%ผู้จัดการ%' THEN 'sales_manager'
                     WHEN upper(btrim(coalesce(d.source_code,''))) = 'SA'              THEN 'sales'
                     ELSE 'employee'
                   END AS derived
              FROM hr.employee e
              LEFT JOIN hr.division d ON d.division_id = e.division_id
              LEFT JOIN hr.position p ON p.position_id = e.position_id
              JOIN (VALUES
                    ('E2E-0001','sales'), ('E2E-0002','sales_manager'), ('E2E-0003','import'),
                    ('E2E-0004','account'), ('E2E-0005','ceo')
                   ) AS v(code, expected) ON v.code = e.employee_code
           ) x
     WHERE x.derived <> x.expected;

    IF v_wrong IS NOT NULL THEN
        RAISE EXCEPTION 'V912: a persona does not derive its intended role -- %', v_wrong;
    END IF;
END $$;
