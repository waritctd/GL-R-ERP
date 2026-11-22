# 0001. Rationale: load the import sheet and share IR progress

Decision record for [index.md](index.md). A build does not need this file.

## Context

The import team keeps the status of every IR in one Excel sheet. The portal got shipment rounds and lots in release 2.4.0, but nobody has moved the sheet's content across, so the portal shows most shipments at their first step while the sheet shows them at sea, at customs or in the warehouse. The owner's first priority is to get the current import status into the portal now.

Three forces shape the work:

- **The portal already has the right model.** `sales.shipment_round` has a source value `BACKFILL` whose comment reads "loaded from the import sheet", and it is exempt from the proforma and invoice number rule. Since V239 an IR may exist with no job, for sample, showroom and stock orders. What is missing is the loader, not the tables.
- **The repo is public.** The sheet holds customer names, factory invoice numbers and cost prices. None of it may enter a migration, a fixture or a test. The data has to arrive at runtime.
- **A plain sales rep reads only their own IRs today.** `ImportRequestService.requireRead` and `MyIncomingService` scope a rep to the jobs they own. The owner wants all sales to see all IR progress, which is a permission change and must be proven against the real service and a real database.

Not deciding leaves the portal's import pages showing stale status, keeps sales asking import for updates, and keeps the team double keeping a sheet the shipment round feature was meant to replace.

## What the sheet holds

Counts only. No row content is recorded here.

- Raw tab: 603 rows, 31 columns, 71 blocks separated by blank rows. 65 blocks hold IRs. 40 blocks hold one IR, 25 hold two or more.
- 135 IR entries, 125 distinct IR numbers. 9 numbers appear in more than one entry.
- Shipment columns (vessel, ETD, ETA, arrival, release, GR) sit on the first IR row of a block in all but 3 blocks, and those 3 only add a second GR number, an ETA or a note.
- Derived round steps: 27 received, 7 awaiting customs, 5 in transit, 26 ordered. By IR entry: 43, 18, 27 and 47.
- 479 item lines. 470 carry a clear "= quantity unit" ending. 3 more carry the quantity without the equals sign. 6 have no quantity.
- About 30 cells in date columns hold text instead of a date. Some are a date plus a port name, some are notes.
- Only 1 block has no brand.
- A workbook tab made earlier ("Clean Data" plus three reconciliation tabs) flattens the sheet to one row per IR and compares it with portal jobs: 43 entries match a job (38 by IR number, 5 by project name only), 57 are sample, showroom or monthly stock orders, 35 are customer jobs with no job in the portal. For about 8 jobs the sheet shows goods received while the portal shows IR issued.
- That flattened tab loses the block grouping. Read alone, it shows 56 entries at "proforma received" because their own rows have no shipping dates. Read by block, most of those have shipped.

## Options considered

### Option 1: An upload with a preview that fills the existing shipment rounds

An import only screen takes the team's raw sheet, parses blocks into rounds, shows a preview, and on confirm writes rounds, lots, documents and missing IRs with source `BACKFILL`. Later uploads add and fill blanks only.

**Pros**:
- Reuses the round, lot and no job IR model as designed. No new tracker table.
- The preview lets a person catch a misread before anything is saved.
- Works on production without real data ever entering the repo.
- Can be run again while the team winds the sheet down.

**Cons**:
- The parser for a hand kept sheet with merged cells and text dates is real work and is tied to that layout.
- Adopting existing IRs means moving lots between rounds, the part most likely to go wrong.

### Option 2: A one time script run on the server

The deployer session runs a script once against production, reading the file from outside the repo.

**Pros**:
- Least code. No screen, no endpoints, no mock mirror.
- Nothing to maintain after the load.

**Cons**:
- No preview for the import team, who are the only people able to judge whether a block was read correctly.
- Any catch up needs the deployer and another script run.
- The script still needs every mapping rule, so the saving is the screen only.
- A script that writes to production outside the service skips the service's locks and step derivation unless it calls them, at which point it is Option 1 without a preview.

### Option 3: Excel stays the master, the portal mirrors it

Import keeps working in Excel and uploads whenever it changes. Each upload overwrites the portal.

**Pros**:
- No change of habit for the import team.
- Conflict handling is trivial: the sheet always wins.

**Cons**:
- Throws away the shipment round screens that shipped in 2.4.0, since any edit there is overwritten.
- The portal is only as fresh as the last upload, which is the problem being solved.
- Lot level facts the portal can hold (partial receipts, split lots) have no place in the sheet and would be lost on every upload.

### Option 4: A separate read only table for the sheet

Store sheet rows as they are in a new table and show that table to sales.

**Pros**:
- Simplest parser: no matching against jobs, no lots, no steps.
- Zero risk to existing rounds and jobs.

**Cons**:
- Two sources of import status in one portal, which disagree for every job that has an IR.
- Job pages, the import worklist and the rep's own list would not reflect the load.
- Ignores the `BACKFILL` slot the round model reserved for exactly this.

## Rationale

**Owner ruling, 2026-11-23, after the first version of this spec was accepted:** "there's no need to let import import excel, i want to backfill all the status i got from them, and also let them be able to adjust and create a งานนำเข้า by themselves too". The first version chose Option 1. This version chooses Option 2, with two changes that answer the cons listed under it: the run calls the real services and locks, and its dry run report stands in for the preview. Import's ongoing work moves to create and edit screens in the portal, so nobody needs a sheet after the first run.

