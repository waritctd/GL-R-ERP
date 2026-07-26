# Phase 5A CSS And Visual Cleanup

Date: 2026-07-25

Scope: `/tickets/:id` ticket workspace and directly related ticket panels:
`TicketDetailPage`, workspace header/action/tab components, `DealStagePanel`,
`DealTrackingPanel`, `DealQuotationPanel`, `DealDepositPanel`, `DealFulfilmentPanel`, and
`PricingRequestPanel` when rendered inside the ticket workspace.

This contract is Step 20. It governs the visual cleanup that happens after the workspace has
clear information architecture and component boundaries.

## Goal

Clearer grouping with fewer boxes.

The goal is not "no structure". The workspace still needs visible distinctions between current
work, waiting work, completed work, historical reference and temporary decisions. It should feel
like an operations control desk: calm, reliable and efficient.

## Scope Boundaries

Allowed:

- Remove ticket-detail border layers that no longer carry meaning.
- Remove unnecessary rounded containers created by the old panel/card stack.
- Remove duplicate backgrounds that make every section feel equally heavy.
- Reduce excessive vertical padding, especially from nested panel/card padding.
- Remove one-off shadows from resting content.
- Replace repeated inline layout styles with semantic components, Tailwind utilities, approved
  tokens, or ticket-owned classes when native CSS is genuinely needed.
- Delete ticket-owned legacy selectors only after usage verification.

Not allowed:

- Full application CSS rewrite.
- Blind CSS-to-Tailwind conversion.
- New page-specific CSS files.
- Global edits to shared legacy selectors without proving all callers.
- Removing every border.
- Creating one giant undifferentiated white page.
- Making all content tiny.
- Hiding important distinctions to gain density.
- Reducing Thai font sizes or line-heights below the approved floor.
- Weakening visible focus or 44px touch targets.

## Surface Hierarchy

Use the fewest surfaces that communicate the work.

| Surface | Visual treatment | Notes |
|---|---|---|
| Persistent command header | One strong working surface | The single current-work command panel may keep a border/background. |
| Tab row | Navigation row with underline/divider | Not a card. It maps to supporting details. |
| Active tab body | Sections, description rows, lists and tables | Avoid an extra wrapper panel unless the tab owns one active decision. |
| Active decision | One focused panel or inline alert | Earns weight only when action/decision is required now. |
| Repeated external records | Table/list rows or compact disclosed rows | Pricing requests, quotations, files, payments and POs may have row boundaries. |
| Historical content | Timeline/list rows with subdued dividers | History must not compete with current work. |
| Temporary layers | Modal, drawer, confirm dialog | Elevated surfaces are allowed here. |

## Borders

Remove borders that only restate a parent boundary.

Keep borders for:

- Command header boundary.
- Form controls.
- Table/list row dividers.
- Independent external records when row separation is needed.
- Inline alerts.
- Modal, drawer and confirm-dialog surfaces.
- Focus rings and error states.

Remove or convert borders for:

- Card inside panel inside tab.
- Bordered metric tiles in the header or Money tab.
- Bordered stage chips that duplicate the header.
- Bordered empty-state boxes.
- Bordered historical event cards inside an already grouped Activity tab.
- Decorative accent stripes.

Preferred replacement:

- Light row divider.
- Description-list row.
- Compact inline alert.
- Section heading plus content.
- Tonal inset only when it communicates current/blocking/returned context.

Do not remove every border. Borders remain useful when they separate rows, inputs, temporary
layers, and independent external records.

## Radius

Use radius sparingly.

Allowed:

- `8px` default radius for controls, true panels and temporary layers.
- Pill radius only for real badges or true pill controls.
- Small radius for compact inline controls when inherited from the primitive.

Avoid:

- Rounding every row.
- Rounded metadata pairs in the header.
- Rounded containers nested inside other rounded containers.
- New `20px+` soft panels.
- Radius as the only way to signal grouping.

## Backgrounds

Use one background level per region.

Allowed:

- `surface-panel` for the command header and true working surfaces.
- `surface-muted` for table headers or quiet inset zones.
- `surface-subtle` for tracks, dividers or low-emphasis insets.
- Status-tone backgrounds only for `InlineAlert` or valid status badges.

Avoid:

- A parent panel background plus child card background plus row background.
- Header metadata pairs each on their own background tile.
- Empty states with large decorative backgrounds.
- Alternate background blocks that make unrelated sections feel equal.

If a background is removed, add structure with headings, dividers, row grouping or semantic
ordering rather than leaving users with an undifferentiated page.

## Shadows And Elevation

Resting ticket workspace content should be mostly flat.

Allowed:

- `elevation-resting` only where a true panel needs a barely visible seat.
- `elevation-popover` for menus/popovers.
- `elevation-dialog` for modals, drawers and confirm dialogs.
- `focus-ring` for interactive focus.

