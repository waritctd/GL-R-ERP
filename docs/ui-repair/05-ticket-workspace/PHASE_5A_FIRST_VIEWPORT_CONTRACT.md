# Phase 5A First-Viewport Contract

Date: 2026-07-25

Scope: `/tickets/:id` ticket workspace across all role projections and all inspected viewport
sizes: `390 x 844`, `768 x 1024`, `1024 x 768`, `1366 x 768`, `1440 x 900`.

Mobile-specific requirements for `390 x 844` are detailed in
[`PHASE_5A_MOBILE_WORKSPACE_CONTRACT.md`](PHASE_5A_MOBILE_WORKSPACE_CONTRACT.md). This
first-viewport contract remains the source for the seven required answers; the mobile contract
defines how those answers stay reachable without copying the desktop stack into one column.

This contract turns the ticket workspace from a page of panels into one operational record with
one clear current task.

## Required Questions

Within the first viewport, the user must be able to answer:

1. Which deal is this?
2. Which stage is it currently in?
3. What is the current work state?
4. Is something blocked or returned?
5. Whose action is required?
6. What should the current user do next?
7. Where can supporting details be found?

The first viewport must not force users to scan unrelated panels, historical records, or a full
pipeline diagram before they understand the current work.

## Strict Hierarchy

The first viewport has three layers:

| Layer | Surface | Purpose |
|---|---|---|
| 1 | Persistent command header | Deal identity, current stage, work state, blocker/waiting role, owner, deadline/freshness, one next action |
| 2 | Tab navigation | Where supporting detail lives |
| 3 | Active tab opening section | The current tab's most relevant content, not a repeated header |

The command header is the only first-viewport panel that may carry full visual weight. The tab
row is a navigation row. The active tab opening section must not become another status dashboard.

## Persistent Header Contract

The persistent header may show:

- Deal code.
- Customer.
- Project.
- Current stage.
- Work-state label.
- Waiting-on role where supported.
- Blocker or returned reason.
- Sales owner.
- Important freshness/deadline.
- One primary next action.
- Refresh as an icon action.

The persistent header must not show:

- Five equal-sized summary cards.
- A grid of metric cards for stage, pricing request, payment, import, and deal value.
- Historical events.
- Full tab content.
- Full fourteen-step pipeline detail.
- Multiple primary actions.
- Destructive actions competing with the next workflow action.
- Empty-state panels.

## Header Structure

Preferred structure:

1. Deal identity.
2. Current stage · work state · owner.
3. Compact metadata strip.
4. Primary next action.

The metadata strip uses inline label/value pairs, not rounded cards. Example:

| Label | Value |
|---|---|
| ใบขอราคา | รอ Import รับเรื่อง |
| การชำระเงิน | ยังไม่เริ่ม |
| การนำเข้า | ยังไม่เริ่ม |
| มูลค่าดีล | ฿0 |

Metadata-strip rules:

- Do not wrap each pair in its own decorative card.
- Keep labels and values visually connected with a 4-8px relationship.
- Use 8-12px between adjacent pairs when they remain in one row.
- Use plain label/value text and separators. Do not use badges or elevated tiles for metadata.
- Show only values that help route the user to supporting detail.
- Do not duplicate the current stage and work-state label inside the strip.
- On mobile, keep the most important pairs visible and allow lower-priority pairs to wrap,
  collapse, or move below the tab content.

## Stage Display Contract

The full pipeline must not dominate the first viewport.

Show:

- Current phase.
- Current stage.
- Immediate previous/next context where useful.
- Progress summary.

Allowed compact examples:

- `เฟส 4 · 11. จัดซื้อและนำเข้าสินค้า`
- `ขั้นตอน 11 จาก 14`
- `ก่อนหน้า: รับคำสั่งซื้อ · ถัดไป: ส่งมอบบางส่วน`

The complete fourteen-step detail may remain behind:

- `ดูขั้นตอนทั้งหมด`

The complete pipeline detail must not render at full prominence on every tab. It belongs in a
collapsed disclosure, drawer, or stage-detail section opened intentionally by the user.

## Work-State Treatment

Work-state is viewer-specific and comes from `dealWorkState.js`, not `ticket.status`.

Required display rules:

- `needs-action`: show the required actor and one primary action if the current viewer can act.
- `waiting`: show the waiting-on role and do not invent a primary action for the current viewer.
- `blocked`: show the blocker in the header and link users to the supporting tab.
- `returned`: show the returned reason in the header when available, with the rework path.
- `overdue`: show the deadline/freshness signal without creating a second status dashboard.
- `complete` / `cancelled`: remove routine workflow actions and make the record read terminal.
- `informational`: show current state and supporting detail location, not an action prompt.

The body may expand on work-state details, but it must not contradict the header.

## Action Contract

At most one visually dominant primary action may appear in the first viewport for the current
role and state.

Detailed action classification and role/state priority lives in
`PHASE_5A_ACTION_HIERARCHY.md`.

Allowed:

- One primary next action.
- Refresh as an icon action.
- One compact secondary text action only when needed to route to the active tab.

Not allowed:

- Primary action in the header plus another primary action in the first body panel.
- Destructive actions in the first-viewport action cluster.
- A visible wall of workflow actions.
- Disabled actions without a reason.

Secondary, destructive, and administrative actions belong in row-local controls, tab-local
controls, or `การดำเนินการอื่น`.

## Active Tab Opening

The content immediately below the tab row must prove where supporting details live.

Overview may open with:

- A compact current-stage section.
- Key item/customer reference rows.
- A small pointer to the tab that owns the current blocker.

Other tabs may open with:

- The tab's active record list.
- A compact inline empty row.
- A current decision surface only when that tab owns the current work.

The active tab opening must not repeat the full header, the full pipeline, or large empty-state
blocks.

## Viewport Requirements

Mobile `390 x 844`:

- Deal identity, current stage, work state, blocker/waiting role, owner or deadline, tabs, and
  the next action path must be visible without requiring the full desktop body stack.
- Metadata may wrap or collapse, but the current work answer must remain visible.
- Do not copy desktop section padding or full pipeline density directly to mobile.
- Long Thai customer/project names must wrap intentionally without causing page-level horizontal
  scrolling.
- The sticky action path must account for safe-area padding and must not cover active tab
  content.

Tablet `768 x 1024`:

- Header, tabs, and the beginning of active tab content must be visible together.
- The pipeline may show only compact current/progress context.

Desktop `1024 x 768`, `1366 x 768`, `1440 x 900`:

- Header, tabs, and the active tab's opening section must be visible together.
- Header metadata must read as one compact strip, not a row of cards.
- Full pipeline detail, history, and tab-local records must not compete with the current task.

## Spacing Requirements

Apply the Phase 5 spacing rhythm:

- Persistent header to tabs: 16px.
- Tabs to active content: 16px.
- Header internal working-section padding: 20px desktop, 16px mobile.
- Metadata label/value relationship: 4-8px.
- Metadata pair to pair: 8-12px.
- Action gap: 8px.
- Major section separation below the first viewport: 24px.

Do not visually tune first-viewport alignment with one-off margin values.

## Acceptance Checks

Implementation is not complete until rendered evidence shows:

- No five-card summary row in the persistent header.
- No full fourteen-step pipeline displayed by default in the first viewport.
- No duplicated primary workflow action between header/action bar and the first body panel.
- Header metadata uses compact inline label/value pairs.
- The first viewport answers the seven required questions for each role projection.
- Mobile does not reproduce the entire desktop stack before supporting details are reachable.
- The tab row clearly indicates where detail lives.
- Spacing follows the Phase 5 rhythm without new arbitrary values.
