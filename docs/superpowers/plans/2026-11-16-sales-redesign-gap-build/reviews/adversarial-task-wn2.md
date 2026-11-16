# Adversarial review: 17 Nov "มีอะไรใหม่" release (df277543, base e8beece3)

## Verdict: FIX BEFORE RELEASE

The notice's audiences, labels and claims hold up against the components and the Java. No role is shown an entry for a page it cannot open on production, and I found no false claim about behaviour. One finding stops the merge as the branch stands: the branch is based on `e8beece3`, but `origin/develop` has moved to `30f86f6b` (#1324 launcher), which deletes `Sidebar.jsx`. The new test file imports that file, so once develop is merged in, the whole release test file fails to load. The rest are medium and low accuracy problems in images and wording, plus test gaps.

Worktree clean at the end (`git status --short` is empty). Every mutation and the scratch merge were reverted or aborted.

---

## Findings

### F1 (High, proven): after merging current develop, the release test file fails to load
- `2026-11-17.test.js:15` has `import sidebarSrc from '../../../components/layout/Sidebar.jsx?raw'`. #1324 (`84b1fb9a`/`653e8299`, merged into develop as `30f86f6b`) deletes `Sidebar.jsx`. `NAV_GROUPS` becomes `SECTIONS` in the new `Launcher.jsx`.
- How I established it: `git merge --no-commit --no-ff 30f86f6b` merged with no conflicts. `npx vitest run src/features/whatsNew/` then gave `Failed to resolve import "../../../components/layout/Sidebar.jsx?raw"`, 1 test file failed, and all 25 tests in 2026-11-17.test.js were lost (40 others passed). Merge aborted.
- Fix: sync with develop. Point the menu check at `Launcher.jsx` (`SECTIONS` still has 'งานขาย' / 'การเงิน'; AppShell still has 'แดชบอร์ด', 'งานทั้งหมด', 'ใบเสนอราคา', 'นัดเข้าพบ', 'งานการเงิน').
- The menu wording itself still works after the launcher: the top-bar button is literally labelled "เมนู". None of the screenshots include the sidebar (they are content crops), so no image goes stale.

### F2 (Medium, proven): deal-documents-2.png contradicts the money wording of its own step
- Step (sales, deal-documents step 2) says the button "ขึ้นเมื่อรับมัดจำแล้ว", and that the summary line reads "ส่วนที่เหลือ … = มูลค่า … − มัดจำ …".
- The image shows `ส่วนที่เหลือ ฿275,000.00 (ก่อน VAT) = มูลค่า ฿275,000.00 − มัดจำ ฿0.00`, and `หักมัดจำ ฿0.00`. That is a deposit-taken deal whose remaining invoice deducts no deposit. A rep comparing this to a real deal will think the deduction is broken.
- The image is also the DraftEditor (it has ลบร่าง and ออกใบแจ้งหนี้), not the CreateForm the step's first action ("กด สร้างใบแจ้งหนี้ส่วนที่เหลือ แล้วกด บันทึกร่าง") opens.
- Recapture on a deal with a non-zero deposit, in the create state.

### F3 (Medium, proven): the quotation-list images are a sales rep's screen, but go to five roles (three on production)
quotation-list-1..4.png were captured as `sales`. On production the entry goes to sales, sales_manager and ceo. What differs for the other roles:
- **"สร้างใบเสนอราคา" button.** It shows in all four images. The CEO does not have it: `canCreateDealQuotationStandalone` = sales / sales_manager / grant (`quotationMeta.js:105-108`). account and import (lock off) do not have it either.
- **Subtitle "ใบเสนอราคาของคุณ".** This is the rep-only text. Approvers see "ใบเสนอราคาที่รอการอนุมัติ และทั้งหมด"; account and import see "ใบเสนอราคาทั้งหมด (ดูอย่างเดียว)" (`QuotationListPage.jsx` ~661-665).
- **Active tab "ทั้งหมด".** sales_manager and CEO land on "รออนุมัติ". The help text covers this.
- **Missing filters.** The images lack the พนักงานขาย and ยอดรวม filters that sales_manager and CEO have (`seesAll`). The step text does not mention them, so nothing in the text is false, but a manager's screen will not match the image.
- The step text itself is role-neutral and correct.

### F4 (Medium, test gap, proven by mutation): the tests cannot tell a control the reader does not have
The label check only asks whether a quoted string exists *anywhere* in the listed source files, comments included. Four mutations survived:
- **M4.** `"โทรแล้ว"` replaced by `"บันทึกกิจกรรม"`. That string exists only in a code comment in DealQuickActions.jsx.
- **M10.** The CEO finance entry was told to press "ยืนยันพร้อมปิดงาน", an account-only step (`financeActionPerformers.js`).
- **M17.** The CEO entry was given a deposit-notice "ออกเอกสาร" step, which is rep-only (`isOwnerSales` in `DealPaymentSection.jsx:414`).
- **M9.** The help text was changed to say sales_manager may cancel.

