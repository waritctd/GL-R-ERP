# Report: second "มีอะไรใหม่" release (2026-11-17)

Worktree `C:\Users\user\Documents\GitHub\GL-R-ERP\.claude\worktrees\whats-new-1117`, branch `feat/whats-new-2026-11-17`. Everything is uncommitted (my rules forbid committing). `git fetch origin` showed develop had not moved past `e8beece3`, so there was nothing to merge.

## State found
- The previous agent had finished most of the work: an 11-entry release module, its test file, registration in `index.js`, and changes to the bubble, the page and their tests. All 53 tests were green.
- Problems in the draft:
  1. **Wrong 16 Nov wording.** `WhatsNewPage.jsx` replaced the published note "ฉบับนี้มีเฉพาะการย้ายที่อยู่เว็บ (ด้านบน)" with a generic sentence, which changes what `/whats-new/2026-11-16` shows.
  2. **Rule removed.** `releases.test.js` dropped the "every role needs at least one entry" rule for every release, with nothing put in its place.
  3. **Roles left out.** `account` and `import` can open `/quotations` when the lock is off (`canViewDealQuotations`), but were given nothing about the ใบเสนอราคา changes.
  4. **Claim not on the rep's screen.** `all-deals` (sales) said the rep's table shows a "งานเงียบ" badge. Only `sales_manager`/`ceo` get that summary (`TicketListPage` `MANAGER_PIPELINE_ROLES`).
  5. **Claim not on the /visits screen.** `visits-team` said each rep's row shows "เข้าพบแล้ว … จาก … นัด". That text is the dots column of ภาพรวมทีมขาย (`VisitDots`), not the /visits grid. The source-grep check passed anyway, because the text exists in the same file.
  6. **Not email-ready.** Titles were "Page: fragment" headings, and some "why" lines ran to several sentences (208 characters).
- Everything else in the draft checked out against the components: labels, positions, role branches, tabs, dialogs and the finance buttons.

## Entries and who gets each (lock off; under the lock, account keeps only finance-worklist and import gets nothing)
| entry | roles |
|---|---|
| all-deals (own board) | sales |
| all-deals-team (team board, owner names) | sales_manager, ceo |
| deal-page | sales, sales_manager, ceo |
| deal-documents (deposit, remaining invoice, IR, delivery) | sales |
| deal-documents-ceo (IR and delivery only) | ceo |
| quotation-list | sales, sales_manager, ceo, account, import |
| visits (own week) | sales |
| visits-team (team grid) | sales_manager, ceo |
| today-phone | sales |
| finance-worklist (action buttons) | account |
| finance-worklist-ceo (rows say "เปิด") | ceo |
| nothing (empty-state, no filler) | hr, warehouse, qc, employee |

- `sales_manager` gets no document entry: it cannot issue an IR or record a delivery (`canFullWriteStoredIr`; delivery is CEO or the owning rep).
- Not described anywhere: the lifecycle dialogs and "งานใหม่".
- Item 8 (ดีล → งาน) is folded into the two board entries.

## Changes outside the release file
- **Kept: `WhatsNewBubble.jsx` empty state.** Needed because four roles have zero entries in the latest release. Without it they open the pill and get a heading over an empty list. It uses the page's own empty-state words. It changes nothing for 16 Nov, where every role has at least one entry, even under the lock.
- **Kept: `WhatsNewPage.jsx` filter for empty sections.** Needed because `allTabSections` always emits a "ทุกแผนก" section, which would be an empty heading for 17 Nov. It does nothing for 16 Nov.
- **Kept, and corrected: `WhatsNewPage.jsx` note text.** The address-move wording only applies to a release with an everyone entry (16 Nov), so 16 Nov keeps it verbatim. A release with no everyone entry says "ฉบับนี้ไม่มีเรื่องเฉพาะแผนกเหล่านี้". I reverted the previous agent's generic rewording.
  - Known risk: a future release with a non-address everyone entry would show the address sentence. That limit existed before this change.
- **Kept: the bubble and page test updates.** The latest release is now 17 Nov. The 16 Nov assertions moved to explicit `/whats-new/2026-11-16` and unread-notice cases.
- **`releases.test.js`:**
  - The "at least one entry" rule now has a narrow exception: a section may be empty only when the release has no everyone entry AND the role, with the lock off, can open none of the release's non-landing pages.
  - The original rule is still pinned verbatim for 16 Nov ("every role is told at least one thing").
  - New rule: titles must be unique within a release.
  - I am flagging this as a judgement call. The no-filler rule and the old blanket assertion cannot both hold for this release.
- **`e2e-real/route-coverage.spec.js`:** added `/whats-new/2026-11-17` to `ROUTES`. Nothing else in `e2e-real/` mentions the What's-new page, pill or routes.

## Test-first coverage
### Red run: 11 failed out of 60, all assertion failures
- 16 Nov note text: expected the address wording, got the generic sentence.
- 17 Nov "ทั้งหมด" tab is missing "ฝ่ายนำเข้า".
- account and import sections are wrong.
- ใบเสนอราคา audience does not equal the `canViewDealQuotations` roles.
- allTabSections and publishPayload return the wrong roles.
- Under the lock, the expected hidden set is `account:quotation-list` and `import:quotation-list`; the draft hid nothing.
- Title contains a colon.
- "why" is over 160 characters.
- all-deals quotes "งานเงียบ".
- releases.test reports: "import can open /quotations but is told nothing".

