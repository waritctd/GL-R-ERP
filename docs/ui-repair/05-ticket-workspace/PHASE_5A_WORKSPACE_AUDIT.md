# Phase 5A Workspace Density Audit

Date: 2026-07-25

Scope: ticket workspace at `/tickets/6` in the mock Vite app.

Status: workspace inventory complete. This document is the Phase 5A code-change gate; production code must not be changed until this inventory exists.

## Inspection Inputs

Required governance, audit, IA, design foundation, and Phase 5A plan files were read before this audit. The ticket workspace implementation and shared primitives named in the Phase 5A prompt were inspected.

Rendered evidence was captured with `VITE_USE_MOCKS=true` at:

- `390 x 844`
- `768 x 1024`
- `1024 x 768`
- `1366 x 768`
- `1440 x 900`

Roles inspected:

- `sales`
- `sales_manager`
- `import`
- `account`
- `ceo`

Temporary evidence files:

- Summary: `/private/tmp/glr-phase5a-audit/summary.json`
- Screenshots: `/private/tmp/glr-phase5a-audit/screens/`

App shell navigation, notification, and account controls are visible in screenshots but are excluded from this workspace-density classification because they are outside the ticket record surface.

## First-Viewport Findings

At all inspected viewport sizes, the first viewport contains the deal state header, the tab row, and the beginning of the pipeline panel. The deal identity, current stage, current work state, blocker, responsible role, and next action are present, but they compete with repeated stage and status content in the pipeline panel immediately below.

On `390 x 844`, the header consumes most of the first viewport. The page still starts stacking desktop content vertically after the tabs, so the mobile layout risks becoming the full desktop workspace in a narrower column.

Step 18 converts that risk into a Phase 5 acceptance gate:
[`PHASE_5A_MOBILE_WORKSPACE_CONTRACT.md`](PHASE_5A_MOBILE_WORKSPACE_CONTRACT.md). Mobile
implementation must prove that the compact header, reachable tabs, active tab opening and one
primary action path are available at `390 x 844` without scrolling through historical content.

On `768 x 1024`, the header is readable, but the pipeline panel restates the same stage and status information before supporting details can appear.

On `1024 x 768`, `1366 x 768`, and `1440 x 900`, the workspace looks calm, but equal-weight panels and repeated badges make it harder to distinguish the active work from reference information.

Across roles, the header generally answers "what is happening now", while the body still behaves like a long collection of panels. Phase 5A should keep the header as the operational command surface and flatten the body into sections, rows, lists, and progressive disclosure.

## Spacing Findings

Spacing inconsistency is part of the workspace-density defect. The screenshots show the same
problem the source confirms: nested panels multiply padding, empty states occupy more space than
active records, and similar controls use unrelated gap values.

Observed issues:

- The command header, tab row, and pipeline panel use different vertical relationships across
  mobile, tablet, and desktop. The first viewport reads as three separate blocks instead of one
  operational workspace.
- Overview and Fulfilment create double padding through `panel` / `table-panel` surfaces with
  additional bordered child cards.
- The Price and Documents empty states consume large vertical blank areas even though they contain
  less actionable content than active financial or fulfilment rows.
- Money uses metric tiles, date rows, action rows, and receipt history with different gap systems
  inside one panel.
- Activity mixes readiness alert spacing, description-grid spacing, bordered activity items,
  dashed add-activity form spacing, event timeline spacing, and comment-composer spacing.
- Button groups, status badges, metadata rows, and row-local document actions do not share a
  predictable 4/8/12/16/20/24/32px rhythm.
- Mobile keeps the desktop stack and padding model, causing the header and first body panel to
  crowd out supporting details.

Acceptance requirement:

- Use Phase 3 spacing tokens as the source of truth.
- For the Phase 5 ticket workspace, default to 4, 8, 12, 16, 20, 24, and 32px relationships.
- Do not introduce new one-off spacing values to make a single screenshot line up.
- Remove obsolete inner padding when converting nested cards into sections, rows, description
  lists, tables/lists, inline alerts, or collapsed disclosures.

## Classification Legend

