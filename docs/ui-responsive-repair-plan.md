# GL&R HR Portal UI Responsive Repair Plan

**Purpose:** Feed this entire Markdown file to Claude Code / Codex / your coding agent and have it run the UI repair **step by step overnight**, using **Opus as the screenshot-based senior reviewer/planner** and **Sonnet as the execution agent**.

**Primary objective:** Fix the GL&R HR Portal from a desktop-biased admin template into a **calm, reliable, efficient internal ERP operations cockpit** that works on both desktop and mobile.

**Critical priority:** Mobile is currently structurally broken. Do **mobile shell → mobile tables/cards → mobile forms → mobile spacing** first. Desktop polish comes after mobile is usable.


---

## Critical update — responsive fit is not enough

This repair must **not** be treated as “make the desktop fit inside mobile.” The goal is **real mobile usability for real GL&R users**.

A mobile screen is considered successful only if a real staff/CEO user can complete the actual task without guessing, pinching, horizontal scrolling, or hunting for the main action.

### Real-user mobile standard

For every mobile page, Impeccable and the coding agent must check these questions:

```text
1. What is the user trying to do on this page?
2. Is the primary action visible without excessive scrolling?
3. Can the user understand current status in under 5 seconds?
4. Can the user complete the task using one thumb?
5. Are dangerous actions clearly separated from normal actions?
6. Is the page showing the right information, or just all information?
7. Is the mobile version a task-focused experience, not a crushed desktop table?
8. Is the content order useful for real work?
9. Can a tired HR/admin user use this without making mistakes?
10. Would a CEO trust this screen before approving money, leave, OT, or quotation data?
```

### Mobile must support real tasks

The final UI must support these real mobile tasks:

```text
CEO / manager tasks:
- Open notification
- Review pending quotation / price request
- See customer, amount/status, owner, and next action quickly
- Approve / reject deliberately
- Review OT / leave approval status
- Check commission status and amount summary

HR/admin tasks:
- Search employee / attendance / leave / OT records
- Import attendance file without fighting the UI
- Upload certificate / tax / invoice / PO file
- See missing badge/card/punch problems quickly
- Submit OT / leave request from mobile

Sales / operation tasks:
- Search price request
- Open request detail
- See status and history
- Search product catalog
- Compare product price quickly
- Upload price list safely
```

### Usability laws and research principles to enforce

Impeccable should apply these principles during every UI change:

```text
Nielsen Norman usability heuristics:
- Visibility of system status: every page must show current state/status clearly.
- Match between system and real world: labels should use business language GL&R staff understand.
- User control and freedom: back, close drawer, cancel, and escape paths must be obvious.
- Consistency and standards: same badge/button/table/form patterns everywhere.
- Error prevention: risky approval/upload/finance actions need clear context before submit.
- Recognition rather than recall: users should not remember data from previous screens.
- Flexibility and efficiency: frequent tasks should be fast, not buried.
- Aesthetic and minimalist design: remove low-value mobile columns/details.

Fitts’s Law / touch ergonomics:
- Important buttons must be large enough and easy to hit.
- Related actions should be close to the content they affect.
- Destructive actions should not be too close to primary safe actions.

WCAG-oriented accessibility:
- Use at least 44px practical touch targets for this app, even though WCAG 2.2 AA minimum target size is lower in some cases.
- Preserve visible focus states.
- Do not rely on color alone for status.
- Maintain readable Thai typography and sufficient contrast.

Hick’s Law / decision simplicity:
- Do not show all desktop columns on mobile.
- Show the minimum fields needed to decide the next action.
- Secondary fields can move into detail pages.

Gestalt proximity/similarity:
- Group related metadata together in mobile cards.
- Keep status, owner, and date visually associated with the record.
```

### Reference-quality apps/design systems

Use these as UX references, not as visual skins to copy:

```text
Shopify Polaris / Shopify Admin:
- Good reference for operational admin tables, object lists, merchant actions, and dense business workflows.
- Use the concept: list items should help users analyze and take action.

GOV.UK Design System / Service Manual:
- Good reference for plain-language forms, accessibility, error prevention, and non-flashy public-service UX.
- Use the concept: boring but extremely usable beats pretty but confusing.

NHS / government service design systems:
- Good reference for staff-facing, high-trust, accessible workflows.

Material Design 3 navigation drawer:
- Good reference for mobile drawer behavior, app navigation, and destination switching.

Linear / Airtable / Notion admin-like workflows:
- Good reference for fast scanning, compact records, status clarity, and action-first productivity.
- Do not copy their aesthetic blindly; GL&R should stay calmer and more conservative.
```

### Tailwind migration emphasis

This repair should also move the UI away from scattered native CSS and toward a **Tailwind-first responsive system**.

This does **not** mean blindly converting everything overnight. It means:

```text
1. Tailwind utilities should become the default way to express layout, spacing, typography, responsive behavior, colors, and states.
2. Native CSS should be reduced and contained.
3. Keep global CSS only for:
   - Tailwind imports/layers
   - CSS variables/design tokens if already used
   - font setup
   - app reset/base styles
   - third-party overrides that cannot be done cleanly in Tailwind
   - rare animations/keyframes
4. Avoid random page-specific .css files for layout.
5. Avoid duplicated custom CSS that Tailwind utilities can express.
6. Prefer shared Tailwind-based components over one-off CSS hacks.
7. If @apply is used, keep it inside shared component classes only; do not create a second hidden design system.
8. Tailwind breakpoints must drive mobile/tablet/desktop behavior.
9. Remove dead CSS after migration, but carefully.
10. The migration must not break desktop or business workflows.
```

Impeccable must explicitly report:

```text
- Which native CSS files/classes still exist
- Which ones were migrated to Tailwind
- Which ones remain intentionally and why
- Whether any Tailwind config/design tokens were added or changed
- Whether responsive behavior is now controlled mostly by Tailwind classes/components
```


---

## Critical update — mandatory Opus review + Sonnet execution loop

This plan must be executed as a **review → implement → screenshot → review → fix loop**, not as a one-time code crawl.

### Required model roles

```text
Opus role:
- Senior product designer / UX reviewer / frontend architect.
- Must review actual screenshots and real user flows.
- Must think from the perspective of GL&R users: CEO, HR/admin, sales, import, and operations staff.
- Must decide whether the screen is genuinely usable, not merely responsive.
- Must identify priority problems, acceptance criteria, and exact implementation guidance.
- Must review before Sonnet starts each phase and after Sonnet finishes each phase.

Sonnet role:
- Implementation engineer.
- Must execute the Opus plan exactly and carefully.
- Must preserve business logic, APIs, auth, permissions, routes, calculations, and data shape.
- Must run lint/typecheck/build/tests after each phase.
- Must produce updated screenshots after implementation.
- Must not declare done until Opus reviews the screenshots and signs off or gives next fixes.
```

### Absolute rule: screenshots first, code second

Opus must **not** only inspect the codebase. It must review **actual rendered screenshots**.

For every major phase, the agent must capture or inspect screenshots at:

```text
- 320px
- 375px
- 390px
- 430px
- 768px
- desktop width
```

And for these key pages:

```text
- Login
- Dashboard
- My profile
- Price requests
- Ticket overview / Price request overview
- Price request detail
- Quotation approval detail
- CEO price config
- Product catalog
- Price import
- Commissions
- Attendance
- Overtime
- Leave
```

Screenshots can come from Playwright, browser devtools, local preview server, or any repo-supported visual testing tool. If screenshot automation does not exist, Sonnet should set it up only if safe and lightweight; otherwise it must document manual screenshot instructions and still review rendered pages in browser.

### What Opus must look for in screenshots

Opus must review each screenshot like a real user trying to complete a task:

