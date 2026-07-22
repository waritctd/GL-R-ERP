-- Commission redesign calc-refine slice: 2x/3x weighted tier-base credit (workbook column O).
--
-- Certain receipts count DOUBLE toward the monthly TIER BASE used for the progressive commission
-- bracket calculation -- NOT toward real cash (actual_received stays the literal, unweighted
-- amount received; only the aggregate used for bracket math is weighted). 1x is the default and
-- requires no manager action. 2x is owner-confirmed real policy (2026-07-22, workbook column O).
--
-- 3x is intentionally allowed by the CHECK constraint so a sales manager CAN set it through the
-- existing manager-review flow, but its real-world meaning has NOT been confirmed by the owner --
-- see the comment on CommissionCalculator and handoff 102 for the flag. Do not treat a 3x row as
-- verified business policy without owner sign-off.
--
-- Forward-only: never edit an already-applied migration in place (CLAUDE.md).
ALTER TABLE sales.commission_record
    ADD COLUMN weight_multiplier SMALLINT NOT NULL DEFAULT 1
        CHECK (weight_multiplier IN (1, 2, 3));
