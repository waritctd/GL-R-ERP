# Phase 5A Mobile Workspace Contract

Date: 2026-07-25

Scope: `/tickets/:id` ticket workspace at the primary mobile acceptance viewport
`390 x 844`.

This contract is Step 18. The mobile ticket workspace must be a compact operational record with
one reachable current task. It must not reproduce the desktop workspace as one long vertical
stack.

## Core Rule

Mobile is not "desktop in one column".

At `390 x 844`, the first screen must show the compact command header, reachable tabs, the start
of the active tab, and a reachable primary action path. Historical content, full pipeline detail,
large empty states and reference tables must not appear before the current work.

## Required Mobile Answers

Without scrolling through historical content, a mobile user must be able to answer:

1. Which deal is this?
2. Which stage is it currently in?
3. What is the current work state?
4. Is something blocked, returned or overdue?
5. Whose action is required?
6. What should this user do next?
7. Which tab contains the supporting detail?

These answers may be split between the compact header, work-state alert, tab row and sticky
action bar, but they must not be hidden below the old desktop body stack.

## Header Contract

The persistent header is compact on mobile.

Allowed header content:

- Deal code.
- Customer name.
- Project name.
- Current stage.
- Work-state label or alert.
- Waiting-on role or blocker/returned reason.
- Sales owner or deadline/freshness when relevant.
- Compact metadata strip.
- Refresh as an icon action.

Mobile header rules:

- Do not render the five-card summary row.
- Do not render the full fourteen-step pipeline in the header or immediately after it.
- Keep one primary next-action path. The primary can live in the sticky action bar, with the
  header explaining why.
- Long Thai customer and project names wrap intentionally. They must not force page-level
  horizontal scrolling, clip the current stage, or push tabs out of reach.
- Prefer two-line wrapping for identity text, with lower-priority metadata wrapping, collapsing
  or moving below the active tab when space is tight.
- Do not use viewport-scaled font sizes to make names fit.

## Tab Reachability

Tabs remain reachable at the top of the mobile workspace.

Rules:

- The tab row appears directly after the compact header relationship, using the Phase 5
  `16px` header-to-tabs rhythm.
- Tabs may horizontally scroll as a tablist, but the page itself must not horizontally scroll.
- Active tab content begins close to the top, using the Phase 5 `16px` tabs-to-content rhythm.
- The Overview tab must not preload Pricing, Money, Fulfilment, Documents and Activity as full
  panels below it.
- A role with fewer tabs still uses the same tablist contract; do not replace it with a separate
  mobile-only content stack.

## Sticky Action Bar

The primary next action must be reachable without scrolling through history.

Rules:

- Render at most one primary action.
- Render up to two common secondary actions before overflow.
- Move destructive actions into a separated overflow or confirmation path.
- Include safe-area padding at the bottom.
- Add enough bottom content padding so the sticky bar never covers the last row, form action,
  empty state, table row or timeline entry.
- The bar must not cover modal or drawer controls.
- The bar must not trap scroll, and keyboard focus must remain visible.
- If no permitted action exists, show waiting/read-only context instead of a disabled fake
  workflow action.

## Active Tab Density

Each active tab opens with its current operational content, not with reference history.

Mobile tab rules:

- Overview opens with current-work alert, description rows, item summary and compact workflow
  summary.
- Pricing opens with the active request or a compact empty row.
- Quotations open with the current document/outcome row, not every historical revision expanded.
- Money opens with compact financial rows and permitted finance action.
- Fulfilment opens with the current procurement/delivery track row.
- Documents opens with grouped dense file rows or a compact empty upload row.
- Activity opens with the current composer/filter plus recent timeline, with long history
  progressively disclosed.

Empty states must stay compact. An empty tab should not consume more vertical space than an
active record row.

## Tables And Lists

Tables must not force page-level horizontal scrolling.

Rules:

- Use compact mobile records for item, pricing request, quotation, payment, document, delivery
  and activity rows when comparison across many columns is not the main task.
- Use horizontal scroll only inside a clearly bounded table/list region where true column
  comparison is necessary, such as dense finance, Factory PO or quotation detail data.
- The document/page root must satisfy: `scrollWidth <= clientWidth + 1`.
- Row actions stay row-local and compact.
- Mobile record internal gap: `8-12px`.
- Mobile record-to-record gap: `12px`.

## Forms, Disclosures, Modals And Drawers

Expandable forms must not open beyond the viewport in a way that hides the submit/cancel path.

Rules:

- Short forms may expand inline only when the primary controls remain reachable.
- Longer forms should use the shared `Modal` contract or a focused drawer/sheet.
- Mobile sheets use a fixed footer and internal scroll for long content.
- Modals and drawers must trap focus, close with Escape where supported, restore focus on close,
  and mark background content inert.
- Initial focus must land on the first meaningful control or heading.
- Background page scroll must not continue behind an open blocking layer.
- The sticky action bar must not appear above or compete with modal/drawer submit actions.

## Safe Area And Overflow

Mobile layout must account for device safe areas and prevent accidental horizontal overflow.

Rules:

- Bottom sticky surfaces include `env(safe-area-inset-bottom)`.
- Top sticky surfaces account for shell/header offsets without covering tabs.
- Page-level horizontal scrolling is forbidden.
- Long filenames, long Thai customer names, long project names and unbroken document numbers must
  wrap or truncate intentionally inside their owning row.
- Buttons, badges and metadata pairs may wrap; they may not stretch the page.

## Mobile Test Scenarios

Implementation evidence must test `390 x 844` with the following data shapes:

| Scenario | Required mobile assertion |
|---|---|
| Long Thai customer name | Header wraps intentionally; tabs and primary action remain reachable; no horizontal overflow. |
| Long project name | Project text wraps or collapses without hiding current stage, work state or tab row. |
| Multiple pricing requests | Pricing tab shows active request first; other requests are collapsed compact rows. |
| Five quotations | Quotations tab uses dense rows; revisions do not become five full cards before the current action. |
| Partial delivery | Fulfilment tab distinguishes warehouse receipt from customer delivery and shows current delivery work first. |
| Multiple documents | Documents tab uses grouped rows with compact row-local download/delete actions. |
| Long activity history | Activity history is compact and does not block access to the primary action or composer. |
| Returned pricing request | Header/work-state alert explains the return; Pricing tab shows the rework path without a long badge. |
| Overdue payment | Header/work-state alert shows overdue money blocker; account gets one finance primary, other roles get waiting context. |
| Completed deal | Header reads terminal; sticky bar removes routine workflow actions; history remains reference only. |

## Acceptance Checks

Implementation is not complete until mobile evidence proves:

- The page does not reproduce the desktop structure as one vertical stack.
- Compact header, tabs, active tab opening and primary action path are visible at `390 x 844`.
- Long Thai customer/project names do not cause page-level horizontal scrolling.
- Sticky action bar uses safe-area padding and does not cover content.
- Empty states are compact.
- Tables reflow to records or scroll only within their own bounded region.
- Expandable forms, modals and drawers keep submit/cancel controls reachable.
- Modal and drawer focus, inert background and focus restore behaviours remain correct.
- `document.documentElement.scrollWidth <= document.documentElement.clientWidth + 1` in every
  mobile scenario.