```text
1. Can I immediately tell what page I am on?
2. Can I immediately tell what status this item/record is in?
3. Can I find the primary action without hunting?
4. Is the mobile screen task-focused, or is it just a squeezed desktop page?
5. Are the most important fields visible first?
6. Are any buttons too small, too close, or too risky?
7. Do status badges, dates, and numbers collide or clip?
8. Is there horizontal scroll?
9. Is Thai text readable and naturally grouped?
10. Would a tired HR/admin user make mistakes here?
11. Would a CEO trust this before approving money, OT, leave, or quotation data?
12. Does desktop still look professional after mobile fixes?
```

### Required execution loop for every phase

Every implementation phase must follow this loop:

```text
1. Opus Review Before Coding
   - Review current screenshots and relevant code.
   - Identify root UX problems from real screenshots.
   - Produce a short implementation plan for Sonnet.
   - Define concrete acceptance criteria for the phase.

2. Sonnet Execute
   - Implement only the agreed scope.
   - Prefer Tailwind-first shared components.
   - Preserve business logic and data flow.
   - Run lint/typecheck/build/tests.
   - Capture updated screenshots at required viewports.

3. Opus Review After Coding
   - Review the updated screenshots, not just the diff.
   - Check real-user task usability.
   - Compare against the phase acceptance criteria.
   - Mark phase as PASS or give targeted fixes.

4. Sonnet Fix Follow-up
   - If Opus finds issues, Sonnet fixes them.
   - Re-run validation and screenshots.
   - Repeat until Opus marks the phase PASS.

5. Commit Only After PASS
   - Commit the phase only after Opus signs off from screenshot review.
```

### Do not accept these false passes

```text
- “It builds, so it is done.”
- “It fits mobile width, so it is responsive.”
- “The code looks correct, so the UI is fine.”
- “The table scrolls horizontally, so mobile is solved.”
- “The desktop view is pretty, so mobile can be okay.”
- “The component is reusable, so the workflow is usable.”
```

A phase passes only when screenshots show that real users can complete the intended task clearly and safely.

### Copy/paste kickoff prompt for Claude Code

Use this before starting Step 1:

```text
You must run this repair using a strict Opus-review / Sonnet-execute loop.

Opus acts as the senior UX/UI reviewer and planner. Opus must inspect actual rendered screenshots at 320, 375, 390, 430, 768, and desktop widths. Opus must think from the perspective of real GL&R users: CEO, HR/admin, sales, import, and operations staff. Opus must not approve a phase from code review alone.

Sonnet acts as the implementation engineer. Sonnet executes Opus's plan, preserves business logic, uses Tailwind-first shared components, validates with lint/typecheck/build/tests, and captures updated screenshots.

For every phase:
1. Opus reviews current screenshots and writes the implementation plan.
2. Sonnet implements that plan.
3. Sonnet runs validation and captures updated screenshots.
4. Opus reviews the updated screenshots from a real-user perspective.
5. Sonnet fixes issues until Opus marks the phase PASS.
6. Only then commit the phase.

Do not declare done because the code builds. Do not declare done because the layout fits the viewport. The UI must be actually usable for real GL&R tasks.
```

---

## 0. Non-negotiable guardrails for the agent

Before making any changes, the agent must follow these rules:

1. **Do not change business logic** unless a UI bug absolutely requires it and the change is explicitly documented.
2. **Do not change backend logic, database schema, API contracts, auth, permissions, calculations, or route structure.**
3. **Do not invent fake data.** Use existing data only. If no data exists, show proper empty states.
4. **Do not rewrite the app.** Prefer shared components and small scoped refactors.
5. **Do not start desktop polish before mobile P0 is fixed.**
6. **Do not merge to main unless validation passes.**
7. **Do not force-push main.** Work on a branch, push the branch, then merge only after careful validation.
8. **Keep Thai as primary language.** English helper labels should be smaller and muted.
9. **Preserve existing workflows.** Buttons, routes, approvals, uploads, filtering, sorting, pagination, and permissions must still work.
10. **Impeccable should act as a design-quality reviewer and implementation guide**, not as a permission to do random redesigns.
11. **Mobile must be genuinely usable, not merely responsive.** Do not accept “it fits” if the user cannot complete the task easily.
12. **Use task-first mobile design.** Convert dense desktop views into mobile workflows/cards.
13. **Do not preserve bad desktop structure on mobile.** Mobile can show fewer fields if that improves task completion.
14. **Use Tailwind-first styling.** Prefer Tailwind utilities and shared Tailwind-based components over scattered native CSS.
15. **Do not create new page-specific CSS unless absolutely necessary.** If necessary, document why Tailwind cannot handle it cleanly.
16. **Do not use Tailwind as random class soup.** Reuse components and tokens for consistent spacing, colors, badges, buttons, forms, and cards.
17. **Do not let Impeccable over-beautify the system.** The target is operational clarity, not Dribbble shots.
18. **Do not accept mobile card views that hide critical decision data.** They must show the minimum information needed for real action.
19. **Do not accept tiny action icons on mobile.** Touch targets should be at least 44px in practical app usage.
20. **Do not merge if mobile task smoke tests fail**, even if lint/build pass.
21. **Opus must review actual screenshots before and after every phase.** Code crawling alone is not enough.
22. **Sonnet must execute the Opus plan and then return screenshots/build results for Opus review.**
23. **Continue the Opus→Sonnet loop until the phase passes**, not until the first implementation attempt finishes.
24. **Do not commit a phase until screenshot-based Opus review passes.**
25. **Do not push or merge until final Opus review checks actual screenshots, PR diff, validation results, and real-user task usability.**

Desired product personality:

```text
Calm. Reliable. Efficient.
```

Creative north star:

```text
A calm back-office operations cockpit for GL&R staff and CEO users.
Not a flashy SaaS dashboard.
Not a raw database viewer.
Not a purple-heavy AI-generated admin template.
```

Anti-references:

```text
- Generic SaaS admin template
- Raw database viewer
- Purple-heavy AI-generated dashboard
- Decorative icon-card soup
- Native unfinished HTML file inputs
- Over-rounded bubbly UI
- Flashy startup landing page
- Desktop table squeezed into mobile
```

---

## 1. Setup and baseline

Run this first from repo root.

```bash
git status
npm install
npm run lint
npm run typecheck
npm run build
```

If any baseline command already fails **before changes**, document it clearly. Do not hide baseline failures.

Create a repair branch:

```bash
git checkout -b ui-responsive-repair
```

Install/setup Impeccable if not already installed:

```bash
npx impeccable install
```

Then initialize it in the coding agent:

```text
/impeccable init
```

Baseline screenshots to capture or inspect:

```text
Viewports:
- 320px
- 375px
- 390px
- 430px
- 768px
- desktop width

Pages:
- Login
- Dashboard
- My profile
- Price requests
- Ticket overview / Price request overview
- Price request detail
- Quotation approval detail
- CEO price config
- Product catalog
- Price import
- Commissions
- Attendance
- Overtime
- Leave
```

If Playwright or another screenshot tool exists, use it. Otherwise use browser devtools screenshots manually.

**Important:** These screenshots are not optional. They are the primary review material for Opus. The codebase/diff can explain why something happens, but screenshots decide whether the UI is usable.

---

## 1.5 Tailwind/native CSS baseline audit

Before making UI changes, inspect how styling is currently done. This is an audit step first.

### What Impeccable should do

Impeccable should inspect:

```text
- Tailwind config files
- global CSS files
- page-specific CSS files
- CSS modules
- component-level CSS
- inline style usage
- hardcoded widths/heights/min-widths
- hardcoded breakpoints
- native file input styling
- duplicated color/spacing values
- places where CSS causes mobile overflow
```

### Prompt for Claude / Impeccable