### Green
- All 60 whatsNew tests pass.

### Mutation checks
- Removing the bubble empty branch fails exactly "a role the release has nothing for is told so". Nothing else fails.
- Removing the page section filter fails exactly the 17 Nov "ทั้งหมด" test. Nothing else fails.
- Both mutations were reverted.

### Pins on the published release
- `/whats-new/2026-11-16`: the note text is pinned verbatim.
- hr sees 1 card (the address move) on its "mine" tab.
- hr with an unread 16 Nov notice: the bell popover shows 16 พ.ย. with 1 item, and the button goes to `/whats-new/2026-11-16`.
- These pins passed before my change, because the code they pin already existed.

### What the tests do and do not prove
- **Labels:** every quoted label must exist in the source of the screen it describes. A string that is in the source but on another role's screen is not caught automatically; the two I found are pinned explicitly.
- **Not covered:**
  - Whether the Thai is good: needs human review.
  - Screenshots: none exist yet. No test requires the image files, so none fails.
  - The e2e suite: not run, since it needs the real stack.
- **Permissions:** no authorization change. The audience rules are UI-only and use the real `canAccessPath`, with the lock both on and off.

## Commands
| command | result |
|---|---|
| `npx vitest run src/features/whatsNew/` | red 11/60, then green 60/60 |
| `npm run lint` | clean |
| `npm test` | 311 files, 6,863 tests passed |
| `npm run build` | OK (the usual chunk-size warning) |

I did not check the pages in a browser. The running preview on port 5201 belongs to another session and serves the repo root, not this worktree.

## Screenshot shot list
Mock app. Sign in with the quick-login button named in the table. Desktop shots are 1440×900; `today-phone` shots are 375×812. Every file goes to `frontend/public/whats-new/2026-11-17/`.

### all-deals (sign in as ฝ่ายขาย, desktop)
| file | how to get there | must be visible |
|---|---|---|
| all-deals-1.png | open `/tickets` | the header "งานทั้งหมด" and all five columns; at least one "อัปเดตอัตโนมัติ" tag |
| all-deals-2.png | same page | the filter row ไตรมาสนี้ / ทั้งหมด / เฉพาะงานเงียบ n and the search box; ideally a card with "เงียบ n วัน" |
| all-deals-3.png | same page | a card, plus the พักไว้ · เสียงาน · ยกเลิก links at the right of the filter row |
| all-deals-4.png | click "ตาราง" | the table, with the "บอร์ด" button at top right |

### all-deals-team (sign in as ผู้จัดการฝ่ายขาย, desktop)
| file | how to get there | must be visible |
|---|---|---|
| all-deals-team-1.png | open `/tickets` | the team board, with owner-name chips on the cards |
| all-deals-team-2.png | same page | the filter chips |
| all-deals-team-3.png | click "ตาราง" | the ผู้ดูแล column and the search box "ค้นหาเลขที่ / บริษัท / โครงการ / ผู้ดูแล" |
| all-deals-team-4.png | back to the board | a card being hovered, plus the closed-deal links |

### deal-page (sign in as ฝ่ายขาย, desktop; open any ACTIVE deal of the rep from the board)
| file | must be visible |
|---|---|
| deal-page-1.png | the stepper S1–S9, with the current step and the "เซลส์ย้ายเอง" / "ย้ายตามเอกสาร" labels under the steps |
| deal-page-2.png | the "ขั้นต่อไป" card and its button |
| deal-page-3.png | the โทรแล้ว and นัดเข้าพบ buttons at top right |
| deal-page-4.png | the tab row ภาพรวม · ใบเสนอราคา · เงิน · ส่งของ · ไฟล์, with ภาพรวม open: ประวัติ on the left, ข้อมูลงาน on the right |
| deal-page-5.png | scrolled down the right column: พักงาน · ปิดงาน (เสียงาน) · ยกเลิกงาน under ข้อมูลงาน |

### deal-documents (sign in as ฝ่ายขาย, desktop; use the rep's own deals)
| file | deal state needed | how to get there | must be visible |
|---|---|---|---|
| deal-documents-1.png | a deal with a deposit-notice DRAFT | เงิน tab, click "ออกเอกสาร" | the confirm dialog: the warning, the summary line and the table ending "ยอดที่ลูกค้าต้องชำระ" |
| deal-documents-2.png | a deal ready for the remaining invoice | เงิน tab, click "สร้างใบแจ้งหนี้ส่วนที่เหลือ" (or "จัดการเอกสาร") | the dialog, the summary line "ส่วนที่เหลือ … = มูลค่า … − มัดจำ …" and the "บันทึกร่าง" / "ออกใบแจ้งหนี้" buttons |
| deal-documents-3.png | a deal with the deposit paid and its IR not yet issued | ส่งของ tab, click "ออกใบขอซื้อ (IR)" | the confirm dialog: the warning, "N โรงงาน · ส่งถึงฝ่ายนำเข้าทันที" and the factory table |
| deal-documents-4.png | a deal with delivery open | ส่งของ tab, click "บันทึกการส่งสินค้า" | the dialog: the สั่ง / ส่งแล้ว / เหลือส่ง / ส่งครั้งนี้ table, the "บันทึกแล้วแก้ไขหรือลบไม่ได้" warning and the S9 info line |

