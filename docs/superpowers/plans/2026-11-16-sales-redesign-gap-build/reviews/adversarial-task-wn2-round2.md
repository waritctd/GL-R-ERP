# Round 2 review: 17 Nov "มีอะไรใหม่" release (HEAD 90423c5c)

## Verdict: ANOTHER ROUND NEEDED

One new defect needs a fix, and it is a text-only change (N1). Everything else holds.

**Branch state.** This branch equals `origin/main` plus the notice. `git diff --stat origin/main HEAD` touches only `features/whatsNew` and `public/whats-new`, and `origin/develop` matches `origin/main` apart from the notice. So every component I read is the code running in production.

**Clean-up.** `git status --short` was empty at the end. The scratch test was deleted and every mutation was reverted.

## Images: 48 of 48 opened, one at a time

None show an email address, phone number, bank account number or a real-looking name. The names that do appear are mock ones: คุณสมหมาย ขายดี, คุณอรุณี ขายเก่ง, คุณนำเข้า พานิช, คุณวิชัย ธนาคาร.

The four approver images (`quotation-list-approver-1..4`) are cropped below the header. "สร้างใบเสนอราคา" and the rep subtitle are out of frame, the พนักงานขาย and ยอดรวม filters are visible, and "ทั้งหมด" is the active tab, as step 1 says.

The CEO document images (`deal-documents-ceo-1/2`) show only the IR and delivery dialogs.

Six images have problems. All are low severity; none blocks the release.

| Image | Problem |
|---|---|
| menu-launcher-1.png | It shows a sales rep's panel (role badge "SALES", and the งานขาย section with 5 sales pages), but it goes to all 9 roles. Locked roles see different sections: employee, qc and warehouse see หน้าหลัก + บุคคลของฉัน, and account also sees การเงิน. The text ("เช่น…", "แต่ละคนเห็นเฉพาะ…") covers the difference. |
| menu-launcher-2.png | A tight crop of the last two items only. It has no avatar, so it does not show that this menu opens from the profile picture. It also leaves out ข้อมูลของฉัน and ค่าลดหย่อนภาษี (see N1). If it is re-cropped, keep `user.email` out of frame (`UserMenu.jsx:100`). |
| deal-page-4.png | Captured mid-scroll: the condensed sticky header covers the page. The link "ดูขั้นตอนทั้งหมด (15 ขั้น)" is clipped behind the tabs, and "15 ขั้น" sits beside a notice that says "แปดขั้น". This was the old F10 and has not changed. |
| visits-team-3.png | An empty week ("ไม่มีนัดเข้าพบในสัปดาห์นี้"). It is the same week 9–15 พ.ย. in which visits-5.png shows an overdue visit on 12 พ.ย., so the reader sees an empty grid. |
| finance-worklist-2.png | The crop drops the group names. The group header bands are blank grey. |
| finance-worklist-ceo-1.png | The last row is cut off mid-row. Cosmetic. |

Also noted, but not counted as failures:
- **all-deals-1.png** shows a "พักยาว 1" link. Step 3 lists only "พักไว้", "เสียงาน" and "ยกเลิก", so the text is incomplete rather than false.
- **today-phone-6.png** and **all-deals-1/2/4.png** still show "งานใหม่".

## Original findings

| Finding | Status | Detail |
|---|---|---|
| F1 | FIXED | The test now imports `Launcher.jsx?raw` (`2026-11-17.test.js:22`). The develop merge is in, and 76 of 76 tests pass. |
| F2 | FIXED | Step 2 is now image-less (`2026-11-17.js:148-151`), and the old image, with its ฿0 deposit, bank account and email, is gone. |
| F3 | FIXED | The entry is split into three: `:168` sales, `:184` approver, `:200` read-only view. The approver steps fit both sales_manager and ceo. Production gets [sales] / [sales_manager, ceo] / nobody; with the lock off, the read-only view goes to [account, import]. |
| F4 | FIXED (spot-checked) | Three mutations were all killed (see Tests). I did not re-run M17, M18 or M21. |
| F5 | FIXED | `:148` matches `RemainingInvoiceDialog.jsx:120-126` and `DealPaymentSection.jsx:445/451`. |
| F6 | FIXED | `:72` matches `QuietRule.java:13-19`. |
| F7 | FIXED | `:67`. |
| F8 | FIXED | All four points: `:229`, `:56`, `:101`, `:70`. |
| F9 | FIXED | The ดีล→งาน wording is scoped (`:98`), "ลดเหลือห้าแท็บ" (`:128`), and the email comment is corrected (`:25-28`). |
| F10 | PARTIAL | The bank/email image is gone. deal-page-4 and today-phone-6 are unchanged. |
| F11 | NOT FIXED | Out of scope. CLAUDE.md, `features.js:11` and the comment at `AppShell.jsx:232` still say the lock exempts only hr and ceo. The code (`permissions.js` `SELF_SERVICE_EXEMPT_ROLES`) exempts hr, ceo, sales and sales_manager. |