```text
/impeccable

Audit the current styling architecture before editing.

Goal:
Move GL&R HR Portal toward a Tailwind-first responsive UI system while preserving business logic and existing workflows.

Do not edit files yet.

Inspect:
1. Tailwind setup and config.
2. Global CSS files.
3. Page-specific native CSS files.
4. CSS modules or scoped CSS.
5. Inline styles.
6. Hardcoded widths, min-widths, heights, and layout constants.
7. CSS that causes horizontal overflow on mobile.
8. Repeated colors, spacing, borders, shadows, radius, badge styles, button styles, table styles, and form styles.
9. Components already using Tailwind well.
10. Components that should be migrated to Tailwind/shared components.

Deliver:
- Styling architecture summary.
- List of native CSS files/classes to migrate.
- List of native CSS that should remain intentionally.
- Tailwind config/token recommendations.
- Highest-risk CSS causing mobile breakage.
- Recommended migration order.

Do not change code in this audit step.
```

### Styling migration rule

After this audit, all UI repair steps should prefer Tailwind utilities and shared Tailwind-based components. Native CSS should only remain when justified.

---

## 2. Global acceptance criteria

At the end of the whole repair, the portal must satisfy:

```text
1. No horizontal scroll at 320px and above.
2. Header never overlaps content.
3. Sidebar drawer is readable, left-aligned, and closes correctly.
4. Tables become cards on mobile.
5. Status badges never clip.
6. Dates never overlap status badges.
7. Forms are single-column on mobile.
8. File uploads are custom-looking, not raw native Choose File UI.
9. Primary actions are reachable and obvious.
10. Touch targets are at least 44px.
11. Thai text is readable.
12. Desktop layout remains stable and professional.
13. Approval and finance screens feel serious and trustworthy.
14. No business workflow is broken.
15. lint/typecheck/build pass.
```

### Real-user task acceptance criteria

The UI must pass real task checks, not just viewport checks:

```text
[ ] CEO can open a pending quotation/approval on mobile and understand what needs approval.
[ ] CEO can approve/reject without hunting for the action.
[ ] HR can search attendance records and identify missing/abnormal data.
[ ] HR can import a .dat attendance file from mobile/tablet layout without broken file input UI.
[ ] HR can submit leave/OT request from mobile without field overflow.
[ ] User can search product catalog and understand price/result cards.
[ ] User can open price request detail and understand status/history/next action.
[ ] User never has to horizontally scroll to complete primary mobile tasks.
[ ] User never has to remember information from one screen to decide on the next screen.
[ ] User can recover from mistakes: close drawer, go back, cancel, edit, or correct form input.
```

---

## 3. Priority order overview

Run the steps in this exact order:

```text
P0 — Emergency mobile repair
0.5 Tailwind/native CSS baseline audit
1. Mobile shell + global overflow
2. Mobile sidebar drawer
3. Mobile tables → cards
4. Mobile forms + file uploads
5. Mobile spacing/density
5.5 Tailwind-first CSS migration and containment

P1 — Shared system and desktop usability
6. Shared responsive UI system
7. Desktop tables
8. Dashboard action hierarchy
9. Detail page action hierarchy
10. Approval and finance trust

P2 — Domain-specific polish and QA
11. Product catalog + price import
12. HR operation screens
13. Final responsive QA
14. Push branch and merge to main only after careful validation
```

After each step:

```bash
npm run lint
npm run typecheck
npm run build
git diff --stat
git status
```

Then commit that step before moving on.

---

# P0 — Emergency mobile repair

## P0 operating principle: usable mobile, not resized desktop

For every P0 step, Impeccable must judge the result by task completion:

```text
Bad outcome:
- Desktop table technically fits but user cannot scan it.
- Drawer opens but menu is centered/underlined/unreadable.
- Form fits but takes forever to complete.
- Card shows all fields but hides the status/action.
- Button is visible but too small or far away.

Good outcome:
- User sees status and next action immediately.
- Mobile cards show fewer, better fields.
- Approval/upload/search actions are obvious.
- No horizontal scroll.
- No pinch/zoom needed.
- Thai labels are readable.
- Touch targets are comfortable.
```

Do not let Impeccable stop at “viewport fits.” It must verify “real user can complete the workflow.”


---

## Step 1 — Fix mobile shell and global overflow

### Why this is first

The root failure is that the desktop layout is being forced into mobile. Before fixing individual pages, the global app shell must stop causing overflow.

### What Impeccable should do

Impeccable should inspect and repair:

- `html`, `body`, app root, layout wrapper
- main content container
- page container widths
- fixed widths / min-widths
- mobile header
- mobile sidebar drawer
- horizontal overflow sources
- responsive spacing tokens/classes

It should **not** touch business logic.

### Prompt for Claude / Impeccable

```text
/impeccable

Fix P0 mobile shell and global overflow for GL&R HR Portal.

Do not change business logic, routes, auth, APIs, permissions, database logic, calculations, or data fetching.

Context:
This is an internal ERP / HR / quotation / attendance / leave / commission system.
Design personality: Calm. Reliable. Efficient.
Mobile goal: staff and CEO can quickly check, approve, search, upload, and submit without horizontal scrolling or broken layouts.

Problems from screenshots:
- Mobile has horizontal overflow.
- Desktop layout is being squeezed into mobile.
- Header buttons are oversized.
- Sidebar drawer is too wide, centered, underlined, and unreadable.
- Content can feel hidden under or crowded by the header.
- Page padding and container widths look desktop-biased.

Fix:
1. Ensure html, body, app root, layout wrapper, and page containers never exceed viewport width.
2. Remove or override fixed widths/min-widths that break mobile.
3. Use w-full, max-w-full, min-w-0, overflow-x-hidden, and responsive containers appropriately.
4. Mobile page padding should be around 16px.
5. Tablet page padding should be around 24px.
6. Desktop can keep current spacing if stable.
7. Header should be compact: about 64–72px height on mobile.
8. Hamburger should be left.
9. App title should be compact: “GL&R HR”, with role underneath only if space allows.
10. Right actions should be compact and tappable.
11. Hide or simplify the avatar placeholder on mobile. The current mint square with “-” looks like missing data.
12. Notification/logout buttons should be smaller but still at least 44px tappable.
13. Sidebar drawer should be 280–320px wide, not most of the screen.
14. Sidebar items must be left-aligned, not centered.
15. Remove underline styling from nav labels.
16. Thai label primary, English helper smaller and muted.
17. Active nav item should be obvious but calm.
18. Drawer should close after navigation.
19. Background overlay should not visually fight the page.

Acceptance:
- No horizontal scroll at 320, 375, 390, 430px.
- Header fits cleanly in one row.
- Sidebar is readable and left-aligned.
- Content starts below header and does not hide underneath it.
- Desktop layout remains visually close to current design.

After changes:
- Run lint/typecheck/build.
- Report changed files and remaining mobile risks.
```

### Validation after Step 1

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual/browser checks:

```text
- 320px: no horizontal scroll
- 375px: no horizontal scroll
- 390px: no horizontal scroll
- 430px: no horizontal scroll
- Header does not wrap badly
- Sidebar drawer width is reasonable
- Sidebar menu is left-aligned
- Nav labels are not underlined
- Desktop shell still looks okay
```

Commit:

```bash
git add .
git commit -m "fix mobile shell and global overflow"
```

---

## Step 2 — Convert mobile tables into cards

### Why this is second

Most mobile screenshots are broken because desktop tables are squeezed into small screens. This causes clipped text, overlapping badges, unreadable dates, and horizontal scroll.

### What Impeccable should do

Impeccable should create or reuse a responsive table pattern:

```text
Desktop/tablet: normal table
Mobile: stacked record cards
```

It must keep existing data fetching, filtering, sorting, pagination, and row click behavior.

### Affected pages

```text
- Price requests / ใบขอราคา
- Ticket overview / ภาพรวมใบขอราคา
- Product catalog / แคตตาล็อกสินค้า
- Price import product list / รายการสินค้า ACTIVE
- Attendance / เวลาทำงาน
- Overtime / ล่วงเวลา
- Leave / วันลา
- Commission records / ค่าคอมมิชชั่น
```

