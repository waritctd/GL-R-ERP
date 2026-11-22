# 0001. Backfill the import sheet, let import run their own import jobs, and show IR progress to all sales

**Date**: 2026-11-23 (revised the same day after the owner changed direction)
**Status**: In Progress

## Summary

The import team tracks every IR (import request) in an Excel sheet, so the portal is behind the real world. This spec loads that sheet into the portal once, as a backfill run done for the owner, with no upload screen. From then on import works in the portal: they can create a งานนำเข้า (an import job, meaning an IR with no sales job) by themselves and edit any of them. Every sales rep also gets a company wide, read only view of IR progress with no prices and no factory documents.

## Requirements

**User stories**:
- As the owner, I want the status the import team gave me loaded into the portal in one go, so that the portal is true from day one without anyone typing it in again.
- As an import officer, I want to create an import job myself when goods are ordered and no sales job exists in the portal, so that nothing is tracked outside the portal.
- As an import officer, I want to correct any import job, including one that came from the sheet, so that a typo in the sheet does not stay wrong forever.
- As a sales rep, I want to see the progress of every IR in the company, so that I can answer a customer or a colleague without asking import.
- As the owner, I want sales to see progress but never cost or factory documents.

**Acceptance criteria** (the contract, each criterion is IDed and independently checkable):

One time backfill of the sheet
- **AC-1**: There is no screen and no HTTP endpoint that loads a sheet. The backfill is a run started on the server by the deployer, with the sheet file kept outside the repo.
- **AC-2**: A dry run writes nothing to the database. It produces a report that lists every shipment block (a run of rows between blank rows) as one shipment round, with its IRs, item lines, derived step, dates, the note text that would be stored, every warning, every skipped IR and excluded block with its reason, and every job that would move forward.
- **AC-3**: The run reads the team's raw tab: the first sheet with `IR NO.` in any cell of row 1. It finds columns by header text, not by position. A sheet with a required header missing stops the run with the list of missing headers and writes nothing.
- **AC-4**: A commit run creates one shipment round with source `BACKFILL` per block that has at least one IR to place, at the step and with the dates derived by the rules in *Sheet to portal mapping*.
- **AC-5**: An IR number that matches an issued portal IR of the same factory reuses that IR. No second IR is created. If the IR's own round is untouched, its lots move onto the block's round and the emptied round is deleted. If its own round has been worked on in the portal, that round stays, and only its blank fields are filled.
- **AC-6**: An IR number with no match is created as an issued IR with source `BACKFILL` and no job, with one item line per sheet line, and its lots placed on the block's round. Its purpose is `SAMPLE`, `SHOWROOM`, `STOCK` or `CUSTOMER`, suggested by keyword and overridable in the choices file. A new IR listed in two blocks is one IR with lots on both rounds.
- **AC-7**: When the run leaves every IR of a job at received, the job moves forward exactly as if import had advanced it: goods received, delivery scheduling, and payment status from deposit paid to awaiting final payment. The usual job events are written. No bell, no email and no other notification is created by the run.
- **AC-8**: Running a commit twice on the same file changes nothing the second time: no new round, IR, item, lot or document, and no step change.
- **AC-9**: A later run on a newer file adds new blocks and new IRs, fills only fields that are blank in the portal, and moves a round's step only forward. It never overwrites a value that is already set, never moves a step back, and never changes the items or lots of an IR that already exists. Each place where the sheet and the portal differ is listed in the report.
- **AC-10**: A matching portal IR is left untouched and listed with its reason when: it is not issued (draft or superseded); its factory differs from the block's; its job belongs to a different rep than the sheet row names; any of its lots sits on a `PORTAL` round; its lots sit on more than one round or share a round with another IR; or it is a job IR that the sheet lists in more than one block. The rest of the block still loads.
- **AC-11**: A problem excludes only its own block, never the whole file. Blocking problems are: an IR number that does not fit the pattern; the same IR number in two blocks under different sales codes (both blocks are excluded); IR rows of one block naming different brands; and a block whose factory is not resolved.
- **AC-12**: The five cost columns are never read, never stored and never written to the report.
- **AC-13**: Each commit run writes one run log row with who, when, the file name and hash, the counts, and the ids of everything it created, moved or advanced. The row exists even if the run stops half way.
- **AC-14**: A commit run refuses to write when the plan it computes differs from the dry run the owner reviewed.
- **AC-15**: After a run, and after any import job is created with a typed number, the portal's own IR numbering never produces a number that is already used. Issuing a new IR and creating a no job order both still work.

Import jobs that import runs themselves
- **AC-16**: An import or CEO user creates an import job from การขนส่ง: type (`CUSTOMER`, `SAMPLE`, `SHOWROOM`, `STOCK`), project or customer text, factory, date the IR was received, and at least one item line are required. Rep, deposit date and the date the customer wants the goods are optional. The result is an issued IR with no sales job.
- **AC-17**: On create, the IR number is either the next number the portal mints, or a number the user types. A typed number that is already used, or that does not fit the pattern, is refused with a clear message.
- **AC-18**: On create, the user chooses where the goods ride: not planned yet (the IR waits in รอจัดรอบขนส่ง), an existing round of the same factory that is not yet received, or a new round. The IR's step then follows its round.
- **AC-19**: An import or CEO user edits any import job, including one that came from the sheet: type, project or customer text, rep, the three dates, and the IR number. The factory can change only while the job has no lot on any round.
- **AC-20**: An import or CEO user adds, edits and removes item lines of an import job. A line whose goods are on a received round can have its text changed but not its quantity, and cannot be removed. A quantity change keeps lots consistent by the rules in *Editing items*.
- **AC-21**: Every create and every edit of an import job is kept in a history that shows who changed what and when, with the value before and after.
- **AC-22**: These create and edit actions work only on an IR with no sales job. On an IR that belongs to a sales job they are refused, and that IR is changed through its job as today.
- **AC-23**: Only roles `import` and `ceo` can create or edit an import job. Every other role is refused, including the rep named on the job and a holder of the read all grant. A sales rep cannot withdraw an import job that import created or that came from the sheet.
- **AC-24**: On การขนส่ง, each waiting IR and each round shows where it came from: a sales job, an order a rep placed, a job import created, or the sheet. After a backfill run the page shows one line saying when the sheet was loaded and how many items need a check, with the list of those items.
- **AC-25**: Transshipment ETA and insurance date are shown and editable on the shipment round page for import and the CEO, like the other shipment facts.