## New defect

**N1 (Medium; affects every role that has an employeeId).** The launcher move also took ค่าลดหย่อนภาษี out of the side menu and put it in the profile menu, and the notice never says so.
- **Before:** at e8beece3, `AppShell.jsx:249` listed `/tax-allowance` in the `self` group (บุคคลของฉัน).
- **Now:** it appears only in `UserMenu.jsx:116-127`, next to ข้อมูลของฉัน (`:103-114`).
- **What the notice says:** the why reads "ทุกหน้าที่คุณใช้ได้อยู่ในแผงเดียวแยกตามหมวด" (`2026-11-17.js:82`). That is false for both of these pages.
- **Who is hit hardest:** locked staff, for whom ค่าลดหย่อนภาษี is one of only about six pages. A locked employee will look in บุคคลของฉัน and not find it.
- **Fix:** drop "ทุกหน้า…" from the why. Make step 2 name "ข้อมูลของฉัน", "ค่าลดหย่อนภาษี" and "มีอะไรใหม่" in the profile menu. Optionally re-crop menu-launcher-2 to show the whole menu, with the email out of frame.

## Launcher entry and navigation steps

**Launcher entry (`menu-launcher`).**
- The "เมนู" button (`AppShell.jsx:369-388`) is identical on desktop and phone, top left, for every role. The panel closes on Esc or an outside click (`:293`, `:319`).
- Sections: หน้าหลัก, งานขาย, บุคคล, การเงิน, ทีมของฉัน, บุคคลของฉัน, ตั้งค่า (`Launcher.jsx:5-14`).
- "มีอะไรใหม่" is in the user menu unconditionally (`UserMenu.jsx:129-138`).
- With the lock on, each locked role still sees the sections the step names as examples: หน้าหลัก + บุคคลของฉัน for everyone, plus การเงิน for account. So step 1 is true for every role.

**Every "กด เมนู แล้วเลือก … ในหมวด …" step**, checked against `AppShell` `navItems` with the lock on:
- **งานทั้งหมด, ใบเสนอราคา, นัดเข้าพบ (งานขาย):** correct for sales, sales_manager and ceo, which are lock-exempt (`canViewDealPipeline`, `canViewDealQuotations`, `canViewVisits` in `api/routes.js:718/797/722`).
- **งานการเงิน (การเงิน):** correct for account (through `isReleasedPath`) and for ceo (`canConfirmPayments`).
- **ใบเสนอราคา for account and import:** present with the lock off and hidden with it on, which matches the entry being invisible to them on production.

## Image-less steps and the 16 Nov release

**Image-less steps.** `ChangeCard.jsx:89/101` drops the image column and the image when `step.image` is null, so these steps render cleanly.

**The "มีภาพประกอบ" pill wording** (`WhatsNewBubble.jsx` diff):
- It is shown when any step has an image.
- It is hidden for `quotation-list-view`.
- It is shown for `deal-documents` (3 of 4 steps have images).

**16 Nov release.**
- `git diff e8beece3 90423c5c` on its file and its image folder is empty.
- It has no `otherRolesNote`, so the page falls back to the identical sentence.
- All 31 of its images are still referenced through `shot()`.

## Publish payload on production (lock on)

- 13 changes. The release title is 75 characters; change titles are between 58 and 83 characters. All are within `ReleaseNoticePolicy` limits (150, 200 and 50 changes).
- `menu-launcher` goes to all 9 roles. The others go to: all-deals [sales]; all-deals-team [sales_manager, ceo]; deal-page [sales, sales_manager, ceo]; deal-documents [sales]; deal-documents-ceo [ceo]; quotation-list [sales]; quotation-list-approver [sales_manager, ceo]; visits [sales]; visits-team [sales_manager, ceo]; today-phone [sales]; finance-worklist [account]; finance-worklist-ceo [ceo].
- `quotation-list-view` is excluded, as intended. With the lock off there are 14 changes, and it goes to [account, import].
- Giving everyone a bell row is right: the launcher really did change for every role. Each role can act on its titles.

## Tests

- `npx vitest run src/features/whatsNew/`: 4 files, 76 of 76 pass.
- Mutations (each reverted):

| Mutation | Result |
|---|---|
| CEO told to press "ยืนยันพร้อมปิดงาน" | Killed by "no entry tells a role to use a control that role does not have" |
| Delivery "ไป S9" changed to "ไป S8" | Killed by "numbers and step codes come from their source constants" |
| visits-2.png deleted | Killed by "every referenced screenshot exists" |