### Mobile card content rules

Each mobile card must show only the most important fields.

Price request card:

```text
PR-2026-0001
บริษัท แกรนด์วิลเลจ จำกัด
[รอรับเรื่องจาก Import]
ธนพร ขายดี · 15 ก.ค. 2569
```

Ticket overview card:

```text
UAT-TKT-07
หจก. อีสเทิร์นวิลล่า
[ออกใบเสนอราคาแล้ว]
กฤษณะ เซลล์แมน · 15 ก.ค. 2569
```

Product card:

```text
Bode / BWH012KCA9
Atlas · MATT · 200x1200
9.00 USD / ม²
[แก้ไข]
```

Attendance card:

```text
วิชัย เจริญสุข
5 ก.ค. 2569 01:02
GLR-0011 · SHOWROOM
พนักงานฝ่ายผลิต
```

Overtime card:

```text
สมชาย บริหารกิจ
19/07/2026 18:00–20:00
วันทำงานปกติ · 1.5x
[รอผู้จัดการ]
[อนุมัติ] [ไม่อนุมัติ]
```

Leave card:

```text
สมชาย บริหารกิจ
ลาพักร้อน · 16/07/2026–16/07/2026
คงเหลือ 6 วัน
[รออนุมัติ]
```

Commission card:

```text
UAT-INV-0001
ธนพร ขายดี
ยอดจริง ฿240,000 · ฐานคำนวณ ฿240,000
[อนุมัติแล้ว]
```

### Prompt for Claude / Impeccable

```text
/impeccable

Fix P0 mobile tables by creating responsive table/card patterns.

Do not change data fetching, sorting, filtering, pagination, API calls, route behavior, permissions, or business logic.

Problem:
Desktop tables are being squeezed into mobile width. This causes clipped text, overlapping status badges, unreadable dates, and horizontal overflow.

Create a reusable responsive pattern:
- Desktop/tablet: keep proper table layout.
- Mobile: render records as stacked cards.

Mobile card requirements:
1. Each record card has a clear primary line.
2. Secondary metadata appears underneath in quieter text.
3. Status badge appears near the top and is never clipped.
4. Date is readable and never collides with status.
5. Main action is visible and obvious.
6. Hide low-value columns on mobile.
7. Cards should be tappable/clickable if desktop row is clickable.
8. No horizontal scrolling on mobile.
9. Compact spacing suitable for operations work.
10. Preserve desktop table behavior.

Apply first to:
- Price requests
- Ticket overview
- Product catalog
- Price import product list
- Attendance
- Overtime
- Leave
- Commission records

Suggested mobile card content:

Price request card:
- Request ID
- Company/customer
- Status badge
- Sales/owner
- Created date
- Optional next action

Product card:
- Product name/code
- Collection/factory
- Size/surface/color
- Price
- Edit action

Attendance card:
- Employee name
- Punch time
- Employee code
- Device/source
- Position

OT card:
- Employee name/date
- OT time range
- Reason
- Payable time
- Approval status
- Approve/reject actions if available

Leave card:
- Employee name
- Leave type
- Date range
- Status
- Attachment indicator if available

Commission card:
- Invoice ID
- Sales person
- Amount/base amount
- Status
- Edit/view action

Acceptance:
- At 320/375/390/430px, no table should be squeezed with 5+ columns.
- No status badge clips.
- No date/status collision.
- Text truncation is intentional and readable.
- Desktop tables still work.
- Filtering/sorting/pagination still work.

After changes:
- Run lint/typecheck/build.
- Report changed files and any behavior risks.
```

### Validation after Step 2

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
At 320/375/390/430px:
- Price requests render as cards
- Product catalog renders as cards
- Attendance renders as cards
- OT renders as cards
- Leave renders as cards
- No badge is clipped
- No date overlaps with badge
- No horizontal scroll

At desktop:
- Tables still render as tables
- Filters still work
- Row actions still work
```

Commit:

```bash
git add .
git commit -m "convert mobile tables to responsive cards"
```

---

## Step 3 — Fix mobile forms and file uploads

### Why this is third

Forms currently look desktop-like on mobile and native file inputs make the app feel unfinished. Finance and HR forms need to feel safe and deliberate.

### What Impeccable should do

Impeccable should create or update shared form primitives:

```text
- FormSection
- FormField
- FormRow
- SelectField
- DateField
- TextareaField
- FileUploadField
- SubmitActionBar
```

It must preserve actual submitted values and existing validation behavior.

### Affected pages

```text
- Commission form
- Attendance import
- Price import
- Overtime request
- Leave request
- Quotation / PO upload areas
```

### Prompt for Claude / Impeccable

```text
/impeccable

Fix P0 mobile form UX across GL&R HR Portal.

Do not change business logic, validation logic, API contracts, submitted data shape, calculations, auth, permissions, or backend behavior.

Problems:
- Forms are desktop-like on mobile.
- Inputs are too tall and spaced badly.
- Native file inputs look unfinished.
- Default 0 values feel unsafe.
- Submit buttons are not optimized for mobile.
- Some fields overflow or feel cramped.

Create or improve shared mobile-friendly form components:
- Form section
- Form row
- Form field
- Select field
- Date/time field
- Textarea
- File upload field
- Submit action bar

Fix:
1. Mobile forms must be single-column.
2. Inputs must be full-width and never overflow.
3. Labels should be readable but compact.
4. Required fields should be visually obvious.
5. Help text should be smaller and muted.
6. Submit buttons should be full-width or clearly aligned on mobile.
7. Dangerous actions should remain visually serious.
8. Replace native-looking file input with custom upload field while preserving actual input behavior.
9. Show accepted file types where relevant:
   - PDF/JPG/PNG for proof, PO, invoice, or tax files
   - Excel/CSV for price import
   - .dat for attendance import
10. Show selected filename clearly.
11. Avoid default “0” unless it is truly saved data. Use placeholder/empty state where appropriate.
12. Date/time inputs must not overflow.
13. Textareas should not be comically tall on mobile unless content requires it.
14. Primary submit action should be close enough to the form and easy to tap.

Apply to:
- Commission form
- Attendance import
- Price import
- Overtime request
- Leave request
- Quotation / PO upload areas

Acceptance:
- No form field overflows at 320px.
- File upload looks intentional, not raw “Choose File”.
- Forms are usable with one thumb.
- Buttons are easy to tap.
- Desktop form layout remains acceptable.
- Existing form submission behavior is preserved.

After changes:
- Run lint/typecheck/build.
- Report changed files and any validation behavior risks.
```

### Validation after Step 3

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
At 320/375/390/430px:
- Commission form is single-column
- Attendance import is single-column
- Price import is single-column
- OT request is single-column
- Leave request is single-column
- File upload is custom-looking
- Submit buttons are easy to tap
- No input overflows

At desktop:
- Existing layout still usable
- Form submit behavior remains unchanged
```

Commit:

```bash
git add .
git commit -m "fix mobile form layouts and file uploads"
```

---

## Step 4 — Fix mobile spacing, density, and typography

### Why this is fourth

Once shell, tables, and forms are structurally fixed, reduce the giant mobile spacing. Current mobile cards feel like desktop spacing scaled down badly.

### What Impeccable should do

Impeccable should tune responsive spacing and typography without redesigning desktop.

### Prompt for Claude / Impeccable