Sharing progress with sales
- **AC-26**: A sales rep opens ของที่ฉันสั่ง and finds a two way switch: ของฉัน (the default, unchanged) and ทั้งบริษัท. ทั้งบริษัท lists incoming lines for every IR in the company: job IRs of every rep and no job IRs of every type.
- **AC-27**: Each row in ทั้งบริษัท shows the IR number, the project and customer text, the rep name, the factory, the product, quantity and unit, the current step, and the shipping dates and vessel the rep's own rows already show.
- **AC-28**: ทั้งบริษัท never returns proforma numbers, invoice numbers, shipment documents, round notes, prices or costs. A row the caller cannot open carries no link to the job page and no withdraw action.
- **AC-29**: Roles `sales`, `sales_manager` and `ceo`, and holders of the read all grant, can read ทั้งบริษัท. Roles `hr`, `warehouse`, `qc` and `employee` without that grant get 403. Roles `account` and `import` get 403 with or without it.
- **AC-30**: Nothing else widens. A sales rep who asks for another rep's IR, job page, import job page or shipment round by id still gets 403.
- **AC-31**: ทั้งบริษัท keeps the existing 30 day rule (goods received more than 30 days ago drop off). It has a type filter that includes งานลูกค้า, a search by IR number, project text, product or rep, and a step filter, all done in the browser on the loaded list.

## Decision

**Chosen option**: Option 2, extended: a one time backfill run on the server, plus create and edit of import jobs in the portal

Load the sheet once through a backfill runner that the deployer starts on the server (dry run, owner review, then commit), give import and the CEO endpoints and screens to create and edit IRs that have no sales job, and add one read only endpoint and a switch on ของที่ฉันสั่ง for the company wide view.

**Implementation skills**: `frontend-design` (`.agents/skills/frontend-design/`)

**Design source**: the owner's Claude Design canvas "GL&R Portal Prototype v 3", artboards `ID1-ImportJobs`, `ID2-NewImportJob`, `ID3-ImportJobDetail`, `ID4-CompanyOrders` (Desktop page) and `IM1` to `IM4` of the same names (Mobile page). The owner approved them on 2026-11-23. Build to those artboards.

## Rationale

Reasoning and options: see [rationale.md](rationale.md).

## Feature design

### Data model sketch

One forward only migration. Take the next free number after the highest on `origin/develop` at build time (V242 when this was written, because the branch `claude/quotation-sample-mark` already holds V241. V241 must deploy before or with this migration, or production skips it without an error).

| Table | Change |
|---|---|
| `sales.import_request` | Add `source VARCHAR(10) NOT NULL DEFAULT 'PORTAL'`, check `IN ('PORTAL','IMPORT','BACKFILL')`. `IMPORT` means import created it in the portal. |
| `sales.import_request` | Widen `chk_import_request_order_purpose` to allow `'CUSTOMER'` (a customer job that has an IR but no sales job in the portal). |
| `sales.import_request` | Replace `chk_import_request_project_or_order` with: `ticket_id IS NOT NULL OR (order_purpose IS NOT NULL AND purpose_note IS NOT NULL AND (source IN ('IMPORT','BACKFILL') OR (requester_id IS NOT NULL AND wanted_by IS NOT NULL)))`. |
| `sales.shipment_round` | Add `transship_eta_on DATE` and `insured_on DATE`, both nullable. |
| `sales.shipment_round` | Add `backfill_key VARCHAR(40)`, nullable, with unique index `ux_shipment_round_backfill_key` where it is not null. |
| `sales.import_sheet_backfill_run` (new) | `run_id` identity PK, `actor_id BIGINT NOT NULL` FK `hr.employee`, `actor_name VARCHAR(200) NOT NULL`, `started_at TIMESTAMPTZ NOT NULL DEFAULT now()`, `finished_at TIMESTAMPTZ`, `file_name VARCHAR(255) NOT NULL`, `file_size BIGINT NOT NULL`, `file_sha256 CHAR(64) NOT NULL`, `rounds_created INT NOT NULL DEFAULT 0`, `rounds_updated INT NOT NULL DEFAULT 0`, `irs_created INT NOT NULL DEFAULT 0`, `irs_adopted INT NOT NULL DEFAULT 0`, `irs_skipped INT NOT NULL DEFAULT 0`, `blocks_excluded INT NOT NULL DEFAULT 0`, `deals_advanced INT NOT NULL DEFAULT 0`, `summary JSONB NOT NULL DEFAULT '{}'`. |

