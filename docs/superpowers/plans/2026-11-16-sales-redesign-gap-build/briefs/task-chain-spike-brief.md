# Brief — THROWAWAY prototype: document chain on the ใบเสนอราคา page

Repo: GL&R ERP. Work in your own git worktree cut from `origin/develop` (`git fetch origin develop` first), branch `spike/quotation-document-chain`, worktree `.claude/worktrees/chain-spike`. Frontend only, mock mode only. This is a SPIKE: its purpose is real before/after screenshots for the owner to approve a design. It will not be merged. So: no test-first requirement, no production polish, no backend, no PR, never push. You do not dispatch subagents. Do not run Maven.

## What the owner asked for
"In the ใบเสนอราคา page I also want them to be able to generate the deposit and notice PDF too, in a chain, in case it is easier for them." And: "show me before and after 100% representing the real UI."

Today a sales rep with an approved quotation presses "ยืนยันคำสั่งซื้อ" on the quotation page, then must leave for the deal page's เงิน tab to press "สร้างใบแจ้งยอดเงินรับมัดจำ", which opens a third page (`/tickets/:id/deposit`) where they press "ออกเอกสาร" and "ดาวน์โหลด PDF"; later "สร้างใบแจ้งหนี้ส่วนที่เหลือ" is back on the deal page. Three pages for what the rep thinks of as one job.

## The design the owner chose (build exactly this)
On the page of a saved, approved quotation (`frontend/src/features/quotations/QuotationEditorPage.jsx` in its read/view state; find where the header and the "ยืนยันคำสั่งซื้อ" button render), directly under the page header and above the document, a card titled **"เอกสารต่อจากใบเสนอราคานี้"** with four steps in order. On desktop the steps sit in a row with a connecting line; on a phone they stack vertically.