```text
/impeccable

Fix P0 mobile spacing, typography, and density.

Do not redesign desktop. Do not change business logic, data fetching, APIs, permissions, routes, or calculations.

Problems:
- Mobile cards are too large.
- Page titles are oversized.
- Profile card wastes too much vertical space.
- Dashboard metric cards are too tall.
- Detail pages have huge blank sections.
- Important actions require too much scrolling.
- Empty states waste space.

Mobile rules:
1. Page horizontal padding should be around 16px.
2. Card padding should be around 16–20px.
3. Page titles should be smaller than desktop.
4. Section headings should be compact.
5. Metric/stat cards should be shorter and more scannable.
6. Avoid giant icon tiles on mobile.
7. Use one-column cards on mobile.
8. Empty states should be compact and intentional.
9. Reduce excessive vertical gaps.
10. Touch targets must remain at least 44px.
11. Important actions should appear within reasonable scroll distance.
12. Detail pages should show summary first, then actions, then details/history.

Prioritize:
- Dashboard
- My profile
- Price request detail
- Quotation detail
- Product catalog
- Attendance
- Overtime
- Leave
- Commission

Acceptance:
- Mobile feels like an operations app, not a zoomed desktop page.
- No excessive blank cards.
- Important actions appear early.
- Thai text is readable.
- Desktop remains visually stable.

After changes:
- Run lint/typecheck/build.
- Report changed files and remaining density issues.
```

### Validation after Step 4

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
- Profile page no longer has huge wasteful card height
- Dashboard cards are compact
- Detail page actions are not buried
- Empty states look intentional
- Typography is readable, not huge
- No horizontal scroll
```

Commit:

```bash
git add .
git commit -m "improve mobile spacing and page density"
```

---

## Step 4.5 — Tailwind-first CSS migration and containment

### Why this happens after P0 mobile fixes

Do not start by rewriting all CSS while the mobile UI is broken. First stop the bleeding: shell, tables, forms, spacing. After P0 is usable, migrate styling toward a Tailwind-first system so the fixes do not become scattered CSS hacks.

### Goal

Move from native/scattered CSS to a maintainable Tailwind-first responsive UI system.

This step should **not** be a blind rewrite. It should be a careful containment/migration pass.

### What Impeccable should do

Impeccable should:

```text
1. Review the Tailwind/native CSS baseline audit from Step 1.5.
2. Identify native CSS that duplicates Tailwind utilities.
3. Migrate layout, spacing, typography, borders, shadows, colors, and responsive behavior into Tailwind utilities or shared Tailwind-based components.
4. Keep only intentional native CSS:
   - Tailwind base/import/layers
   - font setup
   - CSS variables/design tokens if already used
   - app reset/base styles
   - third-party overrides
   - rare animations/keyframes
5. Remove dead CSS carefully.
6. Avoid creating new page-specific CSS.
7. Do not use @apply everywhere. Use @apply only for shared semantic component classes when it genuinely reduces repetition.
8. Ensure responsive behavior is controlled through Tailwind breakpoints.
9. Ensure mobile fixes remain intact.
10. Ensure desktop remains stable.
```

### Prompt for Claude / Impeccable

```text
/impeccable

Perform a Tailwind-first CSS migration and containment pass.

Do not change business logic, backend logic, database logic, API contracts, auth, permissions, calculations, routes, or data fetching.

Goal:
Move GL&R HR Portal away from scattered native CSS and toward a Tailwind-first responsive design system.

Important:
This is not a visual redesign. This is a maintainability and consistency pass after the P0 mobile fixes.

Rules:
1. Tailwind utilities should be the default for layout, spacing, typography, color, border, shadow, radius, state, and responsive behavior.
2. Prefer shared Tailwind-based components over page-specific CSS.
3. Remove duplicated native CSS only when replacement is safe and verified.
4. Keep global/native CSS only for:
   - Tailwind imports/layers
   - base reset
   - font setup
   - CSS variables/design tokens if needed
   - third-party overrides
   - rare keyframes/animations
5. Avoid new page-specific .css files.
6. Avoid inline styles except truly dynamic values that cannot be expressed safely in Tailwind.
7. Avoid @apply except in shared component-level classes.
8. Do not create a second hidden design system inside CSS.
9. Tailwind breakpoints should control mobile/tablet/desktop layout.
10. Preserve all existing mobile P0 improvements.

Migration targets:
- App shell/header/sidebar layout styles
- Page container spacing
- Cards
- Tables and mobile record cards
- Forms
- File upload field
- Buttons
- Status badges
- Empty states
- Detail action panels

Deliver:
- List of CSS files/classes migrated.
- List of CSS files/classes intentionally kept with reasons.
- List of Tailwind/shared components updated.
- Any Tailwind config changes.
- Confirmation that mobile acceptance criteria still pass.
- Confirmation that desktop still passes.

Acceptance:
- No horizontal scroll regression.
- Mobile cards/forms still usable.
- Desktop remains stable.
- Native CSS surface area is reduced or clearly contained.
- lint/typecheck/build pass.

After changes:
- Run lint/typecheck/build.
- Report changed files and remaining CSS risks.
```

### Validation after Step 4.5

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
- Mobile shell still works
- Mobile cards still work
- Mobile forms still work
- File upload still works
- Desktop tables still work
- No new horizontal scroll
- Native CSS has been reduced or justified
- Tailwind responsive classes are now the main layout mechanism
```

Commit:

```bash
git add .
git commit -m "migrate styling toward Tailwind first"
```

---

# P1 — Shared UI system and desktop usability

---

## Step 5 — Create shared responsive UI system

### Why this comes after mobile P0

Once mobile is no longer broken, consolidate the fixes into a consistent shared design system so future pages do not regress.

### What Impeccable should do

Impeccable should create or refine shared components/tokens and ensure pages use them gradually.

### Required shared components

```text
PageHeader
SectionCard
MetricCard
StatusBadge
DataTable
MobileRecordCard
EmptyState
FormField
FileUploadField
ActionPanel
```

### Status color governance

Use this system everywhere:

```text
Gray   = draft / inactive / no data
Blue   = in progress
Yellow = waiting / pending someone
Green  = approved / completed
Red    = rejected / cancelled / error
Purple = primary action only, not status
```

### Prompt for Claude / Impeccable

```text
/impeccable

Create a shared responsive UI system for GL&R HR Portal.

Do not change business logic, routes, API calls, database logic, auth, permissions, or data shape.

Goal:
Make the app feel Calm, Reliable, Efficient across desktop and mobile.

Create or improve shared components:
1. PageHeader
2. SectionCard
3. MetricCard
4. StatusBadge
5. DataTable
6. MobileRecordCard
7. EmptyState
8. FormField
9. FileUploadField
10. ActionPanel

Design rules:
- Thai is primary.
- English helper text is smaller and muted.
- Purple is for primary actions only.
- No random badge colors.
- No generic SaaS decoration.
- No over-rounded bubbly cards.
- No native-looking file inputs.
- No decorative icon-card soup.
- Keep the app calm but more operationally clear.

Status rules:
- Gray = draft / inactive / no data
- Blue = in progress
- Yellow = waiting / pending
- Green = approved / completed
- Red = rejected / cancelled / error
- Purple = primary action only, not status

Implementation requirements:
1. Prefer shared components over page-specific hacks.
2. Refactor only enough existing pages to use the shared system safely.
3. Keep desktop and mobile stable.
4. Do not attempt a full rewrite.
5. Preserve all existing workflows.

Acceptance:
- Components are reusable.
- Existing pages adopt them gradually.
- Status badge logic is centralized or consistently mapped.
- Desktop and mobile both remain stable.

After changes:
- Run lint/typecheck/build.
- Report changed files and remaining inconsistent components.
```

### Validation after Step 5

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
- Shared components exist
- No obvious visual regression
- Status colors are more consistent
- Mobile still works
- Desktop still works
```

Commit:

```bash
git add .
git commit -m "add shared responsive UI system"
```

---

## Step 6 — Improve desktop table hierarchy

### Why this matters

Desktop tables were readable but felt like database output. ERP tables should support fast operational scanning.

### What Impeccable should do

Impeccable should improve desktop tables while preserving mobile cards.

### Prompt for Claude / Impeccable

```text
/impeccable