Remove:

- One-off box shadows on resting panels/cards.
- Floating shadows on metric tiles.
- Shadow differences used as the only distinction between sections.

Do not reach for `!important` to win shadow or border conflicts. Resolve ownership between
legacy selectors and utilities instead.

## Spacing And Density

Use the Phase 5 spacing rhythm from `PHASE_5A_PLAN.md` and `TOKENS.md`.

Defaults:

- Desktop working section padding: `20px`.
- Mobile working section padding: `16px`.
- Compact inline alert padding: `12px`.
- Related row gaps: `8-12px`.
- Major section separation: `24px`.
- Buttons/action gaps: `8px`.
- Distinct action/destructive separation: at least `16px` or separate overflow group.

Remove double padding caused by:

```text
panel padding
- inner card padding
  - child content margin
```

Preserve readable density:

- Body/data text stays readable.
- Thai line-height remains safe for tone marks.
- Buttons and row actions keep 44px touch targets where interactive.
- Dense rows stay scannable and do not become cramped.
- Empty states are compact but still clear.

## Inline Styles And Legacy CSS

Inline styles are allowed only when the value is truly data-driven or technically necessary.

Replace repeated inline layout styles with:

- Semantic extracted components.
- Shared primitives.
- Tailwind utilities backed by existing tokens.
- Existing tokenized classes.
- A `ticket-workspace-` class in `styles.css` only when native CSS is genuinely needed.

Rules:

- Do not create a new page-specific CSS file.
- Do not add new ungoverned spacing, radius, colour or shadow values.
- Do not set the same visual property in both a Tailwind utility and a surviving legacy rule.
- Delete a legacy selector only after `rg` proves no remaining live callers.
- Keep `styles.css` edits labelled as Phase 5A when the selector must remain temporarily.
- Remove `.ticket-detail-grid`, `.ticket-events`, `.ticket-event`, and `.event-dot` only after
  their callers are gone.
- The one-off `900px` ticket-detail breakpoint leaves with `.ticket-detail-grid`; do not replace
  it with a new breakpoint.

## Token Mapping

Use approved tokens and semantic roles:

- Surfaces: `surface-panel`, `surface-muted`, `surface-subtle`, `surface-selected`.
- Text: `text-primary`, `text-secondary`, `text-muted`.
- Borders: `border-default`, `border-subtle`, `border-input`, `border-focus`, `border-error`.
- Actions: shared `Button` variants.
- Status: shared `StatusBadge` tones only when the value is a short state.
- Radius: `radius-control` / `radius-panel` default `8px`; `radius-pill` only for true pills.
- Elevation: none by default; popover/dialog only where appropriate.
- Spacing: `4/8/12/16/20/24/32px` Phase 5 rhythm.

Do not introduce nearby one-off values to make a single screenshot line up.

## Visual Distinction Rules

Preserve these distinctions after cleanup:

- Current work: header/work-state alert/action bar, highest operational priority.
- Needs action now: one primary action plus clear responsible role.
- Waiting on another role: waiting text or inline alert, not a fake disabled primary.
- Blocked/returned/overdue: inline alert or work-state banner with supporting tab route.
- Completed/terminal: quiet terminal state, no routine workflow actions.
- Historical/reference: Activity/timeline density, visually below current work.
- Temporary decision: modal/drawer/focused panel with one clear submit path.

If removing a border/background makes two of these states visually ambiguous, keep a quieter
divider, alert, heading or row grouping.

## Verification

Implementation evidence must include:

- Before/after screenshots at `390 x 844`, `768 x 1024`, `1024 x 768`, `1366 x 768`,
  `1440 x 900`.
- Header no longer contains five equal-weight metric cards.
- Money no longer uses three metric cards.
- Documents/Pricing empty states are compact.
- Activity history is not a set of bordered cards inside a panel.
- No nested decorative card stack remains in touched ticket-workspace surfaces.
- No page-level horizontal scrolling at mobile.
- Visible focus remains on tabs, buttons, row actions, forms and modal controls.
- 44px touch targets remain for interactive controls on mobile.
- Thai text remains readable and un-clipped.
- Removed selectors have zero remaining live callers.
- `git diff --check` passes.

## Acceptance Checks

Implementation is not complete until:

- Unnecessary border layers, rounded containers, duplicate backgrounds, excessive padding and
  one-off shadows are removed from touched ticket surfaces.
- Remaining borders/backgrounds/radius/shadows have semantic reasons.
- Inline layout styles are reduced where maintainable components, utilities or tokenized classes
  exist.
- Approved spacing and surface tokens are used.
- Readable density, Thai sizing, visible focus and 44px touch targets are preserved.
- The page still communicates structure; it is not a giant white sheet.
- The cleanup is limited to ticket detail and directly related panels.