### deal-documents-ceo (sign in as CEO, desktop)
- deal-documents-ceo-1.png: the same dialog as deal-documents-3.
- deal-documents-ceo-2.png: the same dialog as deal-documents-4.

### quotation-list (sign in as ฝ่ายขาย, desktop; open `/quotations`)
| file | how to get there | must be visible |
|---|---|---|
| quotation-list-1.png | the page as it opens | the flat list, newest เลขที่ first, on the ทั้งหมด tab |
| quotation-list-2.png | click "จัดกลุ่มตามดีล" | the มุมมอง switch and the grouped rows |
| quotation-list-3.png | back to "เรียงตามเลขที่"; type a visible total (e.g. 5444.8) in the search box | the match |
| quotation-list-4.png | the list with at least one approved row | the "วันที่อนุมัติ" column at the far right |

### visits (sign in as ฝ่ายขาย, desktop; open `/visits`)
| file | state needed | must be visible |
|---|---|---|
| visits-1.png | — | the week bar "สัปดาห์นี้ · …" with "n จาก m ไปแล้ว", and the นัดไว้ (n) and ไปแล้ว (n) panels |
| visits-2.png | — | the header buttons นัดเข้าพบ and บันทึกเข้าพบ, or the PlanVisitModal open |
| visits-3.png | a visit row with ✕ and "เข้าพบแล้ว"; ideally an overdue one | the row, ideally with the "เลยกำหนด" chip |
| visits-4.png | at least one carried-over visit (if the mock has none, plan one dated last week and leave it unticked) | the "ค้างจากสัปดาห์ก่อน (n)" box |
| visits-5.png | — | the ‹ · สัปดาห์นี้ · › control, ideally on another week |

### visits-team (sign in as ผู้จัดการฝ่ายขาย or CEO, desktop; open `/visits`)
- visits-team-1.png: "ตารางเข้าพบสัปดาห์นี้": reps as rows, Mon–Fri as columns, chips in the cells, today's column tinted.
- visits-team-2.png: a close crop of the legend ไปแล้ว / นัดไว้ / เลยกำหนด / ยกเลิก and a few chips.
- visits-team-3.png: the ‹ สัปดาห์นี้ › control, ideally on another week.

### today-phone (sign in as ฝ่ายขาย, phone 375×812; open `/`)
| file | how to get there | must be visible |
|---|---|---|
| today-phone-1.png | the page as it opens | "วันนี้ของฉัน", the date, and the นัดถัดไป card |
| today-phone-2.png | same page | the large "บันทึกเข้าพบ" button; optionally the LogVisitModal open |
| today-phone-3.png | tap โทรแล้ว (optional) | the quick row โทรแล้ว / ทำใบเสนอราคา, or the "โทรหางานไหน" sheet |
| today-phone-4.png | needs an overdue visit | the "ค้าง n นัด" box |
| today-phone-5.png | scroll down | ควรตามต่อ (n) and เงินเข้าเดือนนี้ |
| today-phone-6.png | scroll to the bottom | ดูงานทั้งหมด · นัดเข้าพบทั้งสัปดาห์ · ดูหน้าเต็ม; crop out the "งานใหม่" tile if possible |

### finance-worklist (sign in as ฝ่ายบัญชี, desktop; open `/finance`)
- finance-worklist-1.png: the ต้องดำเนินการ tab, with the three groups and their counts.
- finance-worklist-2.png: a close crop of rows showing the "ยืนยันพร้อมปิดงาน" or "บันทึกใบกำกับ" button and a ติดตามชำระ row with "เปิด".
- finance-worklist-3.png: the ทั้งหมด tab, with the filters ขั้นการเงิน / ขั้นตอนที่ต้องทำ and "ค้างรับทั้งหมด".

### finance-worklist-ceo (sign in as CEO, desktop; open `/finance`)
- finance-worklist-ceo-1.png: the groups.
- finance-worklist-ceo-2.png: rows where every button reads "เปิด".
- finance-worklist-ceo-3.png: the ทั้งหมด tab.

## Files
- `frontend/src/features/whatsNew/releases/2026-11-17.js` (new)
- `frontend/src/features/whatsNew/releases/2026-11-17.test.js` (new)
- `frontend/src/features/whatsNew/releases/index.js`
- `frontend/src/features/whatsNew/releases/releases.test.js`
- `frontend/src/features/whatsNew/WhatsNewBubble.jsx`
- `frontend/src/features/whatsNew/WhatsNewBubble.test.jsx`
- `frontend/src/features/whatsNew/WhatsNewPage.jsx`
- `frontend/src/features/whatsNew/WhatsNewPage.test.jsx`
- `frontend/e2e-real/route-coverage.spec.js`
