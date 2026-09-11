-- Quotation v3b (owner, 2026-09-11 overnight) — the ENGLISH quotation, form F-SM-008.
--
-- MIGRATION NUMBERING: the v3 branch this one is stacked on tops out at V168, origin/develop at
-- V167 and origin/main at V166, so V169 is the next free number ABOVE every one of them (checked,
-- not assumed — `git ls-tree` over every remote branch's migration directory). A LOWER version
-- merged after a higher one is SILENTLY SKIPPED on the prod deploy (validate-on-migrate is false
-- there — see CLAUDE.md), so never renumber this downward, and never renumber it at all once it
-- is pushed.
--
-- THIS IS A DELIBERATE SCHEMA + CONTRACT CHANGE, not a side effect of UI work — authorised under
-- CLAUDE.md's "Sales flow redesign — business logic IS changing" relaxation, which is scoped to
-- the sales pricing/deal workflow. Payroll/tax/SSO/commission math is untouched by this file.
--
-- ⚠️ THE BLOCKER, restated here because this is where a future reader will look: there is NO
-- F-SM-008 template FILE in this repo. backend/src/main/resources/templates/ holds exactly one
-- quotation template, quotation_template.xls, which is the Thai F-SM-002 (03), and the renderer
-- works by FILLING that workbook. The English document is therefore produced by driving the SAME
-- workbook with English labels, an English footer and no VAT row — modelled on the owner's PDF
-- samples (QN6900902-6 and QN6900933), NOT rendered from the real form. If she supplies a genuine
-- F-SM-008.xls, swapping it in is a small change confined to QuotationRenderer's TEMPLATE
-- constant and its row/column map.
--
-- ── sales.quotation.document_language ─────────────────────────────────────────────────────────
-- 'TH' (the Thai F-SM-002, today's ONLY behaviour) or 'EN' (the English F-SM-008). NULL reads as
-- 'TH' in th.co.glr.hr.dealquotation.DealQuotationService, so every pre-V169 row keeps today's
-- behaviour with no rewrite — the same "NULL means the legacy default" device V168 used for
-- price_mode and line_type. The backfill below is hygiene for rows this feature owns.
ALTER TABLE sales.quotation ADD COLUMN document_language VARCHAR(2);

-- A CHECK, not an enum type: this table's own convention (chk_quotation_doc_status V52/V74/V165,
-- chk_quotation_price_mode V168), and it stays widenable by a later DROP + re-ADD if a third
-- language ever appears. NULL passes a CHECK in Postgres, which is what keeps every legacy row —
-- and every Step-4 / pricing-chain row, which never sets this — legal.
ALTER TABLE sales.quotation ADD CONSTRAINT chk_quotation_document_language
    CHECK (document_language IN ('TH', 'EN'));

-- ── sales.quotation.currency ──────────────────────────────────────────────────────────────────
-- The column ALREADY EXISTS (V6: VARCHAR(10) NOT NULL DEFAULT 'THB') and was written as a
-- hardcoded 'THB' literal by DealQuotationRepository#insertDraft until now. It becomes a real
-- parameter here: 'THB' for a TH document, 'USD' for an EN one, defaulted FROM the language so a
-- rep picks one thing (ภาษาเอกสาร) and the currency follows.
--
-- No CHECK is added on currency ON PURPOSE. This column is shared with the pricing-chain /
-- Step-4 quotation rows (origin <> 'DEAL_DIRECT') and with V25/V26's factory + FX vocabulary,
-- which carries EUR and others; a CHECK ('THB','USD') here would be a constraint on rows this
-- feature does not own and has never inspected. The THB|USD restriction is enforced where it
-- belongs — on the deal-quotation WRITE path (UpsertDealQuotationRequest's @Pattern plus
-- DealQuotationService#resolveCurrency).
--
-- ⚠️ ASSUMPTION, flagged in the PR body: VAT follows the LANGUAGE. A TH document carries the 7%
-- ภาษีมูลค่าเพิ่ม row; an EN document prints Grand Total (USD) and no VAT row at all. There is no
-- separate VAT switch because no sample the owner supplied shows an English document WITH VAT or
-- a Thai one WITHOUT. If she ever needs the fourth combination, that is a new column, not a
-- reinterpretation of this one.

-- ── Backfill: this feature's own rows only ────────────────────────────────────────────────────
-- Scoped to origin = 'DEAL_DIRECT' (V165's tag) so it can never touch a legacy ticket-item row or
-- a Step-4 / pricing-chain row. The IS NULL guard makes it inert on replay.
UPDATE sales.quotation
   SET document_language = 'TH'
 WHERE origin = 'DEAL_DIRECT'
   AND document_language IS NULL;
