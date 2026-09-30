-- Manager TEAM OVERRIDE (ค่าคอมผู้จัดการจากยอดรวมทีม): auto-compute the monthly override the
-- accountant types by hand today as a MANAGER-kind manual record (manual_amount), replacing the
-- hand entry with a config-driven limb inside CommissionService#computeRepPayrollCommissions.
--
-- Evidence (nine monthly commission workbooks, verified to the satang): sheet `อัตราค่าคอม` K2,
-- "ยอดรับเงิน(ไม่รวม Vat) - 3 ล้าน *0.075%". The base is the COMPANY-WIDE sum of every rep's
-- ex-VAT receipts, UNWEIGHTED -- the x2/x3 tier-base uplift is NOT included. June:
-- 11,032,569.26 -> (11,032,569.26 - 3,000,000) x 0.075% = 6,024.43. The same formula reproduces
-- Feb 1,261.60, Mar 2,483.59, Apr 2,031.21, May 3,738.05, Jul 1,862.01 and Aug 2,711.32. The
-- amount is paid in FULL to EACH recipient (not split between them); at or below the threshold
-- it is zero.
--
-- Both tables follow V108's pattern (sales.commission_incentive_tier / sales.stock_bonus_config):
-- config GENERATIONS selected by effective_from -- CommissionRepository#findTeamOverrideConfig
-- always picks the latest generation whose effective_from <= the payroll month being computed, so
-- the CEO revises the threshold/rate/recipients by inserting a later generation, with no deploy.
--
-- Fix-forward, enforced by the schema (as V108's review fix made it for the incentive ladder and
-- stock bonus): effective_from >= 2026-10-01. Fix-forward is otherwise only a DATA promise --
-- without this CHECK a later `INSERT ... effective_from = '2026-05-01'` would silently re-price a
-- month whose ภ.ง.ด.1 has already been filed, and nothing in the application layer would catch it.
--
-- WHY 2026-10-01: payroll months up to and including 2026-09-01 already carry the override as a
-- hand-entered MANAGER record (August-2026 receipts are payroll_month 2026-09-01 and already hold
-- MANAGER records 1313/1314 in prod). September-2026 receipts, paid in payroll_month 2026-10-01,
-- are the first month NOT already hand-entered. Switching the auto limb on any earlier would pay
-- the override twice. (The service additionally suppresses the auto limb for a recipient whose
-- approved manual MANAGER entries sum strictly positive -- the same replacement rule the
-- INCENTIVE and STOCK_BONUS limbs use -- which covers a month the accountant still hand-types.)

CREATE TABLE sales.commission_team_override_config (
    team_override_config_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    effective_from          DATE          NOT NULL,
    enabled                 BOOLEAN       NOT NULL DEFAULT TRUE,
    -- Company-wide ex-VAT receipts above this amount earn the override (the workbook's "3 ล้าน").
    threshold_base          NUMERIC(14,2) NOT NULL,
    -- Percent, not a fraction: 0.0750 means 0.075% (the workbook's "*0.075%").
    rate_percent            NUMERIC(7,4)  NOT NULL,
    CONSTRAINT uq_commission_team_override_config_effective UNIQUE (effective_from),
    CONSTRAINT chk_commission_team_override_config_nonnegative CHECK (
        threshold_base >= 0 AND rate_percent >= 0
    ),
    CONSTRAINT chk_commission_team_override_config_effective_not_before_launch CHECK (
        effective_from >= DATE '2026-10-01'
    )
);

-- Who receives the override. Each recipient is paid the FULL amount, so this is a plain set per
-- generation (composite PK), not a split.
CREATE TABLE sales.commission_team_override_recipient (
    team_override_config_id BIGINT NOT NULL
        REFERENCES sales.commission_team_override_config (team_override_config_id) ON DELETE CASCADE,
    employee_id             BIGINT NOT NULL REFERENCES hr.employee (employee_id),
    PRIMARY KEY (team_override_config_id, employee_id)
);

INSERT INTO sales.commission_team_override_config (effective_from, enabled, threshold_base, rate_percent)
VALUES ('2026-10-01', TRUE, 3000000.00, 0.0750);

-- Today's two recipients: employee 142 (มณฑ์ชญา / อิ๊ด) and 47 (จินตนา / ผึ้ง). Seeded with
-- INSERT ... SELECT so a database without those people (demo / test) still migrates cleanly
-- instead of failing the foreign key.
INSERT INTO sales.commission_team_override_recipient (team_override_config_id, employee_id)
SELECT c.team_override_config_id, e.employee_id
  FROM sales.commission_team_override_config c
  JOIN hr.employee e ON e.employee_id IN (142, 47)
 WHERE c.effective_from = DATE '2026-10-01';