Improve desktop table UX across GL&R HR Portal.

Do not change data fetching, sorting, filtering, pagination, API calls, permissions, or business logic.

Problems:
Tables are readable but feel like raw database output. Users need to scan work queues quickly.

Fix:
1. Make the primary entity visually stronger:
   - company name
   - request ID
   - invoice number
   - employee name
   - product code/name depending on page
2. Put secondary metadata underneath in muted text.
3. Right-align money and numeric values.
4. Use consistent date formatting.
5. Add clear row hover state.
6. Make row action obvious and consistent.
7. Status badges must follow global badge rules.
8. Avoid noisy “-” values. Use muted empty text only where helpful.
9. Sticky headers for long tables if technically simple and safe.
10. Keep mobile card views intact.
11. Do not remove useful columns on desktop unless they are genuinely redundant.

Prioritize:
- Price requests
- Ticket overview
- Product catalog
- Price import
- Commission records
- Attendance
- Overtime
- Leave

Acceptance:
- Desktop tables feel like operational work queues, not raw SQL output.
- Money/numbers align correctly.
- Status is scannable.
- Mobile card views are unaffected.

After changes:
- Run lint/typecheck/build.
- Report changed files and table behavior risks.
```

### Validation after Step 6

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
Desktop:
- Price request table readable
- Product catalog price column clear
- Attendance table readable
- OT/leave table readable
- Row hover works
- Sorting/filtering/pagination still works

Mobile:
- Cards still render
```

Commit:

```bash
git add .
git commit -m "improve desktop table hierarchy"
```

---

## Step 7 — Improve dashboard action hierarchy

### Why this matters

Dashboard should not just display numbers. It should tell users what needs action.

### What Impeccable should do

Impeccable should turn dashboard into an operational work queue without inventing data.

### Prompt for Claude / Impeccable

```text
/impeccable

Improve dashboard hierarchy for GL&R HR Portal.

Do not change API calls, backend behavior, calculations, permissions, routes, or invent fake data.

Problem:
Dashboard shows numbers but does not clearly tell users what needs action.

Goal:
Turn the dashboard into an operational work queue.

Fix:
1. Add or strengthen a top “ต้องดำเนินการ” / “งานที่ต้องดูแล” section.
2. Prioritize items that require action:
   - pending approvals
   - unread notifications
   - open tickets
   - overtime waiting approval
   - leave waiting approval
   - import/profile requests if data exists
3. Make metric cards clearly clickable if they navigate somewhere.
4. Add clear hover/focus states for clickable cards.
5. Add meaningful empty states instead of blank panels.
6. Reduce decorative card feeling.
7. Keep cards calm and compact.
8. Do not invent fake data. Use existing values only.
9. If data is missing, show a safe empty state.
10. Mobile dashboard must not become too tall.

Avoid:
- More decorative cards.
- More random icons.
- Making all numbers visually equal.
- Adding charts unless existing data supports them.

Acceptance:
- User can see what needs attention within 5 seconds.
- Mobile dashboard is compact and useful.
- Desktop dashboard feels calm and operational.

After changes:
- Run lint/typecheck/build.
- Report changed files and any data assumptions.
```

### Validation after Step 7

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
- Dashboard has clear “what needs action” hierarchy
- Cards that navigate are clearly clickable
- Empty states are intentional
- Mobile dashboard is compact
- No fake data introduced
```

Commit:

```bash
git add .
git commit -m "improve dashboard action hierarchy"
```

---

## Step 8 — Improve detail page action hierarchy

### Why this matters

Detail pages currently contain information, but they do not guide the user strongly enough.

Every detail page should answer:

```text
What is this?
What status is it in?
Who owns it?
What should I do next?
What changes after I click approve/reject?
```

### Affected pages

```text
- Price request detail
- Quotation approval detail
- Commission detail if any
- OT detail/cards
- Leave detail/cards
```

### Prompt for Claude / Impeccable

```text
/impeccable

Improve detail page hierarchy and action panels.

Do not change approval logic, API calls, permissions, business rules, routes, database logic, or calculations.

Problem:
Detail pages contain information but do not clearly show current status, owner, and next action.

Fix:
1. Add or improve top summary panel:
   - ID
   - customer/person/company
   - status badge
   - created by
   - created date
   - current owner/role if data exists
   - next action
2. Replace blank action cards with contextual action panels.
3. Make approve/reject actions serious and clear.
4. Add helper text explaining what happens after action.
5. Keep history/comment panels but improve empty states.
6. Mobile should show summary first, then actions, then details/history.
7. Do not bury approval actions under long details.
8. Item/money lists should be easy to scan.
9. Do not invent ownership or next action data; if unavailable, use safe generic wording.

Acceptance:
- User knows what to do next immediately.
- Approval/detail pages feel trustworthy.
- Mobile does not require excessive scrolling to find important actions.
- Existing actions still work.

After changes:
- Run lint/typecheck/build.
- Report changed files and approval behavior risks.
```

### Validation after Step 8

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
- Price request detail shows summary/status/next action
- Quotation detail shows summary/status/next action
- Mobile detail page order makes sense
- Existing approve/reject/comment/upload actions still work
```

Commit:

```bash
git add .
git commit -m "improve detail page action hierarchy"
```

---

## Step 9 — Improve approval and finance UI trust

### Why this matters

Approval and money screens are business-critical. They must feel serious, precise, and safe.

### What Impeccable should do

Impeccable should improve UI trust without changing approval or calculation logic.

### Prompt for Claude / Impeccable

```text
/impeccable

Improve approval and finance UI seriousness.

Do not change approval business logic, calculations, API contracts, permissions, routes, database logic, or submitted data.

Problem:
Approval and finance actions feel too casual for business-critical workflows.

Fix:
1. Approval pages must clearly show what the user is approving.
2. Show financial summary before approve/reject where data exists.
3. Approve/reject buttons must be visually serious and clearly differentiated.
4. Rejection should clearly support reason/comment if existing logic allows.
5. Show next status after action using helper text.
6. Make commission amounts, deductions, gross/net values easy to scan.
7. Right-align money and numeric values.
8. Keep audit/history visible.
9. Use confirmation dialogs or confirmation copy only if existing patterns support it safely.
10. Do not add blocking behavior that breaks existing workflows.

Pages:
- Quotation approval
- Price request approval
- Commission
- OT approval
- Leave approval

Acceptance:
- User can verify business-critical data before approval.
- Finance values are scannable.
- Approve/reject actions feel deliberate.
- Existing approval behavior is preserved.

After changes:
- Run lint/typecheck/build.
- Report changed files and workflow risks.
```

### Validation after Step 9

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
- Quotation approval shows what is being approved
- Commission money values are scannable
- OT approval and leave approval actions still work
- Rejection/comment behavior is not broken
```

Commit:

```bash
git add .
git commit -m "improve approval and finance UI trust"
```

---

# P2 — Domain-specific polish and final QA

---

## Step 10 — Improve product catalog and price import UX

### Why this matters

Product catalog and price import are data-heavy and risky. They need clearer hierarchy and safer workflow.

### Product catalog goals

```text
- Search first
- Factory filter clear
- Product identity clear
- Price prominent
- Edit action consistent
- Mobile product cards readable
```

### Price import goals

```text
Step 1: Select factory
Step 2: Upload price list
Step 3: Preview/validate if available
Step 4: Commit/import
```

### Prompt for Claude / Impeccable

```text
/impeccable

Improve Product Catalog and Price Import UX.

Do not change backend import behavior, data parsing, APIs, database logic, permissions, or routes.

Product catalog problems:
- Too table-like.
- Prices are important but not visually prioritized.
- Search/filter area can be clearer.
- Product code/name/collection hierarchy is weak.
- Mobile must use product cards, not squeezed tables.