Notes on the model:
- The type is named `CUSTOMER`, not `PROJECT`, because the incoming list already uses the word `PROJECT` for a row that has a job (`MyIncomingDtos.MyIncomingRow.kind`).
- `backfill_key` is how a later run finds the same block again. Its value is the first IR document number of the block, then `#`, then a count: 1 for the first block in the file that starts with that number, 2 for the second, and so on.
- `summary` holds `createdRoundIds`, `createdIrIds`, `adopted` (per IR: the IR id, its lots as they were, and a full snapshot of the deleted round's row), `filledInPlace` (round id and the fields filled), `dealsAdvanced` (per job: id, and stage, fulfilment status and payment status before), `sequenceBumps`, and `skipped` and `excluded` with reasons and sheet rows. It is the audit trail, the input to a targeted undo, and the source of the "needs a check" list on การขนส่ง.
- History of an import job uses the existing append only `hr.audit_log` (entity `import_request`, entity id the IR id, `before_json` and `after_json`). No new table.
- There is no table for sales codes. The codes in the sheet's `Sale` column are resolved by the choices file of the run.
- Relationships do not change: a round has many lots, a lot is a quantity of one IR item on one round, an IR belongs to a job or to none.
- The two new date columns join the existing shipment facts everywhere those travel: `ShipmentFacts`, `ShipmentRoundRepository.applyFacts` and `hasOwnData`, the round DTO, and the facts form on the round page (AC-25).

### The backfill run

- A class `ImportSheetBackfillRunner` (an `ApplicationRunner`, the pattern `DepositNoticeBackfill` already uses) is active only when the property `app.import-sheet.backfill.file` is set. It runs once and exits. The deployer starts it as a one off container of the backend image with `--server.port=0 --server.address=127.0.0.1`, so its web server is reachable by nothing else (`web-application-type=none` does not boot: `SecurityConfig` needs `HttpSecurity`).
- Properties: `app.import-sheet.backfill.file` (path of the `.xlsx` on the server), `.choices-file` (path of a JSON file, optional), `.mode` (`DRY_RUN` by default, or `COMMIT`), `.actor-employee-id` (required: the employee the rows and events are written as), `.report-dir` (where the report goes), `.expected-plan-sha256` (required for `COMMIT`).
- The **choices file** holds `factoryChoices` (brand text, or `block:<first IR number>`, to `{ "factoryId": n }` or `{ "new": true, "countryCode": "..", "countryOther": ".." }`), `repChoices` (sales code to an employee id, or `null` for "not a person"), and `purposeChoices` (IR number to a purpose). A dry run lists every brand and code it could not resolve, so the file can be filled in before the commit.
- The **report** is written as `report.json` and a readable `report.md` in the report directory. It carries the plan (see `blocks` below), the totals, and `planSha256`, a hash of the plan. It holds real customer names, so it stays on the server and is never committed.
- `blocks[]` entries carry: `firstRow`, `backfillKey`, `factoryText`, `factoryId`, `transportMode`, `step`, the mapped dates and facts, `note`, `warnings[]`, `action` (`CREATE`, `UPDATE` or `NONE`), and `irs[]` with `docNumber`, `action` (`CREATE`, `ADOPT`, `FILL_IN_PLACE`, `ON_ROUND`, `SKIP`), `reason`, `projectText`, `saleText`, `purpose`, `jobCode` (when it has a job), `lines[]` (`code`, `qty`, `unit`, `warning`). No entry has a cost field.
- A `COMMIT` computes the plan again under its locks. If its `planSha256` differs from `expected-plan-sha256`, it writes nothing, writes a fresh report, and exits with a failure code (AC-14).
- Limits: the file must be `.xlsx`, at most 10 MB and 5,000 rows.

### Sheet to portal mapping

**Reading the sheet.** A **block** is a run of non blank rows. A row with a value in `IR NO.` starts an IR. A row with no IR number adds an item line to the IR above it, and any other text on that row goes to that item's `note`. A row whose only non blank cell is a month name in Thai or English, with or without numbers after it, is a banner and is skipped. A merged cell's value applies to every row it spans. Shipment facts are read from the first IR row of the block, and from a later row only where the first is blank. Rows with the same IR number inside one block merge into one IR.

| Sheet column (header text) | Goes to |
|---|---|
| `IR NO.` | `import_request.doc_number`, as `IR` plus the cell text. The text must match `^\d{5,6}(-\d{1,2})?$` after trimming and removing a leading `IR`. |
| `วันที่ได้รับ IR` | New IR only: `issue_date`, `first_issued_date` and `import_step_at`. Also `shipment_round.contacted_on`. Fallback when blank: the earliest date in the block, else the run day with a warning. |
| `วันที่ได้รับมัดจำ` | New IR only: `deposit_received_date`. |
| `วันที่สั่งของ` | `shipment_round.ordered_on`. |
| `ยี่ห้อ` | Factory of the round and of each new IR: the brand text through the existing `normalizeFactoryName` rule against `price_catalog.factories`, else `factoryChoices`. If IR rows of one block name different brands, the block is excluded (AC-11). |
| `Sale` | New IR only: `requester_id` and `requested_by_name`. For a job IR it is only used for the rep check in *Existing IRs*. |
| `PROJECT` | New IR only: `purpose_note` (cut to 255) and `customer_name` (cut to 200), the same pair the no job order form writes. An IR row with no project text takes the text of the block's first IR row, else `ตารางของเข้า แถว <first row>` with a warning. |
| `รายชื่อสินค้าและจำนวน` | New IR only: one `import_request_item` per line. See *Item lines*. |
| `Proforma invoice no` | One `shipment_round_document` of kind `PROFORMA` per number (split on line breaks). |
| `Invoice No.` | One `shipment_round_document` of kind `INVOICE` per number. |
| `วันที่ตกลงกันว่าของพร้อม` | `shipment_round.goods_ready_on`. |
| `ชื่อพาหนะ` | `shipment_round.vessel`. |
| `วันที่คาดว่าเรือลำแรกออก ETD` | `shipment_round.etd_planned_on`. |
| `วันที่เรือลำแรกออกจริง` | `shipment_round.etd_actual_on` and `in_transit_on`. |
| `ETA SINGAPORE/ เรือลำสอง` | `shipment_round.transship_eta_on`. |
| `ทำประกัน` | `shipment_round.insured_on`. |
| `วันที่คาดว่าจะถึง ETA bkk` | `shipment_round.eta_on`. A port name in the same cell goes to `port`. |
| `วันที่ของถึงจริง` | `shipment_round.arrived_on` and `awaiting_customs_on`. |
| `วันที่ออกของ` | `shipment_round.customs_released_on`. |
| `สินค้าเข้าตรงตามInvoice(เลขที่ใบตรวจรับ)` | The first text matching `GR-\d{4}-\d+` (any letter case) goes to `gr_number`, upper cased. The rest goes to `complete_note` (cut to 500). `complete_per_invoice` is `true` only when the text contains `ครบ` and none of `แตก`, `ตำหนิ`, `ขาด`, `ไม่ครบ`. Otherwise it stays null. |
| `กำหนดการที่ลูกค้าต้องการของ` | New IR only: `wanted_by` when it is a date, and `required_by_note` as the Thai formatted date or the raw text. |
| `หมายเหตุ` | `shipment_round.note`. |
| `จำนวนคน`, `จำนวนคนและชนิดรถที่มาจริง`, `ผลการทำงานของ TP` | Ignored. |
| `ราคาของ(USD/EUR)`, `exchrate`, `ราคาของ (บาท)`, `%`, `ประมาณค่าใช้จ่ายนำเข้า` | Never read (AC-12). |

**Dates.** A real Excel date is used as is. A text cell is searched for the first `d/m/y` token. A two digit year means 20yy. A four digit year above 2400 has 543 taken off. A date more than two years from the run day, or no date token at all, leaves the field blank, adds a warning, and appends the raw text to `shipment_round.note` as `<header>: <text>` (the whole note is cut to 1000). All dates are plain calendar dates in `Asia/Bangkok`.

**Transport mode.** `AIR` when the vessel or project text contains `fedex`, `dhl`, `air`, `แอร์`, or a flight number shape (two letters and three or four digits). Otherwise `SEA`.

**Step.** The round's step is the furthest rule that holds:

| Step | Holds when | Step date |
|---|---|---|
| `RECEIVED` | a GR number or a customs release date is present | `received_on` = release date, else arrival date, else the latest of ETA, actual departure, planned ETD, order date and IR received date, else the run day. Every fallback after arrival date adds a warning. |
| `AWAITING_CUSTOMS` | an actual arrival date is present | `awaiting_customs_on` = arrival date |
| `IN_TRANSIT` | an actual departure date is present | `in_transit_on` = departure date |
| `ORDERED` | an order date, a proforma number, a planned ETD or a vessel is present | `ordered_on` = order date, else the IR received date |
| `CONTACTED` | none of the above | `contacted_on` = IR received date |

`PICKED_UP` is never derived, because the sheet has no column for it. A round marked `RECEIVED` with no GR number carries the warning `ไม่มีเลขใบตรวจรับ`. `received_on` is never left blank on a `RECEIVED` round, because the 30 day rule reads it.

**Item lines.** A line is split at its last `=`. The left side is `code` (cut to 255). The right side is read as a number (commas allowed, at most two decimals) and a unit (cut to 30). A line with no `=` is tried again as "text, number, unit" at its end. A line with no readable quantity is stored with quantity 1, unit `รายการ`, and `note` = `ไม่มีจำนวนในตาราง`, and raises a warning. `seq` is the line order from 1, continuing across blocks for an IR listed in two blocks.

**Purpose of a new IR.** Suggested from the project text: `SHOWROOM` when it contains `showroom` or `โชว์รูม`; `SAMPLE` for `sample`, `ตัวอย่าง`, `present`, `mock`; `STOCK` for `stock`, `สต็อก`, `warehouse`, `คลัง`, or a month name followed by `order`; else `CUSTOMER`. `purposeChoices` overrides it.

**Rep of a new IR.** The `Sale` text is split on `/`, trimmed and upper cased. `requester_id` is the employee of the first code that `repChoices` maps to a person, or null. `requested_by_name` is the resolved names joined by ` / `, with any unresolved code kept as typed.

**A new IR row** (from the run, and from the create form). The insert follows `ImportRequestRepository.insertNoProjectOrder` so every existing check passes: `ticket_id` null, `factory_id`, `factory_name`, `brand` = the factory name, `version` 1, `status` `ISSUED`, `doc_number`, `issue_date`, `first_issued_date` = `issue_date`, `customer_name`, `project_name` = the Thai label of the purpose, `requested_by_name`, `required_by_note`, `deposit_received_date`, `created_by_*` and `issued_by_*` and `issued_by_email` = the actor, `issued_at` = now, `import_step` = `CONTACTED`, `import_step_at` = `issue_date`, `import_step_by_*` = the actor, `order_purpose`, `requester_id` (may be null), `purpose_note`, `wanted_by` (may be null), `source`. After its lots are placed, the IR's step is recomputed through the existing `ShipmentStepDerivation`, never set by hand.

**A new IR in two blocks.** The IR row is created in the first block. Each later block in the same run adds its own lines as new items and places them on its own round. This applies only to an IR created by the run that is in progress.

### Existing IRs (portal is master)

For an IR number in the sheet that matches a row in `sales.import_request`, the first rule that fits decides:

| # | State of the portal IR | What the run does |
|---|---|---|
| 1 | Not `ISSUED` (draft or superseded) | **Skip**. |
| 2 | Its `factory_id` differs from the block's factory | **Skip**, reason `เลขซ้ำกับใบขอซื้ออื่น (โรงงานไม่ตรง)`. |
| 3 | A job IR, every code in the row's `Sale` cell resolves to a person, and none of them is the job's `created_by` or `keyed_by` | **Skip**, reason `เลขซ้ำกับใบขอซื้ออื่น (เซลส์ไม่ตรง)`. If any code is unmapped or "not a person", this rule does not apply. |
| 4 | A job IR that the sheet lists in more than one block | **Skip**, reason "split it on การขนส่ง". |
| 5 | Source `BACKFILL` or `IMPORT` (an earlier run or import created it) | If it has lots on this block's round: nothing to do. If not: list it under differences as "on another round in the portal" and change nothing. Its items and lots are never touched (AC-9). |
| 6 | Any lot on a `PORTAL` round, lots on more than one round, or its round also carries another IR's lots | **Skip**, reason "managed on การขนส่ง". |
| 7 | No lots at all | **Adopt**: place every item whose wanted quantity (view `sales.import_item_wanted`) is above zero on the block's round. If none is above zero, skip. |
| 8 | All lots on one `MIGRATED` round of its own that is **untouched**: its step is `CONTACTED` and `ShipmentRoundRepository.hasOwnData` is false | **Adopt**: move its lots (same quantity and received quantity) to the block's round, clear the undo state on both rounds and on the IR as `finishLotChange` does, and delete the emptied round. |
| 9 | All lots on one `MIGRATED` round of its own that has been worked on | **Fill in place**: that round stays, keeps its source, and gets no `backfill_key`. Its blank facts are filled from the block, missing documents are added, and its step moves forward only. |

Further rules:
- A job that is not `ACTIVE` is still adopted. The existing `onActiveDeals` filter drops its events and its rollup, as it does for any step change.
- The block round's step is the sheet's derived step only. It is never raised to match an adopted IR.
- The run writes lots through the repository, so it may place lots on a `BACKFILL` round at any step.
- A block whose IRs all end as skip, fill in place, or "on another round" creates no round.
- A later run finds a block's round by `backfill_key`. On that round it fills blank facts, moves the step forward only, adds documents that are not there yet (same round, kind, and document number compared trimmed and upper cased), and adds IRs that are new to the portal.

### IR number counter

The portal mints `IR<yy><nnn>` from `sales.document_sequence` (`doc_type` `IMPORT_REQUEST`, one row per Buddhist year). For every IR number the run touches, and for every number a user types in the create or edit form, that year's `last_seq` is raised to at least the number's sequence part: the digits after the first two, ignoring any `-n` suffix, with `year_th` = 2500 plus the first two digits. It uses `INSERT ... ON CONFLICT DO UPDATE SET last_seq = GREATEST(...)` in the same transaction (AC-15).

### Import jobs in the portal

An **import job** is an issued IR with `ticket_id` null. Import and the CEO create and edit them. The screens call them งานนำเข้า.

**Create** (AC-16 to AC-18). Validation, each with its own Thai message:
- `purpose` is one of the four types. `purposeNote` is 1 to 200 characters.
- `factoryId` exists. `receivedOn` is a date not after today. `depositReceivedOn` and `wantedBy` are optional dates. `wantedBy` may be in the past, since a job can be entered after the fact.
- `requesterId`, when given, is an active employee.
- `lines` has at least one line. Each line: `code` 1 to 255 characters, `qty` above zero with at most two decimals, `unit` 1 to 30 characters, `size` up to 80.
- `docNumber` is null (the portal mints the next number, as `NoProjectOrderService.create` does today) or a typed number that matches `^IR\d{5,6}(-\d{1,2})?$` after trimming and upper casing. A used number answers 409 with `เลขที่ใบขอซื้อนี้ถูกใช้แล้ว`.
- `round.mode` is `NONE`, `EXISTING` (with `roundId`) or `NEW`.

The row is written as in *A new IR row*, with `source` = `IMPORT` and the session user as actor. Then:
- `NONE`: no lot. The IR waits in รอจัดรอบขนส่ง.
- `EXISTING`: every line is placed at full quantity on that round. The round must be of the same factory and not `RECEIVED`. A round that has already left the factory is allowed here, because the job may be entered after the goods shipped. This is the one place the "round has left" rule of the round page is relaxed, and it applies only to a job being created.
- `NEW`: a `PORTAL` round at `CONTACTED` is created through the existing create path with every line at full quantity.

If a rep was named, the rep gets one bell, no email: `ฝ่ายนำเข้าสร้างงานนำเข้า <IR number> ให้คุณ`. Nothing else is notified.

**Edit the job** (AC-19). Any of `purpose`, `purposeNote`, `requesterId`, `receivedOn`, `depositReceivedOn`, `wantedBy`, `docNumber`, `factoryId` may be sent. Rules:
- `receivedOn` writes `issue_date` and `first_issued_date`. It is refused when it is after the earliest step date of any round the job rides on, with the message naming that date.
- `docNumber` follows the same pattern and uniqueness rule as create, and raises the counter.
- `factoryId` is refused with 409 while the job has any lot.
- `customer_name`, `project_name`, `requested_by_name` and `required_by_note` are rewritten from the new values, as on create.
- Changing the rep sends no notification.

### Editing items

For AC-20. Each rule is checked under the same locks the round service takes (rounds, then IRs).

| Change | Rule |
|---|---|
| Edit text (`code`, `size`, `unit`) | Always allowed. |
| Edit `qty` | Refused when any lot of the line is on a `RECEIVED` round. If the line has one lot that held its full quantity, that lot takes the new quantity. If the line is split over several lots, the new quantity cannot be below the sum of its lots, and an increase stays unplaced (it shows in รอจัดรอบขนส่ง). If the line has no lot, only the line changes. |
| Add a line | Allowed. The new line has no lot and shows in รอจัดรอบขนส่ง. |
| Remove a line | Refused when any lot of the line is on a `RECEIVED` round, and when it is the job's last line. Otherwise its lots are removed with it. A round left with no lots is deleted when `hasOwnData` is false, else it stays. |

After any change the undo state of the touched rounds and of the IR is cleared, and the IR's step is recomputed through `ShipmentStepDerivation`. Removing a job as a whole stays on the existing withdraw path (`NoProjectOrderService.withdraw`), which works while the job has no lots.

### API surface

| Endpoint | Method | Key inputs | Key outputs | Auth | Key errors |
|---|---|---|---|---|---|
| `/api/import-jobs` | POST | `purpose`, `purposeNote`, `factoryId`, `receivedOn` (req); `requesterId`, `depositReceivedOn`, `wantedBy`, `docNumber` (opt); `lines[]` (req); `round` (req) | `ImportJobDto` | session, role `import` or `ceo` | 400 per field, 403, 409 (number used, round received or of another factory) |
| `/api/import-jobs/{id}` | GET | none | `ImportJobDto` | session, the existing read rule for a no job IR (`requireReadRow`) | 403, 404, 409 when the IR has a sales job |
| `/api/import-jobs/{id}` | PATCH | any of the header fields | `ImportJobDto` | session, role `import` or `ceo` | 400, 403, 404, 409 (has a sales job, number used, factory locked) |
| `/api/import-jobs/{id}/items` | POST | `code`, `size`, `qty`, `unit` | `ImportJobDto` | session, role `import` or `ceo` | 400, 403, 409 |
| `/api/import-jobs/{id}/items/{itemId}` | PATCH | any of `code`, `size`, `qty`, `unit` | `ImportJobDto` | session, role `import` or `ceo` | 400, 403, 409 (received, below placed quantity) |
| `/api/import-jobs/{id}/items/{itemId}` | DELETE | none | `ImportJobDto` | session, role `import` or `ceo` | 403, 409 (received, last line) |
| `/api/import-sheet/backfill-runs/latest` | GET | none | `finishedAt`, the counts, `needsCheck[]` (the run's skipped and excluded entries: sheet row, IR number or block, reason) or 204 when no run exists | session, role `import` or `ceo` | 403 |
| `/api/shipment-rounds` | GET (existing) | none | each waiting row and each lot gains `origin` | unchanged | unchanged |
| `/api/incoming/all` | GET | none | `MyIncomingResponse` | session, role `sales`, `sales_manager`, `ceo`, or read all holder | 403 |
| `/api/my-incoming` | GET (existing) | none | unchanged rows, plus the two new fields below | unchanged | unchanged |

- `ImportJobDto`: `importRequest` (the existing `ImportRequestDto`, which gains `source`), `items[]` (each with `id`, `seq`, `code`, `size`, `qty`, `unit`, `locked` and `lots[]`: `lotId`, `roundId`, `roundLabel`, `qty`, `receivedQty`, `step`), `rounds[]` (the shipment facts and step dates the sales lot view already shows, plus `transshipEtaOn` and `insuredOn`, and no documents), `history[]` (`at`, `actorName`, `action`, `before`, `after`), and `canEdit`.
- `origin` is `JOB` (the IR has a sales job), `SALES_ORDER` (no job, source `PORTAL`), `IMPORT` or `BACKFILL`.
- `MyIncomingRow` gains two fields on both incoming endpoints, so the response shape changes and its digest is regenerated: `repName` and `canOpen`. `MyLine` gains the columns they need (job owner name, `ir.requested_by_name`). Its `kind` may now be `CUSTOMER`.
- All new methods go into `hrApi.js` and `mockApi.js` with the same parameter count, and the digests in `docs/api/` are regenerated. The mock keeps import jobs in memory so the screens can be driven. Its permission checks prove nothing.

### Value sourcing

| Action | Value produced / displayed | Source |
|---|---|---|
| run | which sheet to read | the first sheet with `IR NO.` in any cell of row 1 |
| run | block boundaries | blank rows in that sheet |
| run | `doc_number` | `IR NO.` cell, normalised |
| run | `backfill_key` | first IR number of the block plus its count in the file |
| run | round step and step dates | the *Step* table, from the block's date and GR cells |
| run | factory | brand text through `normalizeFactoryName`, else `factoryChoices` |
| run | requester and rep name of a new IR | `Sale` text through `repChoices` |
| run | purpose | keyword suggestion from project text, else `purposeChoices` |
| run | match, adopt, fill in place, skip | the rule table in *Existing IRs* |
| run | `issue_date`, `first_issued_date`, `import_step_at` of a new IR | `วันที่ได้รับ IR`, else the earliest date in the block, else the run day in `Asia/Bangkok` |
| run | actor of every row and event | property `app.import-sheet.backfill.actor-employee-id` |
| run | `planSha256` | SHA-256 of the plan's `blocks`, `excluded` and `skipped` as canonical JSON |
| run, create | `project_name` of a new IR | the Thai label of its purpose |
| run, create, edit | IR step | `ShipmentStepDerivation.derive` over its lots |
| run | job stage, fulfilment status and payment status | existing `TicketService.applyImportRequestRollup`, unchanged |
| run, create, edit | `last_seq` | the largest sequence part among the numbers written, per year |
| create | `doc_number` when none is typed | `ImportRequestRepository.nextDocNumber` for today's Buddhist year |
| create, edit | actor, `created_by_*`, `issued_by_*`, history actor | the session user |
| create | `issue_date` | input `receivedOn` |
| create | lots | input `round` and the full quantity of each line |
| edit | history entry | the changed fields, their values before and after |
| import job page | `canEdit` | role is `import` or `ceo` |
| import job page | item `locked` | any lot of the line is on a `RECEIVED` round |
| queue | `origin` | `ticket_id` and `import_request.source` |
| queue | backfill line and "needs a check" list | latest row of `sales.import_sheet_backfill_run`, its `summary.skipped` and `summary.excluded` |
| incoming all | rows | `ShipmentRoundRepository.myLines` with all deals and all no job orders in scope |
| incoming all | `repName` | job IR: the full name of the job's `created_by` employee. No job IR: the requester's full name, else `ir.requested_by_name` |
| incoming all | project and customer text | the existing `who` text: job customer and project names for a job IR, `purpose_note` for a no job IR |
| incoming all | `canOpen` | the same predicate the read gates use, exposed as a boolean twin of `ImportRequestService.requireRead` (job IR) and `requireReadRow` (no job IR). It is not restated |
| incoming all | 30 day cutoff | today in `Asia/Bangkok` minus `MyIncomingService.ARRIVED_DAYS` (made visible to the package) |

### Key invariants

- A dry run never writes. Tested by counting rows in every touched table before and after.
- The run never lowers a round's step, never replaces a non null column value, and never deletes a round that holds documents or typed facts.
- The run never changes the items or lots of an IR that existed before that run.
- A `BACKFILL` round is exempt from the proforma and invoice number rule, as V239 already states.
- One IR number is one row. The unique index `ux_import_request_doc_number` stays the guard.
- The run log row is inserted first, in its own transaction. Then each block commits in its own transaction through a `TransactionTemplate`, and updates the log row's counts and `summary` in that same transaction. `finished_at` is set at the end. A block that fails rolls back alone and is listed in the report.
- Inside each block transaction the run takes one fixed transaction level advisory lock, then locks rounds, IRs and jobs in the order the round service uses, and only then matches against the portal.
- The run sends nothing. `ImportRequestService.afterStepChanges` gains a `notify` flag. The run passes `false`, which skips `notifyAllGoodsReceived` and `notifyRepOfImportProgress`. The run never calls `tellRepsLotsArrived` or the receipt correction notice. Job events are still written, with the note `นำเข้าจากตารางของเข้า`.
- The import job endpoints refuse any IR whose `ticket_id` is not null (AC-22).
- `NoProjectOrderService.withdraw` refuses an IR with source `IMPORT` or `BACKFILL` unless the caller's role is `import` or `ceo` (AC-23).
- `NoProjectOrderService.create` (the rep's own order form) still accepts only `SAMPLE`, `SHOWROOM` and `STOCK`. `CUSTOMER` comes only from the run and from import's create form.
- Every create and edit of an import job writes one `hr.audit_log` row in the same transaction.
- The repo is public. No fixture, test, mock, screenshot or doc may contain a row of the real sheet. Tests build their workbook in code from made up values.

### Security model

- **Backfill**: no HTTP surface at all. It runs only when a person with server access starts the one off container. The latest run summary is readable by roles `import` and `ceo` only.
- **Import jobs, write** (create, edit, items): roles `import` and `ceo` only, checked in the new service, the same set as `ADVANCE_STEP_ROLES`. The read all grant does not count. Being named as the rep on a job gives no write right.
- **Import jobs, read**: the existing rule for a no job IR (`ImportRequestService.requireReadRow`): `ceo`, `import`, `sales_manager`, the requester, a read all holder, and the showroom grant for a showroom order. A plain sales rep who is not the requester gets 403 on the job page, and still sees its progress in the company wide list.
- **Company wide list**: roles `sales`, `sales_manager`, `ceo`, plus `OversightReader.holder` (read all grant, which by its own rule never counts for `import` or `account`). `DivisionAccessPolicy.roleFor` already turns a holder of the quotation right into role `sales`. The showroom grant alone still shows showroom orders only.
- **What the list exposes** for another rep's IR: IR number, job code as plain text, project and customer text, rep name, factory, product, quantity, step, vessel and dates. It never exposes documents, proforma or invoice numbers, round notes, prices or costs. The response type has no field for them.
- **Unchanged**: `ImportRequestService.requireRead`, `requireReadRow`, `ShipmentRoundService.requireRead`, and every job level gate. The company wide list is the only widened read. The import job endpoints are the only new writes.
- **Round notes**: sheet remarks and unreadable date text land in `shipment_round.note`. The sales manager and read all holders can read a round's note today, so they will read this text too. Plain sales reps cannot. The dry run report shows the exact note text before anything is saved.
- **Frontend gates** (mirrors only, the backend decides): in `permissions.js`, rules for `/fulfilment/jobs/new` and `/fulfilment/jobs/` placed before the `/fulfilment/` prefix rule. The create page and every edit control show for roles `import` and `ceo` only, through a new permission key `canManageImportJobs`. The ทั้งบริษัท switch shows for roles `sales`, `sales_manager`, `ceo` and read all holders. It stays hidden for a user who reaches `/my-orders` only through the showroom grant or the deal entry right. Each rule gets cases in `permissions.test.js`.
- These are permission changes inside sales and import, so they ship with real database integration tests through the real service (see *Critical test scenarios*). A mock run proves nothing about them.
- Personal data: employee names and customer names, already in the portal. No new category.

### Screens

Build to the approved artboards. Thai labels, Buddhist era dates through `ThaiDateInput`, tables that become cards below 720px.

- **การขนส่ง** (`/fulfilment`, artboards `ID1`, `IM1`): a `+ สร้างงานนำเข้า` button beside `+ จัดรอบขนส่ง`, for import and the CEO. One info line after a backfill run, with a link that opens the "needs a check" list. A ที่มา tag on each waiting row (`งานขาย`, `ฝ่ายขายสั่ง`, `ฝ่ายนำเข้าสร้าง`, `จากตารางของเข้า`), and `จากตารางของเข้า` under the name of a backfilled round. The IR number of an import job links to its page.
- **สร้างงานนำเข้า** (`/fulfilment/jobs/new`, artboards `ID2`, `IM2`): a dialog on desktop, a sheet on a phone. Fields in the order of the artboard: type, project or customer, rep, factory, IR number (next number or typed), the three dates, item lines, round choice. The same form, filled in, is the edit form of a job.
- **งานนำเข้า** (`/fulfilment/jobs/:id`, artboards `ID3`, `IM3`): header with the project text, IR number, type and source tags. A card of the job's data with แก้ไข. A table of items and their lots with a per row edit, and `+ เพิ่มสินค้า`. The job's rounds with their steps and dates, read only here, each linking to the round page. A history list.
- **ผูกกับงานขาย** is drawn on the artboards but is not built in this spec. Do not render the button until the follow up decision (scope feature 37) is made.
- **Round page**: two more date fields in the facts form, `ETA ท่าเปลี่ยนเรือ` and `วันที่ทำประกัน`.
- **ของที่ฉันสั่ง** (`/my-orders`, artboards `ID4`, `IM4`): a switch ของฉัน and ทั้งบริษัท. ทั้งบริษัท adds a rep column, a งานลูกค้า type chip, a step dropdown, shows the job code as plain text and a ดู button when `canOpen` is false, hides withdraw, and on desktop hides the side order form so the table has room.
- **Label for `CUSTOMER`**: `งานลูกค้า`, with `ยังไม่มีงานขาย` beside it where space allows. Add it at every place that labels a purpose: the label lookup used by `NoProjectOrderService` notices (kept apart from the set its `create` accepts), the kind chips in `MyOrdersPage.jsx`, `frontend/src/utils/format.js`, and the purpose labels in the fulfilment feature. The rep's `NewOrderSheet.jsx` does not offer it.

### Critical test scenarios

Per `AGENTS.md`, tests come after the code and stay few, so these scenarios are grouped into a small number of test classes.

Backfill
- Happy path: a workbook built in the test with three blocks (one new sample IR, one block of two new customer IRs, one block matching a seeded job IR on an untouched round) is run dry, then committed. Rounds, lots, documents, steps and `backfill_key` match the mapping, verifies **AC-2**, **AC-4**, **AC-5**, **AC-6**.
- Dry run writes nothing: row counts of `import_request`, `import_request_item`, `shipment_round`, `shipment_lot`, `shipment_round_document`, `ticket_event`, `document_sequence`, `import_sheet_backfill_run` and `hr.notification` are equal before and after, verifies **AC-2**.
- No endpoint: the route list of the running application holds no path that accepts a sheet, verifies **AC-1**.
- Database checks: a new IR with no rep, no wanted date and no IR received date inserts cleanly, and the waiting list, the round page and both incoming lists render it, verifies **AC-6**.
- Two lots: a new IR listed in two blocks ends as one IR with items and lots on two rounds, and a second commit of the same file changes nothing, verifies **AC-6**, **AC-8**.
- Quiet job move: a seeded job at IR issued and deposit paid, whose only IR is in a received block, ends at goods received, delivery scheduling and awaiting final payment, with job events and zero new `hr.notification` rows and zero mails, verifies **AC-7**.
- Portal wins: after a first commit, change the ETA and advance the round in the portal, then run a workbook with an older step and a different ETA. Step and ETA stay, and the difference is in the report, verifies **AC-9**.
- Touched round: a seeded job IR whose own round holds a document and a typed vessel is filled in place. The round and its document survive, verifies **AC-5**.
- Wrong match, written the wrong way round: a sheet number equal to a seeded IR of another factory, and one equal to an IR of another rep's job, are both skipped. Neither job moves and neither round changes, verifies **AC-10**.
- Partial failure: a workbook with one bad IR number, one clash of sales codes and one block of mixed brands loads every other block, verifies **AC-11**.
- Stale plan: change the portal between dry run and commit. The commit writes nothing and exits with a failure code, verifies **AC-14**.
- No cost: the report contains no key and no value from the five cost columns, verifies **AC-12**. The run log row holds the counts and ids, verifies **AC-13**.
- Counter: after loading numbers above the current counter, issuing a job IR and creating a no job order both succeed with a number higher than every loaded one, verifies **AC-15**.
- Parser unit tests: text dates, Buddhist era years, a date with a port name, merged cells, continuation rows with stray text, a month banner, item lines with and without `=`, each step rule and each `received_on` fallback, verifies **AC-3**, **AC-4**.

Import jobs
- Create, three ways: with the next number and no round; with a typed number onto an existing round that is in transit; with a new round. Each ends as an issued IR with no job, the right lots, the right step, and a history entry, verifies **AC-16**, **AC-17**, **AC-18**, **AC-21**.
- Typed number: a used number and a malformed number are each refused. After a typed number above the counter, the next minted number is higher, verifies **AC-17**, **AC-15**.
- Edit the job: change type, text, rep and dates of a backfilled job, then change its number. Each change is in the history with before and after, verifies **AC-19**, **AC-21**.
- Factory lock: changing the factory of a job with a lot is refused, and allowed once it has none, verifies **AC-19**.
- Items: lower a quantity where one lot held the full line (the lot follows); raise a split line (the extra is unplaced); lower a split line below its placed sum (refused); change a quantity on a received round (refused, text change allowed); remove a line and its lots; remove the last line (refused), verifies **AC-20**.
- Wrong way round, on a real database: (1) `sales`, `sales_manager`, `account`, `hr`, `warehouse`, `qc`, `employee` and a read all holder each get 403 on create, edit and every item endpoint; (2) the rep named on a job gets 403 on edit and on withdraw; (3) every import job endpoint refuses an IR that has a sales job, verifies **AC-22**, **AC-23**.
- Mutation check on the role gate and on the "has a sales job" guard: break each, see that exact test go red and nothing else, then revert to an empty diff.
- Queue: a waiting row of each origin carries the right `origin`, and the latest run endpoint returns the "needs a check" list to import and 403 to sales, verifies **AC-24**.
- New dates: the two new dates round trip through the facts endpoint for import, verifies **AC-25**.

Company wide list
- Auth, written the wrong way round: (1) `hr`, `warehouse`, `qc` and `employee` get 403, and `account` and `import` get 403 even with the grant set; (2) a `sales` rep's list contains another rep's job IR, another rep's sample order and an import created `CUSTOMER` job, each with the rep name, and the serialized row holds no document number, no round note, and `canOpen` false; (3) the same rep still gets 403 on that IR by id, on that job, on that import job page and on that shipment round, verifies **AC-27**, **AC-28**, **AC-29**, **AC-30**.
- Mutation check on the list gate and on the 403 in (3).
- List content: an IR whose round was received 31 days ago is absent from the list, and one received 29 days ago is present, verifies **AC-31**.
- Frontend: `MyOrdersPage.test.jsx` covers the switch, its default, the hidden switch for a showroom only user, plain text job codes, the งานลูกค้า chip, search and the step filter. `permissions.test.js` covers the new rules, verifies **AC-26**, **AC-31**.
- Real stack: add the import job endpoints, the latest run endpoint and `/api/incoming/all` to `frontend/e2e-real/api-authz.spec.js` for every role in `REAL_ROLES`, verifies **AC-23**, **AC-29**.

## Build plan

The project builds Tracer Bullet: a thin thread through database, API and screen first, shown running, then thickened. Per `AGENTS.md`, tests follow the code and stay few. The owner has approved the design, so there is no prototype gate left.

Two threads share one migration and can run side by side after task 1: the backfill (tasks 2 to 8) and the import job screens (tasks 9 to 14). The backfill is the first priority, because it puts the current status in the portal.

1. Write the migration for the confirmed data model, satisfies **AC-4**, **AC-6**, **AC-13**, **AC-16**, **AC-25**.
2. Parser as a plain class (blocks, IR rows, item lines, dates, step, transport mode, block key), with unit tests on workbooks built in code, satisfies **AC-3**, **AC-4**.
3. Planner and dry run: the plan builder with the full rule table of *Existing IRs*, the excluded and differences lists, the choices file, the report writer and the runner in `DRY_RUN` mode. Run it against the real sheet on a local database loaded with made up jobs, and review the report with the owner. Satisfies **AC-1**, **AC-2**, **AC-10**, **AC-11**, **AC-12**.
4. Commit: `COMMIT` mode with the run log row, per block transactions, the advisory lock, rounds, new IRs and items, adopt, fill in place, lots, documents and the stale plan check, satisfies **AC-4**, **AC-5**, **AC-6**, **AC-13**, **AC-14**.
5. IR counter: the counter raise, shared by the run and the import job service, satisfies **AC-15**.
6. Quiet job move: the `notify` flag on `afterStepChanges` and the quiet step recompute and job rollup in the commit, satisfies **AC-7**.
7. Safe repeat: lookup by `backfill_key`, fill blanks only, forward only, document dedupe, satisfies **AC-8**, **AC-9**.
8. Two new dates on `ShipmentFacts`, `applyFacts`, `hasOwnData`, the round DTO and the round page, satisfies **AC-25**.
9. Import job service and create endpoint: `POST /api/import-jobs` and `GET /api/import-jobs/{id}`, with the withdraw guard and a real database auth test, satisfies **AC-16**, **AC-17**, **AC-18**, **AC-22**, **AC-23**.
10. Create screen and the button on การขนส่ง, with `hrApi.js`, `mockApi.js`, the digests and the route gates, shown working end to end in the real app, satisfies **AC-16**, **AC-17**, **AC-18**.
11. Edit and history: `PATCH /api/import-jobs/{id}` and the audit log writes and read, satisfies **AC-19**, **AC-21**.
12. Items: the three item endpoints with the lot rules, satisfies **AC-20**.
13. Import job page with its edit form, per row item edit and history, satisfies **AC-19**, **AC-20**, **AC-21**.
14. Queue: `origin` on the waiting rows and lots, the latest run endpoint, the info line and the "needs a check" list, and the `CUSTOMER` label sites, satisfies **AC-24**, **AC-6**.
15. Company wide list: `GET /api/incoming/all`, the new `MyLine` columns, `repName` and `canOpen`, with a real database auth test and a mutation check, satisfies **AC-27**, **AC-28**, **AC-29**, **AC-30**, **AC-31**.
16. The ของฉัน and ทั้งบริษัท switch with the type chip, search and step filter on `/my-orders`, and its visibility rule, satisfies **AC-26**, **AC-27**, **AC-31**.
17. Add the new endpoints to `e2e-real/api-authz.spec.js`, satisfies **AC-23**, **AC-29**.
18. Hand the merged commit to the deployer session with the migration number and the note that backend and frontend ship together, then follow *Migration plan*. Satisfies **AC-4** (in production).

## Migration plan

**Strategy**: release first, then one supervised backfill run. The release alone changes nothing visible except the new button for import and the switch for sales.

**Phases**:
1. Release the backend image and the frontend together. The migration only adds columns, one table, one index and two relaxed checks, so existing rows stay valid.
2. The deployer copies the sheet and an empty choices file to the server, outside the repo, and starts a `DRY_RUN`.
3. Review the report with the owner and the import team: totals, the excluded and skipped lists, the note text, the brands and sales codes that need a choice, and the list of jobs that will move forward. Fill in the choices file and run the dry run again until the plan is the one the owner wants.
4. Tell sales and account which jobs will move, since boards and the account queue change with no notice.
5. Take a database backup. With the owner's go, run `COMMIT` outside working hours with the reviewed plan's hash.
6. Check ของที่ฉันสั่ง as a sales rep, and three rounds on การขนส่ง against the sheet. Import works through the "needs a check" list.
7. Import updates the portal from then on and stops keeping the sheet. A later run on a newer file is possible and follows AC-9, but is not planned.
8. Delete the sheet, the choices file and the reports from the server once the owner is satisfied.

**Rollback**: before phase 5 there is nothing to undo. After it, restore the phase 5 backup while the run is the only change. If other work has happened since, use the run log row's `summary`: delete the created rounds and IRs, recreate each adopted round from its snapshot and move that IR's lots back onto it, clear the fields listed under `filledInPlace`, set the listed jobs back to their recorded stage, fulfilment status and payment status, and recompute the step of every IR touched. The counter raise is left as it is, because lowering it could cause a clash. The migration itself rolls back by dropping the table, the index and the four columns, and restoring the two old checks, but only after every `BACKFILL` and `IMPORT` IR and every `BACKFILL` round is gone.

**Risks**:
- The sheet is hand kept. A layout the parser has not seen can misread a block. The dry run report and the phase 3 review are the guard.
- About 8 jobs move to delivery scheduling and awaiting final payment in one step.
- A round marked received from a release date alone (4 blocks today) may be a day or two early.
- A typo in an IR number that happens to match another IR of the same factory and the same rep would still be adopted. The factory and rep checks narrow this, they do not close it. The report lists every adopted IR with its job code for a person to check.
- The run writes to production data. It needs the owner's explicit go for that exact run, and only the deployer session starts it.

## Consequences

**Positive**:
- The portal holds the true import status for every IR the team tracks, including sample, stock and customer orders that never had a job.
- Import no longer depends on sales, or on a sheet, to record goods on order. They can also fix what the sheet got wrong.
- One sheet block becomes one shipment round, which is exactly what the round model was built for. No parallel tracker table.
- No upload surface exists, so nobody can load a wrong file by accident.
- The IR counter is made safe against hand typed numbers, which was already a risk before this work.

**Negative / tradeoffs**:
- A later sheet cannot be loaded by import. It needs the deployer and a new run. This is the intent: the sheet is retired after the first run.
- Import can now create and change IRs with no review step. The history is the only control.
- An import job entered after the fact may join a round that has already left, which the round page forbids for everything else.
- The parser is tied to one hand kept layout, for a run that happens once.
- Every sales rep can now read every customer and project name that has an IR. This is a deliberate widening of what a rep sees.
- Sheet remarks become readable by the sales manager and read all holders through the round note.
- Import jobs stay outside the job pipeline, payments and commission until someone links them (see Follow-up).
- Cost data stays in Excel, so the sheet is not fully retired for whoever uses those columns.
- Item text for backfilled and import created jobs is free text, not catalogue codes.
- Received rounds of no job orders stay listed on การขนส่ง, because that page hides a round only when its jobs are finished. About 27 received rounds arrive at once.

**Neutral**:
- New type value `CUSTOMER` and new source value `IMPORT` appear wherever those are labelled.
- The 5 sheet rows that match a job only by project name, not by IR number, load as no job `CUSTOMER` IRs.
- `PICKED_UP` is never set by the run.
- A job IR whose round import already worked on keeps its own round, so that shipment shows as two rounds with the same vessel.
- `MyIncomingResponse` gains two fields on the existing endpoint as well.
- The backfill runner can be deleted one release after the run, as the other one off runners are.

## Follow-up

- [ ] Link an import job to a sales job (`/architect link a no job IR to a job`, scope feature 37). The artboards draw a ผูกกับงานขาย button, and this spec does not build it: it must settle one issued IR per job and factory, and how free text lines map to job items.
- [ ] Decide a home for the five cost columns before the sheet is fully retired.
- [ ] Hide old received no job rounds on การขนส่ง, for example after 30 days.
- [ ] Give `warehouse` and `account` the company wide list if they ask. Left out because the owner asked for sales.
- [ ] Add paging to `/api/incoming/all` if it passes about 2,000 rows.
- [ ] Remove `ImportSheetBackfillRunner` one release after the production run.