1. **ใบเสนอราคา** — the quotation number, "อนุมัติแล้ว" and the approval date, and a "ดาวน์โหลด PDF" action (the page's existing download).
2. **ยืนยันคำสั่งซื้อ** — before: the existing button, now living in the chain (it opens the existing confirm dialog unchanged). After: "ยืนยันแล้ว" with the date.
3. **ใบแจ้งยอดเงินรับมัดจำ** — locked ("ยืนยันคำสั่งซื้อก่อน") until step 2 is done. Then a "สร้างใบแจ้งยอดเงินรับมัดจำ" button that opens a DIALOG ON THIS PAGE showing the figures of the saved draft (item value, deposit %, deposit, VAT, total: reuse the summary component the deal page's deposit confirm already uses, `DocumentDialogParts.jsx` / `DealPaymentSection.jsx`), the "cannot be edited after issuing" warning first, a secondary link "แก้รายละเอียด" that goes to the existing full notice page, and the primary "ออกเอกสาร". After issuing: the notice number, "ออกแล้ว" + date, and "ดาวน์โหลด PDF". A quotation with deposit 0% shows this step as "ไม่รับมัดจำ" and skipped (muted, no button).
4. **ใบแจ้งหนี้ส่วนที่เหลือ** — locked with the true reason until today's rule allows it (read `DealPaymentSection.jsx` for the real condition and wording), then "สร้างใบแจ้งหนี้ส่วนที่เหลือ" opening the EXISTING `RemainingInvoiceDialog` from this page; after issuing: number, date, "ดาวน์โหลด PDF".

Rules:
- Exactly ONE primary button in the card at any time: the next step. Completed steps show a check and their download; future steps are muted with the reason they are locked.
- Every issuing action keeps its own confirm; nothing is issued in one click.
- Who can act does not change: reuse the same conditions the existing buttons use (owner/keyer sales rep creates and issues; read `DealPaymentSection.jsx`, `pricingRequestMeta.js` `canCreateDepositNoticeFromQuotation`, the quotation page's confirm-order condition). A viewer who cannot act sees the same card read-only: statuses and the downloads they are already allowed, no action button, and one muted line saying who does the next step.
- Use the existing design system only: Tailwind utilities and tokens from `frontend/src/index.css`, shared components (`Button`, `StatusBadge`, `Icon`, `Modal`/`ConfirmDialog`), the visual language of the deal page's `DealStepper.jsx` for the step markers so the two pages feel related. No new colours, no gradients, no emoji; SVG icons from the existing `Icon` set. Labels say "งาน" not "ดีล" where the word appears. Phone: no horizontal page scroll at 320–375px, tap targets at least 44px, the card must not push the document far down (keep it compact; completed steps collapse to one line each on a phone).
- On the ใบเสนอราคา LIST (`QuotationListPage.jsx`), each row gets one small status mark for where that quotation stands in the chain ("รอยืนยันคำสั่งซื้อ", "ยืนยันแล้ว · รอใบแจ้งมัดจำ", "ออกใบแจ้งมัดจำแล้ว", "ออกใบแจ้งหนี้แล้ว"), in the existing compact badge style, without adding a column if the row can carry it.

Because this is a spike on mock data: wire the card to whatever the mock already exposes; where the quotation's API response does not carry a needed fact (order confirmed? notice state? invoice state?), fetch it the way the deal page does, or, for the list mark only, derive it in the spike from mock data. Keep a short list of every piece of data the real build would need the SERVER to provide that it does not provide to this page today: that list is one of your deliverables.

## Screenshots (the actual deliverable)
Use headless Playwright against the app in mock mode (the repo has `@playwright/test`; Chromium is installed under `%LOCALAPPDATA%\ms-playwright`; do not download browsers). Start `VITE_USE_MOCKS=true npx vite --port 5281 --strictPort` in `frontend` (pick another free port if taken; not 5201, 5202, 5233, 5247, 5261, 5271, 5273, 5291), stop it afterwards. Keep scripts in `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\chain-spike\`, not in the repo. Mock facts: the login page has quick-login buttons (ฝ่ายขาย, ผู้จัดการฝ่ายขาย, CEO, ฝ่ายบัญชี, …); the mock session is lost on a full reload or `page.goto` after login, so navigate by clicking or with `history.pushState` + a `popstate` event; wait ~7 s after login for the toast to clear; set the viewport before loading. In this shell a bash heredoc containing an apostrophe breaks: write scripts with your file-writing tool.

**BEFORE** (take these FIRST, from an untouched checkout of `origin/develop` — before you change any file, or from a second clean worktree): the same quotation page and list in the same states, so each "after" has a true "before".

Save PNGs to `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\chain-spike\out\` named `<before|after>-<state>-<role>-<desktop|phone>.png`. Desktop 1440×900 (deviceScaleFactor 1); phone 375×812 (deviceScaleFactor 2, isMobile, hasTouch). Capture the top of the page down to and including the start of the document, so the card and its surroundings are visible (not just the card).

States to capture, as the owning sales rep unless stated:
- A. approved quotation, order NOT confirmed (primary = ยืนยันคำสั่งซื้อ)
- B. order confirmed, deposit notice not created (primary = สร้างใบแจ้งยอดเงินรับมัดจำ)
- C. the deposit notice dialog open on the quotation page (after only; before = the separate notice page a rep reaches today)
- D. deposit notice issued (download available), remaining invoice locked with its reason
- E. remaining invoice available / issued, whichever the mock can reach honestly
- F. a quotation with 0% deposit (step 3 skipped)
- G. state B or D as ผู้จัดการฝ่ายขาย and as ฝ่ายบัญชี (read-only card)
- H. the quotation list with the status marks (rep and manager)
Phone versions of A, B, C and D at least.

To reach states you may perform actions in the MOCK app (confirm an order, create and issue a mock deposit notice): mock state is in memory and nothing leaves this machine. Say which mock quotation/deal you used for each state. If a state cannot be reached honestly in mock mode, do not fake the screen: skip it and say why.

Open every PNG yourself and check it: right state and role, Thai renders, no toast or spinner, nothing clipped, the card reads clearly at phone width, no real-looking email address, phone number or bank account in frame (crop if the page chrome shows one).

## Report
Write the full report to `C:\Users\user\AppData\Local\Temp\claude\44a3f738c4644781\21665dad-d5d9-4895-9692-9b2bd2afbd34\scratchpad\task-chain-spike-report.md` and return at most 300 words: the list of screenshot files by state (before/after pairs), any state you could not reach and why, the data the server would need to provide for the real build, every place you had to make a design decision the brief did not settle (with what you chose), and anything about the design that felt wrong once you saw it rendered (too tall on a phone, a step that confuses, a locked reason that is unclear). Leave the worktree as it is, uncommitted; do not push.
