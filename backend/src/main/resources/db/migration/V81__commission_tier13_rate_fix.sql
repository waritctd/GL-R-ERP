-- Commission redesign Slice A1 — REAL-PAYROLL DATA FIX, deliberately kept separate from V80
-- (which is schema-only) so this one can be reviewed and deployed independently.
--
-- sales.tier_config tier 13 (the "high roller" tier, monthly commissionable base above
-- 3,000,000 THB) was seeded in V11.2 at 7.5000%. The real commission policy Excel puts this rate
-- at 3.2500%. Owner-confirmed 2026-07-22 that 3.25% is correct and the live DB's 7.5% was a
-- transcription error, not an intentional business rule.
--
-- Forward-only, no backfill: as of authoring, the live DB has only 2 test commission rows and
-- neither has a monthly commissionable base above 3,000,000, so there is nothing real to
-- recompute. is_high_roller stays TRUE — only the rate changes.
UPDATE sales.tier_config
   SET rate_percent = 3.2500
 WHERE tier_number = 13;