The current content is correct on all four points (I checked by reading the code). The guard is narrower than the report implies.

### F5 (Low, proven): remaining-invoice flow is described as close-and-reopen, but the dialog switches in place
- After "บันทึกร่าง", `CreateForm.onCreated` only invalidates the list. The same Modal re-renders the DraftEditor, with "ออกใบแจ้งหนี้" right there (`RemainingInvoiceDialog.jsx:121-128`).
- The notice says "เมื่อบันทึกร่างแล้ว ปุ่มในแท็บ "เงิน" เปลี่ยนเป็น "จัดการเอกสาร" กดเพื่อเปิดร่าง แล้วกด "ออกใบแจ้งหนี้"". That path works, but it is not what the user sees next.
- The test "remaining invoice is saved as a draft before it can be issued" pins the order of the *text*, not this behaviour.

### F6 (Low, proven): "เงียบ … วัน คือไม่มีความเคลื่อนไหวเกิน 14 วัน" leaves out the exclusions
`QuietRule.java:11-19` excludes the deliver & collect column and any deal with a booked visit ahead. all-deals-1.png itself shows column 5 cards 100+ days old with no เงียบ chip. 14 is the default (`application.yml:211`, `APP_SALES_QUIET_DAYS`, not overridden in render.yaml).

### F7 (Low, proven): the deal-documents help gives an escalation path that cannot fix anything
"บันทึกการส่งสินค้าแล้วแก้ไขหรือลบไม่ได้ … ถ้าบันทึกผิด แจ้งผู้จัดการฝ่ายขายทันที". No endpoint updates, deletes or reverses a `delivery_record` (grep of backend). The sales manager is not a delivery writer either (`TicketService.canWriteDelivery:3668` = CEO or owning sales). The irreversibility claim is true; the remedy implies a fix the system does not have.

### F8 (Low, proven): smaller condition gaps
- **Visits step 4.** The "ค้างจากสัปดาห์ก่อน" box shows only on the current week (`weekOffset === 0`, `VisitsPage.jsx`), not merely "เมื่อมีนัดค้าง".
- **IR step.** The IR button is also disabled until every line has a factory (`!allLinesPicked`). Not mentioned.
- **all-deals "ระบบจำแบบที่คุณเลือกไว้".** It is localStorage, per browser. The quotation entry correctly says "ในเครื่องนี้".
- **Finance.** An "อื่น ๆ ที่ต้องดำเนินการ" fourth group can appear (`OTHER_WORK_GROUP`). The notice says "สามกลุ่ม".

### F9 (Low): language
- "หน้าจอฝ่ายขายเรียก “ดีล” ว่า “งาน” แล้ว" is too broad. The quotation list still says "จัดกลุ่มตามดีล", "ดีล PR-…" and "เปิดดีล →" (quotation-list-2.png). The quotation entry itself mixes "ไม่จัดกลุ่มตามงานแล้ว" with the label "จัดกลุ่มตามดีล".
- deal-page why: "แท็บที่เหลือห้าแท็บ" reads as "the remaining five tabs". "แท็บเหลือห้าแท็บ" is meant.
- No stray English beyond on-screen labels (S1–S9, IR, VAT, CEO).
- Titles stand alone.
- Note: `ReleaseNoticePolicy.emailBody` sends **titles only**, not `why`. The release file's comment ("title + one-line reason") is wrong, so the `why` length and sentence rules guard nothing for email.

### F10 (Info): images
- **Mock and demo data.** No mock marker, but there is a real-looking bank account and email: deal-documents-2.png note templates show "กสิกรไทย 003-1-15914-8" and "info@glr.co.th". These are the company's own note templates and appear on real documents, but check they are the real values before publishing.
- **today-phone-6.png.** Shows the "งานใหม่" tile, which the shot list said to crop. It is the live, old create flow; the notice does not describe it.
- **deal-page-4.png.** The capture caught the condensed sticky header overlapping the page ("ดูขั้นตอนทั้งหมด (15 ขั้น)" peeks out behind the tabs). Cosmetic.

### F11 (Info): CLAUDE.md is out of date
CLAUDE.md says the lock exempts only hr and ceo. The code (`permissions.js:176`) exempts `['hr','ceo','sales','sales_manager']`. I used the code. The notice's production audience depends on this.

---