The model exists and names this load as its third source, so writing into it costs a loader rather than a design. The public repo rules out data in code, so the file arrives on the server at run time and stays out of the repo. The owner chose the portal as master, which removes Option 3 and fixes the conflict rule: fill blanks, forward only, list differences.

The raw tab is the input, not the flattened tab, because the block is the shipment. Loading from the flattened tab would show about 40 shipped IRs as not shipped. The flattened tab stays useful as a cross check while building: the parser's IR list should match its 135 entries.

Recommendations made on the owner's behalf, each with its runner up:

- **One block is one round.** Runner up: keep one round per IR and copy the block's facts onto each. Rejected because it copies one vessel's dates into many rows that then drift apart.
- **Adopt an untouched IR by moving its lots, fill a worked on round in place, skip anything import has regrouped.** "Untouched" means the round is still at its first step and holds no documents or typed facts, the same test the round service uses before it lets a round be deleted. Runner up: never touch existing rounds and only create rounds for new IRs. Rejected because the 38 IRs that match by number are the ones tied to jobs, which sales care about most.
- **A match needs the same factory, and the same rep when the code is mapped.** Runner up: match on the number alone. Rejected because sheet numbers are typed by hand in the same range the portal mints from, so a typo could move another job forward.
- **A stored block key (`backfill_key`) finds a block again.** Runner up: find the round through the lots of the block's IRs. Rejected because it fails for an IR shipped in two lots, an IR that changes block, and a block where everything was skipped.
- **Raise the IR counter past every loaded number.** There is no real runner up: without it the portal later mints a number the load inserted, the unique index refuses it, the counter does not advance, and issuing stays broken for that year.
- **Keep the two new date columns and put them on the round page.** Runner up: write both dates into the round note and add no column. Rejected because the portal is the master, so import must be able to read and correct them as fields.
- **Sheet remarks go to the round note.** Runner up: a new field only import and the CEO can read. Rejected as more surface for little gain: the remarks are operational, and the preview shows them before they are saved. The cost is that the sales manager and read all holders can read them.
- **A stale plan is refused.** The commit recomputes the plan and compares its hash with the dry run the owner reviewed. Runner up: commit whatever the fresh plan says. Rejected because the owner approved something else.
- **A matched IR that the sheet lists in two blocks is skipped.** Runner up: split its lots by matching sheet lines to job items. Rejected because sheet lines are free text and a wrong split is worse than a short manual list (about 4 IRs).
- **Received when a GR number or a release date is present.** Runner up: GR number only. Rejected because it would show released goods as waiting for customs. The cost is 4 rounds marked received from a release date alone, flagged in the preview.
- **A line with no quantity loads as quantity 1 with a note.** Runner up: exclude the block. Rejected because 6 odd lines in one sample order should not hide a shipment.
- **Clash of sales codes is the test for a mistyped IR number.** Runner up: compare project text. Rejected because real repeat entries differ in project text ("Lot 1", "Lot 2") while the one known typo differs in sales code.
- **Rep codes live in the run's choices file.** The first version had a mapping table, which only made sense for repeated uploads. Runner up: a sales code column on `hr.employee`. Rejected for now because it changes the employee table for a one time load. It is the better home if codes are needed elsewhere.
- **The backfill is an `ApplicationRunner` in a one off container.** Runner up: a CEO only endpoint. Rejected because any HTTP surface is something that can be called twice or with the wrong file, and the owner asked for no upload.
- **An import job is the existing no job IR, with a new source value `IMPORT`.** Runner up: a separate import job table. Rejected because rounds, lots, the waiting list and the incoming lists already work on no job IRs.
- **History uses the append only `hr.audit_log`.** Runner up: a history table for import jobs. Rejected because the existing table already stores who, when, before and after, and refuses updates and deletes.
- **A quantity on a received round is locked, and a split line cannot drop below its placed sum.** Runner up: let import change anything and adjust lots silently. Rejected because a received quantity feeds delivery limits and job rollups.
- **A job entered after the fact may join a round that has already left.** Runner up: keep the round page's rule. Rejected because import often records goods that are already at sea, which is the case this feature exists for.
- **Sales read an import job through the company list only.** Runner up: open the job page to all sales. Rejected because the list already answers the status question with a narrower set of fields.
- **A new endpoint for the company wide list.** Runner up: a `scope` parameter on `/api/my-incoming`. Rejected because a separate endpoint has one gate to test and leaves the existing one untouched.
- **New purpose is named `CUSTOMER`.** Runner up: `PROJECT`, as first described to the owner. Rejected because the incoming list already uses `PROJECT` for a row that has a job.
- **Per block transactions.** Runner up: one transaction for the file. Rejected because one bad block should not block the first priority load.
- **No new library.** Apache POI is already used by the price import and the receipts import.

## Cross check

An independent read on another model (2026-11-23) found no simpler design and returned 7 behaviour gaps and 17 build details. The owner chose to apply every recommended fix, and the ones about the load carry into this revision. The create and edit rules for import jobs were added after that check and have not had an independent read. The two that mattered most: a hand typed number could have adopted the wrong job's IR, and loaded numbers would later have collided with the portal's own IR counter.
