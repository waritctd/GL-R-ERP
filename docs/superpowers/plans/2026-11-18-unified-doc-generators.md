# Unified document generator screens — Implementation Plan

> **For agentic workers:** implement task-by-task, test-first. One implementer per task, a task review after each.

**Goal:** The deposit notice editor and the remaining invoice dialog use the quotation editor's visual vocabulary
(`frontend/src/features/quotations/QuotationEditorPage.jsx`): page header with status and actions, titled panels,
field grid, item table, totals block, save bar. Behaviour and owner rulings already pinned by tests stay as they are.

**Starting point:** branch `ui/unified-doc-generators`, one WIP commit on top of `develop` after the deposit notice
fix (#1360). `DepositNoticePage.jsx` is already restyled; its test file still expects the old layout (15 of 27 fail),
the side preview is no longer rendered but its code is still in the file, and a static prototype page for the
remaining invoice exists.

**Spec:** none; the three design decisions below are the spec. They were made by the controller and each was
challenged by an independent agent against the code before this plan was written.

## Decisions

1. **Deposit notice side preview: removed; the real file is offered on a draft instead.** The preview is HTML from a
   separate renderer and can disagree with the PDF; its two-column layout does not fit a phone. A draft gets the same
   "ดาวน์โหลด" menu (PDF / Excel) as the quotation editor. Because a draft file would otherwise look like an issued
   one, the rendered file marks itself: a draft prints "ฉบับร่าง" where the document number goes (Task 2, the one
   backend change). `api.depositNotices.preview` and the deal page's own preview are not touched.
2. **Remaining invoice: the dialog stays a dialog and is restyled; it does not become a page.** It has no editable
   line (the lines are a snapshot of the issued deposit notice), no item data before a draft exists, five openers
   with their own write rules, and 47 tests of owner rulings. The static prototype page and its route are deleted.
3. **Deposit notice lines stay editable; a mismatch with the quotation is shown, not blocked.** An edited notice is
   the agreed amount by owner ruling, so issuing is never blocked. The page and every issue confirmation show a line
   when the notice's subtotal differs from its quotation's stored pre-VAT total. The quotation is identified from the
   server (`orderConfirmedQuotationId`), falling back to the reference text.

Not in this plan (reported to the owner instead): a "re-pull lines from the quotation" action for drafts created
before #1360; keeping a line's discount when its unit price is edited; removing the deal page's own HTML preview.

## Global Constraints

- The repository is PUBLIC: no real customer names or data in code, tests, comments or commit messages. Follow the
  neutral values existing fixtures use.
- Test first. For every behaviour change: write or adjust the test, run it, record the RED output, then implement,
  then record GREEN. A test that fails only because the layout changed is repaired by changing how it finds the
  element, never by weakening or deleting what it asserts.
- Never skip, disable or delete a test to get green. Deleting a test is allowed only when the feature it tests is
  deleted by this plan (the side preview), and the report must list each one.
- Targeted tests only, never the whole suite. Frontend: `cd frontend && npx vitest run <file> [<file>…]`. Lint:
  `cd frontend && npx eslint <changed files>` must exit clean. Backend (Task 2 only), one Maven process at a time:
  `cd backend && export TEST_DB_URL=jdbc:postgresql://localhost:5432/glr_test TEST_DB_USERNAME=postgres TEST_DB_PASSWORD=postgres && ./mvnw -q test -Dtest.fork.count=1 -Dtest='Class#method'`,
  results in `backend/target/surefire-reports/*.txt`. If Postgres is down: `pg_ctlcluster 16 main start`.
  Known environment-only failure to ignore: `DepositNoticePdfRendererTest#anUnbreakableThaiRunWrapsBetweenClustersNeverBeforeACombiningMark`.
- UI text is Thai, in the wording the surrounding screen already uses. Reuse the shared components the quotation
  editor and the restyled deposit page use (`PageHeader`, `Panel`, `FormField`, `StatusBadge`, `OverflowMenu`,
  `Button`, the `table-head` / `data-row` / `reflow-cards` classes); do not add a new styling system or dependency.
- Phones: no horizontal page scroll at 320px wide; controls a finger must hit are at least 44px high on mobile (the
  codebase's `mobile:` variants).
- Keep every existing `data-testid`.
- Commit on `ui/unified-doc-generators` only. Commit messages: `type(scope): summary` as in `git log`. No
  `Co-Authored-By` line and no tool footer. Do not push.
- Do only what the task says. Anything else you notice goes in the report.

---

### Task 1: Deposit notice page — tests on the new layout, preview code deleted, draft download menu

**Files:** `frontend/src/features/deposits/DepositNoticePage.jsx`, `frontend/src/features/deposits/DepositNoticePage.test.jsx`,
`frontend/src/App.jsx` (only to drop a prop that becomes unused).

1. **Repair the 15 tests that fail on the new layout** (`npx vitest run src/features/deposits/DepositNoticePage.test.jsx`
   shows them). Change selectors and structure lookups only; every asserted value, payload and message stays. If a
   test fails because the restyled page really lost a behaviour (not just moved an element), that is a defect in the
   page: fix the page, and say so in the report.
2. **Delete the side preview code** from the page: the `previewHtml` state, `previewMutation`, `handlePreview`,
   `previewLoading`, `iframeRef`, the Preview button if any remains, and imports and props that become unused
   (`Breadcrumbs`, `onNavigateTickets`; drop the prop at its caller in `App.jsx` too). `api.depositNotices.preview`
   itself stays in the API layer and the mock: `DealPaymentSection.jsx` still uses it. The test "refuses to save or
   preview when every row was cleared…" becomes "refuses to save or download…" (see 3).
3. **One "ดาวน์โหลด" menu (PDF, Excel) for both a DRAFT and an ISSUED notice**, built like the quotation editor's
   (`QuotationEditorPage.jsx`, the `OverflowMenu` near `handleDownload`), replacing the two issued-only buttons. It
   calls the existing `api.depositNotices.downloadPdf` / `downloadXlsx`. On an ISSUED notice it downloads directly, as
   today. On a DRAFT:
   - every row cleared (the existing `refusesClearedItems` rule): no API call, the same items error the save shows;
   - the form differs from what was loaded or last saved: save first through the same path as บันทึก (same payload,
     same 409 handling), and download only if the save succeeded;
   - the form is unchanged: download the stored draft without saving (a reader who cannot save still gets the file).
   Both menu items are disabled while a download is running.
4. New tests, written before the code in 3: an unchanged draft downloads without calling `update`; an edited draft
   calls `update` then the download, in that order; a failed save does not download; a cleared-rows draft calls
   neither and shows the items error; an issued notice downloads without `update`.

**Done when:** the page test file passes in full, eslint is clean on the three files, and
`grep -n "preview\|Preview" frontend/src/features/deposits/DepositNoticePage.jsx` shows no preview code.

### Task 2: A draft deposit notice file marks itself as a draft

**Files:** `backend/src/main/java/th/co/glr/hr/deposit/DepositNoticeService.java` (`renderModel`) or
`DepositNoticePdfRenderer.java` (whichever is the smaller change), and `DepositNoticePdfRendererTest.java` or
`DepositNoticeServiceTest.java` (wherever the render model of a draft is easiest to pin).

A DRAFT has no document number, so the PDF and Excel file print an empty number cell and look like an issued
document. Make both print `ฉบับร่าง` in the document-number cell when the notice has no number. An ISSUED notice is
unchanged. The HTML preview already prints its own "DRAFT"; leave it.

Tests first: a draft's rendered sheet/model carries `ฉบับร่าง` in the number cell (RED: empty/null); an issued
notice still carries its number.

**Done when:** the new tests and the rest of `DepositNoticePdfRendererTest` (except the known environment-only
failure) and `DepositNoticeServiceTest` pass.

### Task 3: Notice-versus-quotation mismatch line, on the page and on every issue confirmation

**Files:** `frontend/src/features/deposits/DepositNoticePage.jsx` (page and the exported `DepositIssueSummary`),
its test file, `frontend/src/features/tickets/DealPaymentSection.jsx`, `frontend/src/features/quotations/QuotationDocumentChain.jsx`
and their test files; `frontend/src/api/mockApi.js` only if the mock's deal-quotation list lacks a field the real
API returns.

1. **Which quotation.** The page keeps only `items` from `api.dealQuotations.listForTicket` today. Keep the whole
   response. The notice's quotation is the list item whose id is the response's `orderConfirmedQuotationId`; if that
   is absent, the item whose `number` equals the notice's reference; else none. The existing percent hint
   ("ใบเสนอราคา … ระบุมัดจำ …% — ใบแจ้งนี้ใช้ …%") uses the same resolved quotation. Its existing tests must keep
   passing with their assertions unchanged.
2. **The mismatch line.** When a quotation is resolved and the notice's subtotal (2 dp) differs from that
   quotation's `subtotalAmount` (its stored pre-VAT total), show, in the same style and place as the percent hint:
   `ใบเสนอราคา {number} มูลค่า {quotation subtotal} — ใบแจ้งนี้ {notice subtotal}`. Non-blocking: ออกเอกสาร stays
   enabled. Not shown on an ISSUED notice, and not shown when no quotation is resolved.
3. **Every issue path shows it.** `DepositIssueSummary` takes an optional quotation (`number`, `subtotalAmount`) and
   renders the same line when the subtotal it is given differs. Pass it from the page's own issue confirm, from
   `DealPaymentSection`'s issue dialog and from `QuotationDocumentChain`'s `DepositNoticeIssueDialog`. Use the
   deal-quotation list each caller already has, or the same query key the page uses (so it is served from cache);
   do not add a new endpoint. If a caller cannot get the quotation without a backend change, stop and report
   NEEDS_CONTEXT for that caller rather than inventing one.
4. Tests first: page shows the line when subtotals differ and not when equal; resolved by
   `orderConfirmedQuotationId` even when the reference is a PO number; falls back to the reference; hidden when
   issued; ออกเอกสาร enabled; `DepositIssueSummary` shows it; one test per additional issue path.

**Done when:** the four test files pass and eslint is clean on the changed files.

### Task 4: Remaining invoice dialog restyled; prototype page removed

**Files:** `frontend/src/features/tickets/RemainingInvoiceDialog.jsx` and its test file; delete
`frontend/src/features/tickets/RemainingInvoicePage.prototype.jsx`, its import and route in `frontend/src/App.jsx`,
and the directory `.design/doc-generators/`.

1. Delete the prototype page, its import and its route (`/tickets/:ticketId/remaining-invoice`), and the capture
   scripts directory. Nothing else may reference them (`grep`).
2. Restyle both forms of the dialog (the create form and the stored-draft form) with the deposit page's vocabulary,
   keeping the dialog (`Modal`, sheet on phones), every behaviour, every `data-testid`, every label text and all
   existing tests:
   - sections in this order, each with a titled heading styled like a `Panel` title: `ข้อมูลเอกสาร` (the quotation
     picker when shown, reference, deposit reference, date), `เงื่อนไข` (the notes), `ยอดรวม` (the totals);
   - fields laid out through `FormField` in two columns that collapse to one on mobile;
   - the totals block (`data-testid="remaining-invoice-preview"`) uses the same markup and classes as the deposit
     page's totals block, right-aligned figures with `tabular-nums`, the payable row emphasised;
   - the document status shown as a `StatusBadge` where the dialog shows status text today;
   - the footer row is the save bar: secondary actions left, primary right; full-width buttons on mobile.
3. A stored row that carries `items` (check the stored remaining-invoice DTO; if it carries none, skip this step
   and say so) shows them read-only under a `รายการสินค้า` heading between ข้อมูลเอกสาร and เงื่อนไข: description,
   quantity with unit, net unit price, amount, using `table-head` / `data-row` / `reflow-cards`. Nothing is
   editable. The create form, which has no items, keeps its `จำนวนรายการ` row.
4. Tests: all 47 existing tests in `RemainingInvoiceDialog.test.jsx` pass with assertions unchanged (selectors may
   change where markup moved). New tests first: the three section headings render in order in both forms; the item
   list renders the stored rows read-only (if step 3 applies) and is absent on the create form. Then run the other
   files that open the dialog: `QuotationEditorPage.documentChain.test.jsx`, `TicketDetailPage.test.jsx`,
   `DealDocumentRegister.test.jsx`, `DealPaymentSection.test.jsx`.

**Done when:** those five test files pass, eslint is clean on the changed files, and
`grep -rn "RemainingInvoicePagePrototype\|doc-generators" frontend/src .design` finds nothing.

### Task 5: Phone and desktop width check on the running mock app

**Files:** whatever Tasks 1, 3 and 4 touched, only where a width problem is found.

Start the mock app (`cd frontend && npx vite --host 127.0.0.1 --port 5204 --strictPort --mode mock`; check
`package.json` and `vite.config` for the project's mock mode if that flag is wrong) and drive it with the
pre-installed Playwright (`require('playwright')`, `chromium.launch()` with no options; scripts live outside the
repo, in the scratchpad directory given in the dispatch). Log in by clicking the role button whose name starts with
`ฝ่ายขาย`. For each of the widths 320, 375, 768 and 1280:

- the deposit notice page with an ISSUED notice (`/tickets/19/deposit`) and with a DRAFT (`/tickets/20/deposit`
  after `setDepositNoticeStatusesForTests(20, 'DRAFT')` from the mock module, imported in the page as the old
  capture script did);
- the remaining invoice dialog in its create form and in its stored-draft form, opened from a deal page that offers
  it.

At each, record `document.scrollingElement.scrollWidth <= window.innerWidth` (no horizontal page scroll), that no
text or control is clipped or overlapping, and on 320 and 375 that buttons and inputs are at least 44px high. Save
one full-height screenshot per screen and width at device scale factor 2. Fix what fails, test first where a test
can express it, and re-run the page tests of any file changed.

**Done when:** every screen passes at every width, the screenshots are saved, and the touched test files pass.