Product catalog fixes:
1. Search/filter area cleaner and more compact.
2. Product identity hierarchy clearer.
3. Price column stronger and right-aligned on desktop.
4. Mobile product cards show name/code, collection, size/surface/color, price, and action.
5. Desktop table remains dense but readable.
6. Edit action consistent.
7. Do not remove useful columns unless duplicated in card/detail.

Price import problems:
- Upload flow feels risky.
- It jumps from upload to commit without enough confidence.
- Native file input looks unfinished.

Price import fixes:
1. Make factory selection clearly step 1.
2. Make upload clearly step 2.
3. If existing preview/validation data exists, present it as step 3.
4. If preview does not exist, add a clear warning/note before commit.
5. Make “Upload and Commit” look like a serious action.
6. Replace native-looking file input UI.
7. Preserve existing backend behavior.

Acceptance:
- Product search is usable on mobile and desktop.
- Product price is easy to scan.
- Import workflow feels safer.
- Existing import behavior remains unchanged.

After changes:
- Run lint/typecheck/build.
- Report changed files and import behavior risks.
```

### Validation after Step 10

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
- Product catalog mobile card works
- Product catalog desktop table works
- Search/filter works
- Price import factory select works
- File upload works
- Upload/commit action still works
```

Commit:

```bash
git add .
git commit -m "improve product catalog and price import UX"
```

---

## Step 11 — Improve HR operation screens

### Why this matters

HR users do not just need lists. They need exception-first design: what is missing, pending, abnormal, or risky.

### Attendance should surface

```text
- Missing punch
- Missing badge/card
- Device/source
- Import status
- Search/filter clearly
```

### Overtime should surface

```text
- Pending approvals
- Payable time
- Manager/CEO chain
- Action buttons
```

### Leave should surface

```text
- Remaining leave
- Pending requests
- Medical certificate status
- Legal/reference notes quietly
```

### Prompt for Claude / Impeccable

```text
/impeccable

Improve HR operation screens with exception-first design.

Do not change attendance import logic, OT calculation, leave calculation, APIs, permissions, database logic, or routes.

Goal:
HR pages should show what needs attention first.

Attendance fixes:
1. Surface missing badge/card issues clearly.
2. Show device/source clearly.
3. Improve search/filter layout.
4. Mobile records should be compact cards.
5. Table/card should prioritize employee, punch time, code, device/source, and anomaly.
6. Do not change import logic.

Overtime fixes:
1. Pending approvals must be prominent.
2. Show manager/CEO approval chain.
3. Show payable time clearly.
4. Action buttons consistent and serious.
5. Mobile OT cards should show employee, date, time range, reason, payable time, and status.
6. Do not change OT calculation logic.

Leave fixes:
1. Leave quota cards easier to scan.
2. Remaining leave obvious.
3. Medical certificate upload clearer.
4. Legal/reference note quieter and readable.
5. Mobile leave cards should show employee, leave type, date range, remaining entitlement, and status.
6. Do not change leave calculation logic.

Acceptance:
- HR users can identify pending/abnormal items quickly.
- Mobile cards are readable.
- Forms remain usable.
- Existing calculations and imports are preserved.

After changes:
- Run lint/typecheck/build.
- Report changed files and workflow risks.
```

### Validation after Step 11

Run:

```bash
npm run lint
npm run typecheck
npm run build
```

Manual checks:

```text
- Attendance search/import/list works
- OT request and approvals work
- Leave request and approvals work
- Mobile cards readable
- Desktop remains usable
```

Commit:

```bash
git add .
git commit -m "improve HR operation screens"
```

---

## Step 12 — Final responsive QA pass

### Why this matters

This step is only for bug fixes, spacing, overflow, accessibility, and consistency. Do not start new redesign here.

### Prompt for Claude / Impeccable

```text
/impeccable

Final responsive UI QA for GL&R HR Portal.

Test these viewport widths:
- 320px
- 375px
- 390px
- 430px
- 768px
- desktop width

Pages:
- Login
- Dashboard
- My profile
- Price requests
- Ticket overview / Price request overview
- Price request detail
- Quotation approval detail
- CEO price config
- Product catalog
- Price import
- Commissions
- Attendance
- Overtime
- Leave

Hard acceptance:
1. No horizontal scroll anywhere.
2. Header never overlaps content.
3. Sidebar drawer is readable and closes correctly.
4. Tables become cards on mobile.
5. Status badges never clip.
6. Dates never overlap badges.
7. Forms are single-column on mobile.
8. File uploads are custom-looking.
9. Primary actions are reachable.
10. Touch targets are at least 44px.
11. Thai text is readable.
12. Desktop layout is not broken.
13. Status colors follow global rules.
14. Empty states look intentional.
15. No console errors from UI rendering.
16. No broken approval/upload/search/filter workflows.

Only fix:
- layout bugs
- overflow
- spacing issues
- obvious accessibility problems
- inconsistent badge/button styling
- mobile regressions

Do not start a new redesign.
Do not change business logic.

After changes:
- Run lint
- Run typecheck
- Run tests if available
- Run production build
- Summarize fixed issues
- Summarize changed files
- Summarize remaining risks
```

### Validation after Step 12

Run:

```bash
npm run lint
npm run typecheck
npm test -- --runInBand || npm test || true
npm run build
```

If tests do not exist or test command is unavailable, document that clearly.

Manual checks at every viewport:

```text
320px:
- no horizontal scroll
- header okay
- drawer okay
- tables as cards
- forms single-column

375px:
- no horizontal scroll
- badges not clipped
- important actions reachable

390px:
- same checks

430px:
- same checks

768px:
- tablet layout not awkward
- sidebar/header behavior reasonable

Desktop:
- tables remain usable
- dashboard stable
- detail pages stable
- forms stable
```

Commit final QA fixes:

```bash
git add .
git commit -m "final responsive UI QA fixes"
```

---

# 4. Full final validation before push/merge

Before pushing or merging, the agent must perform a careful final review.

## 4.1 Check git diff

```bash
git status
git diff --stat
git diff --name-only main...HEAD
```

Review the changed files. Confirm the diff is primarily UI/layout/component files.

Red flags that must stop the merge:

```text
- Backend files changed unexpectedly
- Database migrations changed unexpectedly
- API contracts changed unexpectedly
- Auth/permission logic changed unexpectedly
- Business calculations changed unexpectedly
- Massive unrelated file rewrites
- Package lock changes without dependency reason
- Generated files committed accidentally
- Screenshots/logs committed accidentally
```

If any red flag appears, stop and investigate before merge.

## 4.2 Run final validation commands

Use the package manager that the repo uses. If unsure, inspect lockfiles.

Try:

```bash
npm run lint
npm run typecheck
npm test -- --runInBand || npm test || true
npm run build
```

If the repo uses `pnpm`:

```bash
pnpm lint
pnpm typecheck
pnpm test
pnpm build
```

If the repo uses `yarn`:

```bash
yarn lint
yarn typecheck
yarn test
yarn build
```

Do **not** claim tests passed if no test command exists. Say:

```text
No test script was available; lint/typecheck/build passed.
```

## 4.3 Manual visual validation checklist

Validate each page at desktop and mobile.

```text
Pages:
- Login
- Dashboard
- My profile
- Price requests
- Ticket overview / Price request overview
- Price request detail
- Quotation approval detail
- CEO price config
- Product catalog
- Price import
- Commissions
- Attendance
- Overtime
- Leave

Viewports:
- 320px
- 375px
- 390px
- 430px
- 768px
- desktop width
```

Checklist:

