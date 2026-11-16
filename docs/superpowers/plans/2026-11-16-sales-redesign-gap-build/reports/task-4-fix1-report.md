# Task 4, fix round 1 report

**Status: DONE.** All nine rulings are in, and nothing outside them changed. The work is on top of 1511cbb8 in worktree `visits-today`. It is uncommitted.

## Each ruling
1. **Missed visits never vanish.**
   - The phone screen has a new "ค้าง n นัด" block (`today-missed`). It lists this week's unticked past visits plus those carried over from the 31 days before this Monday, which MyWork already loads.
   - Each one can be ticked or cancelled through the existing dialogs.
   - When nothing is coming up but some are missed, the next-visit card reads "ไม่มีนัดใหม่ใน 7 วันข้างหน้า · ค้าง n นัดด้านล่าง". With nothing missed it reads "ไม่มีนัดใน 7 วันข้างหน้า".
   - `/visits` shows the same carried-over list, but only on the current week.
2. **Failed load keeps the buttons.** "บันทึกเข้าพบ" and the quick-action row show even when `/my-work` fails. The error sits beside them with a "ลองใหม่" retry button.
3. **Dialogs survive rotation.** The call picker and call dialog now live in MyWork beside the other dialogs, so crossing 720px keeps them open with the chosen outcome and the typed note.
4. **"ดูหน้าเต็ม" is remembered.**
   - Stored in sessionStorage under `glr.myWork.phoneView`, with try/catch.
   - The full page now opens with "กลับไปหน้าวันนี้" as a full-width 52px button, the same size as "ดูหน้าเต็ม".
5. **Next visit looks past the week boundary.**
   - The card makes one extra request, `GET /api/sales/visits`, from today to today + 7 (8 days inclusive, under the endpoint's 31-day limit).
   - The request only runs at phone width.
   - That result is merged with this week's visits, and anything beyond today + 7 is ignored.
6. **"เงียบ N วัน" only when quiet.** The call dialog shows it only when the deal's `quiet` flag is true.
7. **Dialogs say งาน.**
   - In the shared dialogs:
     - the plan dialog's subtitle now says "แม้ยังไม่มีงาน";
     - the deal-picker label is "งาน", and its "none" option is "ไม่มีงาน";
     - both "บันทึกแล้ว…ออกจาก ควรตามต่อ" hints now say "งานนี้".
   - In MyWork's toasts: "งานนี้ย้ายไปให้", "งานนี้เป็นของ", "สร้างงานเรียบร้อย".
   - The "no ดีล" checks now open each dialog and check its text.
   - `TicketCreateModal` was not touched, because it is under `features/tickets/`.
8. **Cash line as drawn.** It now reads "รอบถัดไป 13 พ.ย.", using a new `weekEndDay` helper. `weekEndLabel` is rebuilt on top of it and its output is unchanged.
9. **Same-time tie-break.** Two visits at the same date and time: the lower id is next, matching the backend's order. Now pinned by a test.

## Tests and results
- **Red run:** 22 tests failed, each on missing behaviour. One failure was first a TypeError from the missing `weekEndDay` export; with a stub in place it failed as an assertion. Log: `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\t4-fix1-red.log`
- **Existing tests I changed:**
  - In `MyWork.visits.test.jsx`, five pinned ดีล strings now expect งาน (ruling 7).
  - In `MyWork.phone.test.jsx`:
    - the old "ไม่มีนัดที่เหลือสัปดาห์นี้" test was replaced to match ruling 5;
    - the cash strings changed to match ruling 8;
    - four quick-row lookups now wait for the data, because the row now appears before `/my-work` answers (ruling 2).
- **Final:** `npm run lint` exit 0; `npm test` 303 files and 6597 tests passed; `npm run build` exit 0.
- The stderr lines "rows.map" and "reading 'rep'" come from the CeoOverview and TeamMovementPanel tests. I did not touch those files, and those tests pass.

## Mutations
Log: `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\t4-fix1-mutations.log`. Every mutation was reverted afterwards.
- **Killed:**
  - the tie-break, which survived last round;
  - one or two mutations for each of rulings 1 to 8: missed block, carried-over on `/visits`, empty wording, log button waiting for data, retry, call dialog lost on rotation, picker lost on rotation, not remembered, small back button, storage error not caught, no look-ahead, look-ahead limit ignored, always "เงียบ", "ไม่มีดีล", "ดีลนี้" hint, "ถึง" kept.
  - My first storage mutation survived because it was a bad mutation. While fixing it I found the storage test itself proved nothing: Node's own storage shadows jsdom's, so the spy never fired. The test now swaps in a refusing storage with `vi.stubGlobal` and checks it was actually called. The mutation is now killed.
- **Survived:** the parked menu entry that ignores `SALES_ENABLED`, as expected.

## Not verified
I did not re-check these screens in a browser this round; the evidence for them is tests on mock data only.
