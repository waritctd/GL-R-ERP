## Fix round 1: all five rulings done, uncommitted, all 8 mutations killed

Worktree `all-deals-board` (on top of 47b6ebed). I didn't run Maven.

**1. Default chip.** It is now always "ทั้งหมด" (`AllDealsPage.jsx`); งานของฉัน still uses `defaultBoardFilter`. When a chip or search leaves no cards, the board shows "ไม่มีงานในตัวกรองนี้", or "ไม่พบงานที่ตรงกับ “q”" if the search matches nothing. One button, "แสดงงานทั้งหมด", resets to ทั้งหมด and clears the search. The subtitle counts the cards shown, so it can read 0.

**2. Release notice.** `releases/2026-11-16.js` and `releases.test.js` are back to exactly the develop base (`a46fe453`). No new release entry.

**3. Board fails soft.** The subtitle reads "โหลดงานไม่สำเร็จ" and the board shows an alert with "ลองอีกครั้ง" and "เปิดแบบตาราง".

**4. New tests** in `AllDealsPage.test.jsx`:
- account and import get the table only;
- subtitle shows other currencies alongside ฿;
- both empty states;
- the reviewer's all-other-quarters probe;
- quiet-chip count;
- error state, retry, and the way to the table.

**Quiet-chip count, my call:** n = quiet deals among the search results, across every quarter. That is exactly the cards pressing the chip shows: it replaces the quarter chip but keeps the search.

**5. Table wording.** Every user-visible "ดีล" in `TicketListPage.jsx` now says "งาน": scope copy, badges, column header, aria and sr-only labels, search, empty states, toast. A new test checks that no "ดีล" is left in visible text or aria/title/placeholder attributes (only deal data may contain it). I updated the tests that pinned the old strings.

**Red before green:** the board tests were 14 red and the wording tests 10 red. The other-currency and quiet-count tests passed straight away because the behaviour was already right; they exist to catch the mutations.

**Mutations** (scripted, file restored):

| Mutation | Result |
|---|---|
| F6d, quiet count over all deals | killed |
| F8c, drop other-currency totals | killed |
| F13b, remove role gate | killed |
| F17, remove error state | killed |
| F18, remove empty-search message | killed |
| Default back to ไตรมาสนี้ | killed |
| Empty state removed | killed |
| Subtitle stuck on loading | killed |

**Final:** `npm run lint` passed, `npm test` passed (299 files, 6466 tests), `npm run build` passed.

**Parked items:** I agree with all four.
