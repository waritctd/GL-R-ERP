-- V144: give sales.ticket.entry_channel an honest "not stated" value.
--
-- WHY. V51 added the column as NOT NULL DEFAULT 'DESIGNER_LED', and TicketRepository.create
-- substituted the same literal whenever the request omitted the field. Three layers therefore
-- asserted "designer-led" without anyone ever saying so, and a deliberate DESIGNER_LED row is
-- indistinguishable from a row nobody touched. That makes the column useless for reporting, and
-- it means the owner's Case C requirement (record that a deal was a direct contractor enquiry)
-- was not actually guaranteed by anything. 'UNSPECIFIED' says "not stated" instead.
--
-- ⚠️ DATA CUTOFF — READ BEFORE REPORTING ON THIS COLUMN.
-- This migration deliberately performs NO backfill. Every row created before V144 reads
-- 'DESIGNER_LED'; some of those were chosen by a human and most were the default, and nothing in
-- the schema or the event log distinguishes the two. Rewriting them to 'UNSPECIFIED' would destroy
-- the deliberate ones; leaving them as-is asserts a fact for the rest. Neither is recoverable, so
-- the data is left exactly as it stands and the ambiguity is documented instead:
--
--     entry_channel is only trustworthy on tickets created AFTER V144 was applied.
--     Exclude earlier tickets from any channel-based reporting — filter on
--     sales.ticket.created_at against this migration's flyway_schema_history.installed_on.
--
-- SEMANTICS. 'UNSPECIFIED' is valid as STORED but rejected as INPUT to
-- POST /api/tickets/{id}/entry-channel: once a channel is stated it cannot be un-stated. This is
-- the same valid-as-stored/invalid-as-input shape as quotation_recipient's 'UNSPECIFIED'
-- (th.co.glr.hr.ticket.QuotationRecipient), enforced in TicketService.setEntryChannel.
-- The Java side is th.co.glr.hr.ticket.EntryChannel.
--
-- NOT a behavioural flag: nothing reads entry_channel to decide anything (DealStage in particular
-- does not consult it). It stays purely descriptive by owner ruling.

-- Full re-declaration to widen the vocabulary, following the V51/V83 pattern for this table.
ALTER TABLE sales.ticket DROP CONSTRAINT IF EXISTS chk_ticket_entry_channel;
ALTER TABLE sales.ticket ADD CONSTRAINT chk_ticket_entry_channel CHECK (entry_channel IN (
    'DESIGNER_LED','OWNER_DIRECT','BUYER_DIRECT','UNSPECIFIED'));

-- The column default backs up the application-level default in TicketRepository.create, so a row
-- inserted by any path that does not name the column lands 'UNSPECIFIED' rather than asserting a
-- route. NOT NULL is unchanged — "not stated" is a value here, not a null.
ALTER TABLE sales.ticket ALTER COLUMN entry_channel SET DEFAULT 'UNSPECIFIED';

COMMENT ON COLUMN sales.ticket.entry_channel IS
    'How the deal arrived: DESIGNER_LED | OWNER_DIRECT | BUYER_DIRECT | UNSPECIFIED (not stated, the default since V144). Descriptive only — no workflow reads it. Rows created before V144 all read DESIGNER_LED regardless of intent and were deliberately not backfilled; exclude them from channel reporting.';