```text
[ ] No horizontal scroll
[ ] Header does not overlap content
[ ] Sidebar drawer opens and closes correctly
[ ] Sidebar drawer is left-aligned and readable
[ ] Nav labels are not underlined
[ ] Tables become cards on mobile
[ ] Cards show only important fields
[ ] Cards show enough information for real user decisions
[ ] Badges do not clip
[ ] Dates do not collide with badges
[ ] Forms are single-column on mobile
[ ] File uploads look custom
[ ] Buttons are tappable, practically at least 44px
[ ] Primary actions are visible and near the relevant content
[ ] Dangerous actions are clearly separated from safe actions
[ ] Thai text is readable
[ ] Desktop tables still work
[ ] Filters still work
[ ] Search still works
[ ] Pagination still works
[ ] Uploads still work
[ ] Approve/reject actions still work
[ ] Mobile user can complete core tasks without guessing
[ ] Styling is Tailwind-first where practical
[ ] Native CSS remaining is intentional and documented
[ ] No console errors
```

## 4.4 Business workflow smoke tests

Perform safe smoke tests in local/dev environment only.

```text
Authentication / app shell:
[ ] Login page renders
[ ] Authenticated layout renders
[ ] Sidebar navigation works
[ ] Logout still works

Price request:
[ ] Price requests list loads
[ ] Filter chips work
[ ] Search works
[ ] Detail page opens
[ ] Status badge renders correctly

Quotation / approval:
[ ] Quotation detail opens
[ ] Approve/reject buttons render in correct states
[ ] Comments/history render
[ ] Upload area renders

Product catalog:
[ ] Search works
[ ] Factory filter works
[ ] Edit action still opens/works

Price import:
[ ] Factory select works
[ ] File field accepts expected file type
[ ] Upload/commit button still wired

Commission:
[ ] Form renders
[ ] File upload renders
[ ] Records render
[ ] Money values readable

Attendance:
[ ] Date filters work
[ ] Device/source select works
[ ] Attendance import field renders
[ ] Records render as cards on mobile and table on desktop

Overtime:
[ ] Request form renders
[ ] Records render
[ ] Approve/reject actions render where allowed

Leave:
[ ] Leave quota renders
[ ] Request form renders
[ ] Certificate upload renders
[ ] Records render
```

---

# 5. Push and merge instructions

Only do this after all validation above passes.

## 5.1 Final commit check

```bash
git status
```

If there are uncommitted changes:

```bash
git add .
git commit -m "final validation fixes"
```

Re-run:

```bash
npm run lint
npm run typecheck
npm run build
```

## 5.2 Push branch

```bash
git push origin ui-responsive-repair
```

## 5.3 Open PR if GitHub CLI is available

```bash
gh pr create \
  --base main \
  --head ui-responsive-repair \
  --title "Fix responsive UI for GL&R HR Portal" \
  --body "
## Summary
- Fixed mobile shell/global overflow
- Reworked mobile tables into cards
- Improved mobile forms and file uploads
- Added shared responsive UI patterns
- Improved desktop table hierarchy
- Improved dashboard/detail/approval UX
- Ran final responsive QA

## Validation
- lint: pass
- typecheck: pass
- build: pass
- tests: pass / not available, documented
- manual responsive QA: 320, 375, 390, 430, 768, desktop

## Risk notes
- UI-only changes intended
- No backend/API/database/auth/business-calculation changes intended
"
```

If GitHub CLI is not available, push the branch and create the PR manually.

## 5.4 Merge carefully

Before merge:

```bash
gh pr diff
```

Then require one final Opus review. Opus must review:

```text
- final actual screenshots at 320, 375, 390, 430, 768, and desktop
- critical user flows from CEO, HR/admin, sales, import, and operations perspectives
- PR diff for accidental business logic/API/auth/calculation changes
- validation output: lint, typecheck, tests, production build
- whether mobile is genuinely usable, not just fitting the viewport
```

Check again for red flags:

```text
- backend logic changed unexpectedly
- database logic changed unexpectedly
- auth/permissions changed unexpectedly
- calculations changed unexpectedly
- broken build
- unresolved merge conflicts
- unresolved TODOs that block UI usage
```

If everything is clean and CI passes, merge.

Preferred merge command if repo uses squash merges:

```bash
gh pr merge --squash --delete-branch
```

If repo uses normal merge commits:

```bash
gh pr merge --merge --delete-branch
```

If repo allows fast-forward and you are certain:

```bash
git checkout main
git pull origin main
git merge --ff-only ui-responsive-repair
git push origin main
git branch -d ui-responsive-repair
git push origin --delete ui-responsive-repair
```

**Do not merge if CI fails. Do not force-push main. Do not bypass review if something looks suspicious.**

---

# 6. Final report the agent should produce

At the end, Claude should report:

```text
## Completed
- Opus/Sonnet loop followed: yes/no
- Opus reviewed actual screenshots before and after each phase: yes/no
- Step 0.5: Tailwind/native CSS baseline audit
- Step 1: Mobile shell/global overflow
- Step 2: Mobile tables to cards
- Step 3: Mobile forms/file uploads
- Step 4: Mobile spacing/density
- Step 4.5: Tailwind-first CSS migration and containment
- Step 5: Shared UI system
- Step 6: Desktop tables
- Step 7: Dashboard hierarchy
- Step 8: Detail page hierarchy
- Step 9: Approval/finance trust
- Step 10: Product catalog/price import
- Step 11: HR operation screens
- Step 12: Final QA

## Validation
- Opus final screenshot review: pass/fail
- real-user mobile task review: pass/fail
- lint: pass/fail
- typecheck: pass/fail
- tests: pass/fail/not available
- build: pass/fail
- responsive QA: pass/fail with notes

## Changed files
- list major files/components changed

## Remaining risks
- list anything uncertain

## Merge status
- final Opus review before push/merge: pass/fail
- PR diff reviewed for unintended business-logic changes: yes/no
- branch pushed: yes/no
- PR created: yes/no
- merged to main: yes/no
```

---

# 7. UX/UI reference notes for Claude

Use these references as principles. Do not copy their visual brand directly.

```text
1. Nielsen Norman Group — 10 usability heuristics
   Use for: status visibility, error prevention, recognition over recall, consistency, user control.
   Reference: https://www.nngroup.com/articles/ten-usability-heuristics/

2. W3C WCAG 2.2 — target size, focus, accessibility baseline
   Use for: touch targets, keyboard/focus behavior, accessible controls.
   Reference: https://www.w3.org/TR/WCAG22/

3. Laws of UX — Fitts's Law
   Use for: touch target size, spacing, action placement, thumb-friendly controls.
   Reference: https://lawsofux.com/fittss-law/

4. Material Design 3 — navigation drawer
   Use for: drawer behavior and navigation destination structure.
   Reference: https://m3.material.io/components/navigation-drawer/overview

5. Shopify Polaris — admin tables and object lists
   Use for: operational admin tables/cards that help users analyze and take action.
   Reference: https://polaris-react.shopify.com/components/tables

6. GOV.UK Design System / Service Manual
   Use for: plain-language, accessible, non-flashy, high-trust service workflows.
   Reference: https://design-system.service.gov.uk/

7. Tailwind CSS official docs
   Use for: utility-first styling, responsive breakpoints, and reducing scattered native CSS.
   Reference: https://tailwindcss.com/docs/styling-with-utility-classes
```

Important interpretation:

```text
- Shopify Polaris is a workflow reference, not a visual skin.
- GOV.UK is a clarity/accessibility reference, not a brand style.
- Material is a navigation behavior reference, not a full app redesign target.
- Tailwind is the implementation standard, not an excuse for inconsistent class soup.
```

---

# 8. Quick emergency fallback

If the overnight agent runs out of time, it must prioritize only these before stopping:

```text
1. No horizontal scroll
2. Mobile header/sidebar usable
3. Price request table becomes cards
4. Product catalog table becomes cards
5. Attendance table becomes cards
6. Forms single-column on mobile
7. Native file inputs replaced visually
8. Build passes
```

Do not spend time on desktop polish until these are done.