## Role × entry (computed with the real `entryVisibleTo` / `canAccessPath`; matches the passing locked/unlocked tests and my reading of permissions.js)

| role | lock OFF | lock ON (production) |
|---|---|---|
| sales | all-deals, deal-page, deal-documents, quotation-list, visits, today-phone | same 6 |
| sales_manager | all-deals-team, deal-page, quotation-list, visits-team | same 4 |
| ceo | all-deals-team, deal-page, deal-documents-ceo, quotation-list, visits-team, finance-worklist-ceo | same 6 |
| account | finance-worklist, quotation-list | finance-worklist |
| import | quotation-list | (empty) |
| hr, warehouse, qc, employee (incl. division manager) | (empty) | (empty) |

How each role's entries hold up on production:
- **sales.** Every control described in its entries is there for the owning rep: DealQuickActions `canAddActivity`; quiet links via `canDealOwnership`; deposit and remaining invoice `isOwnerSales`; IR `canFullWriteStoredIr`; delivery `canWriteDelivery`; tabs `visibleSections` all true; MyWork is the sales landing.
- **sales_manager.** Gets no document entry, which is correct: it cannot write IR or delivery. Its deal-page controls are present: canAddActivity includes sales_manager, canDealOwnership includes sales_manager, all 5 tabs show. Cancel is owner-only, as the help says.
- **ceo.** Its documents entry is limited to IR and delivery (CEO is in canFullWriteStoredIr and canWriteDelivery). Its finance rows say "เปิด" (on no PERFORMERS list).
- **account.** Under the lock it can open /finance only (`RELEASED_PATHS_BY_ROLE`). /quotations is closed to it (`QUOTATION_RELEASE_ROLES` = sales, sales_manager).
- **Roles with a changed page they are not told about.** None found:
  - import's /import/deals and /fulfilment only changed a hint noun (the "ดีล → งาน" change is deliberately deal-page only, per `ImportRequestFactoryCard`).
  - account's finance deal page does not use the changed dialogs.

## 16 Nov release (attack 5)
- `git diff e8beece3..df277543 -- …/2026-11-16.js public/whats-new/2026-11-16` is empty.
- Behaviour is equivalent for 16 Nov:
  - The page's new section filter removes only empty sections. allTabSections pushes role sections only when they are non-empty, and 16 Nov's everyone ("address", not a landing entry, so visible under the lock) is non-empty.
  - The note ternary keeps the published text verbatim when an everyone entry exists.
  - The bubble's empty branch only triggers on zero entries, and every role has the address entry.
- A bell link of `/whats-new/2026-11-16` still resolves:
  - **Page.** `findRelease`. Mutation M12 (page always shows the latest release) was killed by 10 tests.
  - **Bubble.** `releaseToShow` parses the link. M13 was killed by 2 tests.

## Latest-release behaviour (attack 6)
- **Which release each surface uses.**
  - Pill: the first unread RELEASE_NOTE row's release, else the latest (17 Nov).
  - Red dot: any unread RELEASE_NOTE row, tracked per bell row on the server, so there is no global "seen" flag.
  - Menu item and `/whats-new`: the latest release.
  - Opening a release marks only that release's rows read (M16 killed).
- **Suspected, low.** A user with an unread 16 Nov row who opens "มีอะไรใหม่" from the menu lands on 17 Nov, and the dot stays. It clears through the pill, which goes to 16 Nov. No case of a dot that never clears, and no case of a missing 17 Nov dot: 17 Nov rows are new rows.
- **Publish / email.** They send the release being viewed (`ReleaseNoticeAdmin release={release}`).
- **Production payload.** 11 changes. Roles: sales, sales_manager, ceo and account (account only on finance-worklist). quotation-list goes to [sales, sales_manager, ceo]. No change has zero roles; no title for an entry its role cannot open.
- **Server limits** (`ReleaseNoticePolicy`): release title about 70 characters (max 150), change titles 90 or fewer (max 200), 11 changes (max 50), all roles valid. It would not be rejected.

## Empty state (attack 7)
- **Who sees it.** hr, warehouse, qc and employee (and import on production) see the empty state in three places:
  - the page's mine tab: "ฉบับนี้ไม่มีเรื่องเฉพาะแผนกของคุณ / ดูทุกเรื่องได้ที่แท็บ ทั้งหมด", unchanged from before;
  - the pill: the same sentence;
  - the bell: nothing, because the payload excludes them.