Hierarchy levels:

- `L0`: workspace chrome or navigation context.
- `L1`: primary workspace panel or tab surface.
- `L2`: section, group, row set, list, or table inside a tab.
- `L3`: repeated item, nested block, form group, or control cluster.
- `Temp`: modal, drawer, dialog, or temporary editing layer.

Classifications must use the Phase 5A vocabulary:

- `KEEP AS PANEL`
- `CONVERT TO SECTION`
- `CONVERT TO ROW`
- `CONVERT TO DESCRIPTION LIST`
- `CONVERT TO TABLE/LIST`
- `CONVERT TO INLINE ALERT`
- `MOVE TO ANOTHER TAB`
- `COLLAPSE BY DEFAULT`
- `REMOVE DUPLICATION`

## Visible Container Inventory

| Component | Tab | Role | Purpose | Level | Actionable | Historical | Usually Empty | Duplicates Status/Value | Border | Background | Nested In Panel | Card Earns Existence | Classification | Proposed Replacement |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| `Breadcrumbs` | All | All | Return/context path for the ticket code | L0 | Yes | No | No | Yes, repeats ticket code in header | No | No | No | No | CONVERT TO ROW | Keep as a compact navigation row or fold the ticket code into the command header. |
| `DealStateHeader` outer surface | All | All | Operational identity, stage, work state, blocker, owner, deadline, next action | L1 | Yes | No | No | Yes, with pipeline/payment/detail panels | Yes | Yes | No | Yes | KEEP AS PANEL | Keep as the one command panel. Make it the source of truth for current stage, state, blocker, owner, and primary action. |
| `DealStateHeader` identity and badges | All | All | Deal code, lifecycle, stage, work-state chips | L2 | No | No | No | Yes, repeated in pipeline, payment, quotation, fulfilment | Chip only | Tonal | Yes | No | REMOVE DUPLICATION | Keep these values in the header and remove repeated current-state badges from body sections unless tab-local context requires them. |
| `DealStateHeader` responsibility details | All | All | Owner, responsible role, blocker, due date | L2 | No | No | Sometimes | Yes, overlaps overview info and payment due values | Divider | No | Yes | No | CONVERT TO DESCRIPTION LIST | Keep a compact description list focused on current handoff and blocker. Move creator/importer/reference fields to Overview. |
| `DealStateHeader` action cluster | All | Role-specific | Current user's next action plus refresh/secondary action | L2 | Yes | No | Sometimes | Yes, panel actions repeat the same workflow move | Button borders | No | Yes | No | CONVERT TO ROW | Preserve one dominant primary action. Move refresh and secondary actions into a quieter utility row or overflow menu. |
| `Tabs` tablist | All | All | Role-projected navigation across workspace details | L1 | Yes | No | No | No | Bottom border | No | No | No | CONVERT TO ROW | Keep as a compact sticky tab row below the header. Use mobile horizontal scroll without duplicating tab content in the Overview stack. |
| `TicketDetailPage` tab panel wrapper | All | All | Layout wrapper for active tab content | L1 | No | No | No | No | No | No | No | No | CONVERT TO SECTION | Replace old `ticket-detail-grid` assumptions with tab-specific sections and lists. Avoid decorative wrapper semantics. |
| `ticket-detail-grid` | Overview, Price, Quotation, Fulfilment, Documents, Activity | All visible roles | Legacy two-column page layout shell | L2 | No | No | No | No | No | No | No | No | CONVERT TO SECTION | Remove as the default body pattern. Each tab should own its layout instead of inheriting a desktop grid. |
| `DealStagePanel` outer panel | Overview | All | Full pipeline status, current step, status chips, stage actions | L1 | Yes | No | No | Yes, repeats header stage/state/action | Yes | Yes | No | Partly | CONVERT TO SECTION | Convert to an unbordered current-work section under the header. It should clarify the current stage rather than restate the header. |
| `PhaseTracker` phase bars | Overview | All | Shows five connected pipeline phases | L2 | No | No | No | Yes, repeats current stage | Minimal | Progress tones | Yes | No | CONVERT TO ROW | Keep as a compact connected stage row. Use it for orientation only, not as another equal-weight panel. |
| Current stage block in `DealStagePanel` | Overview | All | Stage number, stage name, phase, owner role, age in stage | L2 | Partly | No | No | Yes, repeats header stage and responsibility | No | No | Yes | No | CONVERT TO DESCRIPTION LIST | Fold stage age and owner into the current-work section as a small description list. |
| Stage substate chip rows | Overview | All | Payment, deposit, import, delivery, and fulfilment progress | L2 | Partly | No | No | Yes, repeated in Money and Fulfilment tabs | Chip only | Tonal | Yes | No | CONVERT TO ROW | Keep only blocker/action chips in Overview. Link supporting details to the relevant tab. |
| Stage primary action row | Overview | Role-specific | Workflow transition action for the current role | L2 | Yes | No | Sometimes | Yes, competes with header primary action | Button borders | No | Yes | No | REMOVE DUPLICATION | Render the next workflow action once, in the header or sticky action bar. |
| Stage document action row | Overview | Sales, Account, Import, CEO | Document-related follow-up actions | L2 | Yes | No | Sometimes | Yes, overlaps Documents tab | Button borders | No | Yes | No | MOVE TO ANOTHER TAB | Move document-specific commands to Documents or to the secondary action overflow. |
| Expanded 14-step pipeline | Overview | All | Full process reference | L3 | No | Reference | Usually hidden | No | Yes when expanded | Yes | Yes | No | COLLAPSE BY DEFAULT | Keep behind "ดูขั้นตอนทั้งหมด" or a drawer. Do not let full history compete with current work. |
| Lifecycle hold/lost/cancel warning | Overview | Conditional | Current blocker or terminal-state warning | L2 | Sometimes | No | Usually empty | Yes, overlaps header blocker/work state | Yes | Tonal | Yes | No | CONVERT TO INLINE ALERT | Surface only when active, preferably near the command header or current-work section. |
| Other actions panel | Overview | Sales, Sales manager, CEO | Revise, cancel, and secondary workflow actions | L1 | Yes | No | Sometimes | Yes, repeats/competes with current action | Yes | Yes | No | No | COLLAPSE BY DEFAULT | Move into "การดำเนินการอื่น" overflow. Destructive actions must stay visually secondary. |
| Revise inline form | Overview | Sales, Sales manager, CEO | Temporary reason capture before revise action | L2 | Yes | No | Usually hidden | No | Yes | Yes | Yes | Yes | KEEP AS PANEL | Keep as a temporary editing layer, preferably a modal or focused inline disclosure opened from overflow. |
| General information panel | Overview | All | Customer, creator, created date, import assignee, update date | L2 | No | Partly | No | Yes, overlaps header owner/context | Yes | Yes | Yes | No | CONVERT TO DESCRIPTION LIST | Convert to a quiet Overview description list. Keep it below current work. |
| `InfoRow` rows | Overview | All | Label/value rows inside general info | L3 | No | Partly | Sometimes | Partly | Row dividers | No | Yes | No | CONVERT TO ROW | Use semantic description rows without card styling. Empty values should collapse or show compact fallback text. |
| Items table panel | Overview | All | Deal item list with quantities and approved prices | L2 | Sometimes | No | No | No | Yes | Yes | Yes | Yes | CONVERT TO TABLE/LIST | Keep as a table/list. Remove elevated panel treatment unless it is the only Overview detail surface. |
| Item edit row cards | Overview | Sales, Sales manager, CEO | Inline item edits | L3 | Yes | No | Usually hidden | No | Yes | Yes | Yes | Partly | CONVERT TO TABLE/LIST | Use inline table row editing or a temporary edit layer instead of row cards inside a panel. |
| `PricingRequestPanel` outer table panel | Price | Sales, Sales manager, Import, CEO | Pricing request queue for this deal | L2 | Yes | No | Sometimes | No | Yes | Yes | Yes | No | CONVERT TO TABLE/LIST | Make the Price tab a list/table surface. Header action should be a row-level command, not another panel affordance. |
| Pricing request empty state | Price | Sales, Sales manager, Import, CEO | Shows no pricing requests | L3 | Partly | No | Often for early/empty deals | No | No | No | Yes | No | CONVERT TO INLINE ALERT | Replace large empty-state block with compact empty copy plus one tab-local action when creation is allowed. |
| Pricing request create action | Price | Sales, Sales manager, CEO | Opens request creation | L3 | Yes | No | Sometimes | Yes if header also recommends pricing action | Button | No | Yes | No | CONVERT TO ROW | Keep as the tab-local primary only when the header is not showing a stronger workflow action. |
| Pricing request list item | Price | Conditional | Distinct pricing request record | L3 | Yes | Partly | No | Yes, may duplicate PCR detail page status | Yes | Yes | Yes | Yes | CONVERT TO TABLE/LIST | Keep as a repeated comparable record, but style as a compact list row with expandable details. |
| Pricing request expanded details | Price | Conditional | Vendor/item/response details and actions | L4 | Yes | Partly | Usually collapsed | Yes, overlaps full PCR detail page | Yes | Yes | Yes | Partly | COLLAPSE BY DEFAULT | Keep only summary rows in ticket. Link deep work to the Pricing Request detail page. |
| Pricing request modals | Price | Sales, Import, CEO | Create, edit, respond, info, cancel workflows | Temp | Yes | No | Usually hidden | No | Yes | Yes | No | Yes | KEEP AS PANEL | Keep as temporary layers. Ensure one clear primary action in each modal. |
| Legacy quotation panel | Quotation | Sales, Sales manager, Account, CEO | Existing quotation documents grouped by issuer/customer | L2 | Yes | Partly | No | Yes, overlaps Documents tab and quotation status | Yes | Yes | Yes | No | CONVERT TO TABLE/LIST | Convert to a document list/table with issuer/customer grouping as row metadata. |
| Quotation issuer/customer groups | Quotation | Sales, Sales manager, Account, CEO | Separates internal and customer quotation records | L3 | Yes | Partly | Sometimes | No | Row dividers | No | Yes | No | CONVERT TO TABLE/LIST | Use one table/list with a "ประเภท" column or grouped headings, not separate panel sections. |
| Quotation export buttons | Quotation | Sales, Sales manager, Account, CEO | Download Excel/PDF | L3 | Yes | No | No | Yes, same document concern as Documents tab | Button borders | No | Yes | No | CONVERT TO ROW | Keep as row-local secondary actions. Avoid competing with workflow transition actions. |
| `DealQuotationPanel` outer table panel | Quotation | Conditional | New quotation workflow surface when mounted | L2 | Yes | No | Sometimes | Yes, overlaps legacy quotation/doc tabs | Yes | Yes | Yes | No | CONVERT TO SECTION | Flatten into quotation work section plus document record list. |
| Current quotation card | Quotation | Conditional | Active quotation external record | L3 | Yes | Partly | Sometimes | No | Yes | Yes | Yes | Yes | CONVERT TO TABLE/LIST | Treat as an external document row with expandable metadata. |
| Create quotation empty/create card | Quotation | Conditional | Starts quotation creation | L3 | Yes | No | Often before quote exists | No | Yes | Yes | Yes | No | CONVERT TO INLINE ALERT | Replace with a compact empty prompt and one action when allowed. |
| Quotation outcome recorder | Quotation | Conditional | Decision capture for win/loss/next step | L3 | Yes | No | Usually hidden until quote stage | Yes, overlaps stage action | Yes | Yes | Yes | Yes | KEEP AS PANEL | Keep only when it is the current decision. Otherwise collapse or move to activity/history. |
| Confirm-order card | Quotation | Conditional | Important decision before order/deposit flow | L3 | Yes | No | Usually hidden | Yes, competes with header action | Yes | Yes | Yes | Yes | KEEP AS PANEL | Keep as the active decision surface only when it is the current stage. Header should point to it. |
| Payment panel | Money | Sales, Sales manager, Account, CEO | Outstanding balance, paid amount, due dates, payment actions, receipt history | L1 | Yes for account | Partly | No | Yes, repeats header blocker/deadline | Yes | Yes | No | Partly | KEEP AS PANEL | Keep as the Money tab's main work panel. Remove nested metric cards and repeated state badges. |
| Payment status badges | Money | Sales, Sales manager, Account, CEO | Payment state and overdue state | L2 | No | No | No | Yes, repeats header work state | Chip only | Tonal | Yes | No | REMOVE DUPLICATION | Use only for tab-local nuance. The header owns the global blocker/current state. |
| Payment metric tiles | Money | Sales, Sales manager, Account, CEO | Amount due, paid, remaining | L2 | No | No | No | Partly | Yes | Yes | Yes | No | CONVERT TO ROW | Convert to a compact financial summary row or description list. |
| Payment due/follow-up rows | Money | Sales, Sales manager, Account, CEO | Billing date, due date, next follow-up | L2 | No | No | Sometimes | Yes, header shows deadline/blocker | Row spacing | No | Yes | No | CONVERT TO DESCRIPTION LIST | Keep only financial detail values here. Header should show the operational deadline. |
| Payment action row | Money | Account, CEO | Record payment and billing schedule actions | L2 | Yes | No | Sometimes | Yes, header may show final payment action | Button borders | No | Yes | No | CONVERT TO ROW | Account tab can own the tab-local primary when user is in Money. Header should expose only one dominant action. |
| Receipt history | Money | Sales, Sales manager, Account, CEO | Historical payment records | L2 | No | Yes | Often empty | No | Divider | No | Yes | Yes | CONVERT TO TABLE/LIST | Use a compact table/list. Empty receipt history should not consume large vertical space. |
| Payment and billing modals | Money | Account, CEO | Record payment, final payment, schedule billing | Temp | Yes | No | Usually hidden | No | Yes | Yes | No | Yes | KEEP AS PANEL | Keep as temporary layers with clear submit/cancel hierarchy. |
| `DealDepositPanel` outer table panel | Fulfilment | Sales, Sales manager, Account, CEO | Deposit policy, notice, and deposit payment workflow | L2 | Yes | No | Sometimes | Yes, overlaps Money and header blocker | Yes | Yes | Yes | No | CONVERT TO SECTION | Represent deposit as part of one connected fulfilment process, not a separate panel stack. |
| Deposit policy step card | Fulfilment | Sales, Sales manager, Account, CEO | Deposit policy state and reason | L3 | Sometimes | No | No | Yes, chip also appears in stage panel | Yes | Yes | Yes | No | CONVERT TO ROW | Convert to a process row with status, owner, and one contextual action. |
| Deposit notice step card | Fulfilment | Sales, Sales manager, Account, CEO | Deposit notice generation/signing | L3 | Yes | No | Sometimes | Yes, overlaps Documents | Yes | Yes | Yes | No | CONVERT TO ROW | Convert to a process row. Move document preview/export into row details or Documents. |
| Deposit payment step card | Fulfilment | Sales, Sales manager, Account, CEO | Deposit payment receipt/confirmation | L3 | Yes | No | Sometimes | Yes, overlaps Money | Yes | Yes | Yes | No | CONVERT TO ROW | Convert to a process row and link payment detail to Money. |
| Deposit notice preview iframe | Fulfilment | Conditional | External document preview | L4 | No | Reference | Usually hidden | Yes, overlaps Documents | Yes | Yes | Yes | Yes | COLLAPSE BY DEFAULT | Open preview in a temporary drawer/modal or Documents tab detail. |
| `DealFulfilmentPanel` outer table panel | Fulfilment | Sales, Sales manager, Import, Account, CEO | Import, delivery, and factory purchase work | L2 | Yes | No | No | Yes, overlaps stage panel import/delivery chips | Yes | Yes | Yes | No | CONVERT TO SECTION | Make this the connected operational process for purchasing and delivery. |
| Import step card | Fulfilment | Import, CEO, Sales visible | Import checkpoints and stock reservation | L3 | Yes for import | No | No | Yes, duplicates Overview chips | Yes | Yes | Yes | No | CONVERT TO ROW | Convert to the active process row when import owns the work. |
| Delivery step card | Fulfilment | Import, CEO, Sales visible | Delivery counts, delivery actions, item delivery rows | L3 | Yes for import | No | No | Yes, duplicates Overview delivery chips | Yes | Yes | Yes | No | CONVERT TO ROW | Convert to a process row with nested item list only when expanded. |
| Delivery item rows | Fulfilment | Import, CEO, Sales visible | Per-item delivered/remaining quantities | L4 | Yes for import | No | No | No | Yes | Yes | Yes | Yes | CONVERT TO TABLE/LIST | Use a compact item delivery table. Avoid card-like rows inside the delivery row. |
| Delivery history list | Fulfilment | Import, CEO, Sales visible | Past deliveries | L4 | No | Yes | Often empty | No | Minimal | No | Yes | Yes | COLLAPSE BY DEFAULT | Keep historical delivery records collapsed under the process row. |
| Factory PO step card | Fulfilment | Import, CEO | Factory PO status and actions | L3 | Yes for import | No | Sometimes | Yes, overlaps Documents | Yes | Yes | Yes | Yes | CONVERT TO ROW | Treat as a process row or external-record row, with PO files linking to Documents. |
| Factory PO rows or empty state | Fulfilment | Import, CEO | Factory PO records or absence | L4 | Yes | Partly | Often empty | No | Yes/empty-state | Yes | Yes | Yes | CONVERT TO TABLE/LIST | Use a compact PO list with an inline empty row. |
| Fulfilment action buttons | Fulfilment | Import, CEO | Reserve stock, issue PO, mark import/delivery state | L3 | Yes | No | Sometimes | Yes, may compete with header action | Button borders | No | Yes | No | CONVERT TO ROW | Row-local actions should be secondary unless this tab owns the current stage. |
| Fulfilment modals | Fulfilment | Import, CEO | Delivery, stock, PO, and import action capture | Temp | Yes | No | Usually hidden | No | Yes | Yes | No | Yes | KEEP AS PANEL | Keep as temporary layers. Each modal should expose one primary submit action. |
| Attachments panel | Documents | All visible roles | Uploaded PO, signed documents, tax invoices, and supporting files | L2 | Yes | Partly | Often empty | Yes, overlaps quote/deposit/factory PO document controls | Yes | Yes | Yes | No | CONVERT TO TABLE/LIST | Make Documents the canonical file list with compact category rows and upload controls. |
| Upload control row | Documents | All visible roles | Upload file and document type | L3 | Yes | No | No | Yes, document upload actions appear elsewhere | Button border | No | Yes | No | CONVERT TO ROW | Keep as a tab-local utility row. Prefer shared `FileUploadField` behavior and design tokens. |
| Attachment empty state | Documents | All visible roles | No attached files | L3 | No | No | Often | No | No | No | Yes | No | CONVERT TO INLINE ALERT | Replace large empty state with compact inline empty row. |
| Attachment file rows | Documents | Conditional | Distinct uploaded external records | L3 | Yes | Partly | No | No | Yes | Yes | Yes | Yes | CONVERT TO TABLE/LIST | Keep as file rows with row-local download/delete actions. |
| Attachment delete confirmation | Documents | Conditional | Destructive file delete confirmation | Temp | Yes | No | Usually hidden | No | Yes | Yes | No | Yes | KEEP AS PANEL | Keep as `ConfirmDialog`. Destructive action stays visually secondary until confirmation. |
| `DealTrackingPanel` outer panel | Activity | Sales, Sales manager, CEO | Current tracking metadata, readiness, and activity input | L2 | Yes | Partly | No | Yes, overlaps Activity history and header next action | Yes | Yes | Yes | Partly | CONVERT TO SECTION | Split active tracking work from historical activity. Use description rows plus inline readiness alert. |
| Tracking readiness alert | Activity | Sales, Sales manager, CEO | Warns that activity must be recorded before status change | L3 | No | No | Usually conditional | Yes, overlaps header next-action blockers | Yes | Tonal | Yes | No | CONVERT TO INLINE ALERT | Keep near the relevant action only. Do not bury it in a general tracking panel. |
| Tracking description grid | Activity | Sales, Sales manager, CEO | Win probability, next follow-up, specifier, owner, buyer | L3 | No | No | Some fields empty | Partly | Row dividers | No | Yes | No | CONVERT TO DESCRIPTION LIST | Convert to compact description list. Hide empty values or display compact placeholders. |
| Tracking edit form | Activity | Sales, Sales manager, CEO | Edit follow-up metadata | L3 | Yes | No | Usually hidden | No | Form field borders | No | Yes | Yes | KEEP AS PANEL | Treat edit mode as a temporary layer inside the Activity tab. |
| Activity log list | Activity | Sales, Sales manager, CEO | Recent sales activities | L3 | No | Yes | Sometimes | Yes, overlaps ticket event history | No | No | Yes | Yes | CONVERT TO TABLE/LIST | Keep as a compact timeline/list under Activity. Do not give every item a decorative card. |
| Activity log item cards | Activity | Sales, Sales manager, CEO | Individual tracked interaction record | L4 | No | Yes | No | Yes, event history may mirror entries | Yes | Yes | Yes | Partly | CONVERT TO ROW | Render as timeline rows with light dividers, not bordered cards inside a panel. |
| Add activity form | Activity | Sales, Sales manager, CEO | Capture follow-up activity/comment | L3 | Yes | No | Usually available | No | Dashed border | Yes | Yes | Yes | KEEP AS PANEL | Keep as an action form only when expanded or when Activity tab is active. |
| Role-hidden tracking peek | Activity | Import, Account | Explains tracking is handled by sales | L2 | No | No | Conditional | No | Yes | Tonal | Yes | No | CONVERT TO INLINE ALERT | Replace with a compact role-scope notice. It should not become an empty panel substitute. |
| History panel | Activity | All | Ticket event history and comment composer | L2 | Yes for comments | Yes | No | Yes, overlaps tracking activities | Yes | Yes | Yes | Partly | CONVERT TO TABLE/LIST | Keep historical events in Activity only. Use compact timeline/list density. |
| Ticket event timeline | Activity | All | Chronological workflow event records | L3 | No | Yes | No | Yes, repeats state changes from panels | Timeline line | No | Yes | Yes | CONVERT TO TABLE/LIST | Use an event list/timeline with subdued styling. Current work should remain above history. |
| Event message and snapshot insets | Activity | All | Extra event details or structured snapshots | L4 | No | Yes | Sometimes | Yes, can repeat field values | Yes | Tonal | Yes | Partly | COLLAPSE BY DEFAULT | Collapse verbose event details behind disclosure. |
| Comment composer | Activity | All | Add ticket-level comment | L3 | Yes | No | No | Partly, overlaps activity add form | Input borders | No | Yes | Yes | CONVERT TO ROW | Keep as one compact composer. Clarify difference between comment and sales activity. |
| Shared `EmptyState` blocks | Price, Documents, Money, Fulfilment | All visible roles | Empty list placeholders | L3 | Sometimes | No | Often | No | No | No | Yes | No | CONVERT TO INLINE ALERT | Replace large 220px empty blocks with compact empty rows inside the relevant list. |
| Shared `Skeleton` state | Initial load | All | Loading placeholder before ticket data resolves | L1 | No | No | Transient | No | Yes | Yes | No | Yes | KEEP AS PANEL | Keep loading panel, but match final workspace geometry to reduce layout shift. |
| Shared `Modal` | Conditional | All | Temporary create/edit/confirm workflow layer | Temp | Yes | No | Usually hidden | No | Yes | Yes | No | Yes | KEEP AS PANEL | Keep temporary layers. Address known accessibility contract issues separately. |
| Shared `ConfirmDialog` | Conditional | All | Destructive or irreversible confirmation | Temp | Yes | No | Usually hidden | No | Yes | Yes | No | Yes | KEEP AS PANEL | Keep as the only elevated destructive confirmation surface. It must not compete with the next workflow action. |

