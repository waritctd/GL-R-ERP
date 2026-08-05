-- Progressive-disclosure evidence tagging for the ล.ย.01 tax-allowance declaration
-- (feat/tax-allowance-sections): the declaration form is being restructured from "all five groups
-- expanded at once" into "choose a section, then fill only that section" (TaxAllowanceForm.jsx,
-- modelled on LeaveRequestPage.jsx's step flow). Evidence attachments now need to say WHICH of the
-- five sections (family/insurance/savings/housing/donation -- TAX_ALLOWANCE_GROUPS in
-- frontend/src/features/taxAllowance/taxAllowanceSchema.js) they belong to, so the per-section
-- evidence panel can show only that section's own files instead of one flat list for the whole
-- declaration.
--
-- Lands on hr.file_attachment itself (V33), not a new table: TaxAllowanceDeclarationRepository's
-- attachment methods (saveAttachment/findAttachment/findAttachments) already do plain CRUD against
-- this shared table with domain = 'tax_allowance_declaration' -- there is no separate
-- tax-allowance-specific attachment table to add the column to instead (traced from
-- TaxAllowanceDeclarationService#uploadAttachment backward through the repository before writing
-- this migration, per the task's own instruction not to guess).
--
-- NULLABLE, no backfill, no CHECK constraint here:
--   * Nullable so every row uploaded before this migration (and every row in every OTHER domain
--     this table serves -- leave, commission-invoice evidence, factory_quote) is unaffected. The
--     frontend treats NULL as "general/uncategorized" evidence, shown under a dedicated bucket
--     rather than dropped (see TaxAllowanceEvidencePanel.jsx).
--   * No backfill: there is no reliable way to guess which section a pre-existing attachment
--     belonged to, and guessing wrong would be worse than leaving it uncategorized.
--   * No CHECK (section_key IN (...)) at the database level: the five valid keys are a
--     TAX_ALLOWANCE-only concept (mirrored in TaxAllowanceDeclarationService's
--     EVIDENCE_SECTION_KEYS, validated server-side on upload), and this column is shared across
--     every domain hr.file_attachment serves -- a CHECK enumerating today's five ล.ย.01 section
--     keys would incorrectly constrain rows from every other domain too, none of which have
--     sections of their own.
ALTER TABLE hr.file_attachment
    ADD COLUMN section_key VARCHAR(40);

COMMENT ON COLUMN hr.file_attachment.section_key IS
    'V135: optional caller-supplied tag naming which section of a multi-section form this evidence belongs to -- today only the ล.ย.01 tax-allowance declaration''s five TAX_ALLOWANCE_GROUPS keys (family/insurance/savings/housing/donation), validated server-side in TaxAllowanceDeclarationService#uploadAttachment. NULL for every pre-migration row and for every other domain this table serves (leave, commission-invoice evidence, factory_quote), never backfilled -- the frontend shows a NULL row as general/uncategorized rather than dropping it.';