- **ทั้งหมด tab note.** "…: ฉบับนี้ไม่มีเรื่องเฉพาะแผนกเหล่านี้".
- **The exception in releases.test.js.** A section may be empty only when there is no everyone entry AND, with the lock off, the role can open none of the release's non-landing entry paths. That is reasonably tight, with two loose ends:
  - Landing entries are excluded, so a future landing entry for hr's own dashboard could leave hr silently empty.
  - The `hasEveryoneEntry` guard is never exercised: M18 removed it and survived.
- The old blanket "at least one entry" rule is re-pinned for 16 Nov only.

## Mutations (`npx vitest run src/features/whatsNew/`, each reverted)

| # | mutation | result |
|---|---|---|
| M1 | add finance-worklist to import | killed (2) |
| M2 | add quotation-list to hr | killed (3) |
| M3 | rename pinned label เซลส์ย้ายเอง | killed (2) |
| M4 | "โทรแล้ว" → "บันทึกกิจกรรม" (exists only in a comment) | **survived** |
| M5 | images point at 2026-11-16 folder | killed (2) |
| M6 | stepper "แปดขั้น" → "เก้าขั้น" | killed (1) |
| M7 | delivery "ไป S9" → "ไป S8" | **survived** |
| M8 | quiet "14 วัน" → "30 วัน" | **survived** |
| M9 | cancel help → sales_manager may cancel | **survived** |
| M10 | CEO told to press ยืนยันพร้อมปิดงาน | **survived** |
| M11 | remove bubble empty state | killed (1) |
| M12 | page always shows latest even with an id | killed (10) |
| M13 | bubble ignores unread notice | killed (2) |
| M14 | 16 Nov note → generic wording | killed (1) |
| M15 | drop empty-section filter | killed (1) |
| M16 | mark-read marks every release | killed (1) |
| M17 | CEO entry gains rep-only deposit ออกเอกสาร step | **survived** |
| M18 | remove hasEveryoneEntry guard in releases.test | **survived** (no fixture exercises it) |
| M19 | remove `landing` from today-phone | killed (1) |
| M20 | sales_manager gets rep "visits" instead of visits-team | killed (3) |
| M21 | delete today-phone mobile-only help | **survived** |
| M22 | delete visits-2.png | **survived** (no test checks the image files exist) |

No existing assertion was deleted. The "every role ≥1 entry" assertion was narrowed: it now applies to every release only through the exception above, and is pinned verbatim for 16 Nov.

## What I attacked and found to hold (no finding)
- Every quoted label in every entry was checked by hand against the component that renders it for that role. Components checked:
  - AllDealsPage, DealBoard, TicketListPage
  - DealStepper / dealSteps, DealHeader, NextStepCard, DealQuickActions, DealQuietLinks, DealTabBodies, ticketDetailTabs, salesViewScope
  - DealPaymentSection, DepositIssueSummary, RemainingInvoiceDialog, DealFulfilmentPanel
  - QuotationListPage
  - VisitsPage, VisitsThisWeek, VisitWeekGrid
  - TodayOnPhone, MyWork
  - AccountFinancePage, financeWorkGroups, financeActionPerformers
  - AppShell (e8beece3 and 30f86f6b)
- Java claims checked:
  - Delivery completion goes to S9 and notifies account (`TicketService.java:1806-1832`).
  - Delivery writers are CEO or the owning rep (`:3668`).
  - A cancelled visit cannot be undone (`VisitRepository:128-132`).
  - The team grid is sales_manager and CEO only (`VisitService.TEAM_ROLES`).
  - Lost and hold are available to the owner, sales_manager and CEO (`canDealOwnership:3846`).
  - The quiet default is 14 days.
  - An IR "ลบไม่ได้ · แก้ได้ด้วยฉบับแก้ไข" (per the panel comment citing ImportRequestService#revise).
- Production liveness: no backend change on develop after #1317 (`git log e8beece3 -- backend/`). team-board and the visits endpoints are in the image. approvedAt predates the release.
- Images read, 36 of 43: all-deals-1, 3, 4; all-deals-team-1, 3; deal-page-1 to 5; deal-documents-1 to 4; finance-worklist-1 to 3; finance-worklist-ceo-1 to 3; quotation-list-1 to 4; today-phone-1 to 6; visits-1 to 5; visits-team-1, 2. Not read: all-deals-2, all-deals-team-2, all-deals-team-4, visits-team-3, deal-documents-ceo-1, deal-documents-ceo-2, plus one more. The two deal-documents-ceo files have the same dimensions as deal-documents-3 and -4 and are described as the same dialogs, but I did not open them. Apart from F2, F3 and F10, each image I read shows what its step says.

## Proven vs suspected
- **Proven (by command, file or line):** F1 to F11, the role table and the mutations.
- **Suspected:** the menu-versus-pill dot persistence (low UX); whether the bank number in F10 is the real one.