## Primary Restructuring Decisions

1. Make `DealStateHeader` the single current-work command panel.
2. Replace the Overview body with an unbordered current-stage section, a compact item list, and quiet reference description lists.
3. Treat Price, Quotation, Money, Fulfilment, Documents, and Activity as focused tabs, not as desktop panels stacked on mobile.
4. Convert workflow stage cards in Deposit and Fulfilment into one connected process list.
5. Move historical records to Activity and collapse verbose details by default.
6. Move document records and upload/download/delete actions to Documents wherever possible.
7. Replace large empty-state panels with compact inline empty rows.
8. Enforce one dominant primary action for the current role. Secondary, destructive, and historical actions belong in row-local controls, tab-local controls, or "การดำเนินการอื่น".
9. Normalize touched workspace spacing to the Phase 5 rhythm: 20px desktop section padding, 16px mobile section padding, 16px header-to-tabs and tabs-to-content gaps, 24px major section separation, 8px action/control gaps, and 12px compact alert/list padding.
10. Treat spacing reduction as part of flattening the hierarchy. Do not leave old panel padding in place after removing card boundaries.
11. Apply the strict first-viewport contract in `PHASE_5A_FIRST_VIEWPORT_CONTRACT.md`: no five-card summary row, compact metadata strip, compact stage display, one next-action slot, and tab navigation as the route to supporting detail.
12. Apply the tab simplification contract in `PHASE_5A_TAB_SIMPLIFICATION_CONTRACT.md`: Overview as concise summary; Pricing as active request list; Quotations as dense document rows; Money as compact financial rows; Fulfilment as two connected tracks; Documents as grouped file rows; Activity as one chronological structure.
13. Apply the badge audit in `PHASE_5A_BADGE_AUDIT.md`: one primary stage badge, one work-state badge or alert, local sub-record badges only, no badge walls for sequences, no metadata pills, and no repeated `กำลังดำเนินการ` badge across levels.
14. Apply the action hierarchy in `PHASE_5A_ACTION_HIERARCHY.md`: one primary next action per role/state, no equal-weight action wall, up to two secondary actions before overflow, document/navigation actions separated from workflow actions, and destructive actions confirmed.
15. Apply the mobile workspace contract in `PHASE_5A_MOBILE_WORKSPACE_CONTRACT.md`: do not reproduce the desktop body stack at `390 x 844`; keep tabs reachable, active tab content near the top, one primary action reachable, safe-area padding present, empty states compact, tables/lists bounded, modals/drawers focused, and page-level horizontal scrolling forbidden.
16. Apply the component-boundaries contract in `PHASE_5A_COMPONENT_BOUNDARIES.md`: extract semantic components only when they reduce complexity; do not introduce a generic `Card` component or new `*Card` ticket workspace components to preserve the current card-heavy hierarchy.
17. Apply the CSS and visual-cleanup contract in `PHASE_5A_CSS_VISUAL_CLEANUP.md`: remove unnecessary border layers, rounded containers, duplicate backgrounds, excessive padding, one-off shadows and repeated inline layout styles while preserving readable density, 44px touch targets, Thai sizing, visible focus and meaningful structure.
18. Apply the state-feedback contract in `PHASE_5A_STATE_FEEDBACK_CONTRACT.md`: distinguish ticket loading, ticket load error, ticket not found, tab loading, tab error, tab empty, permission-limited content, not-applicable content and completed content. Do not display raw server exceptions.

## Gate Result

This audit confirms the main structural problem is not visual polish. The ticket page already has a strong operational header, but the body still uses equal-weight panels, nested bordered cards, repeated status surfaces, badge walls, metadata pills, equal-weight action rows, inconsistent first-viewport rhythm, duplicate visual layers, ambiguous empty/loading/permission states and a mobile layout that risks copying the desktop stack into one narrow column. Phase 5A implementation should now proceed by flattening the workspace hierarchy, assigning each piece of information to one operational home, extracting only semantic components that make that ownership clearer, cleaning CSS only inside the verified ticket-detail slice, and making each workspace state explain what the user should do next.
