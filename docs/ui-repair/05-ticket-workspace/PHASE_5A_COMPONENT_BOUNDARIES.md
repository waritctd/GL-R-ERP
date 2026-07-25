# Phase 5A Component Boundaries

Date: 2026-07-25

Scope: `/tickets/:id` ticket workspace decomposition during Phase 5A implementation.

This contract is Step 19. Extract focused components where they reduce complexity, clarify
ownership, or encode an operational concept. Do not extract decorative boxes.

## Boundary Principle

Components must encode semantic roles, not visual containers.

Good component names describe the job users understand:

- `DealFinancialSummary`
- `WorkflowStepList`
- `DescriptionList`
- `DealDocumentList`

Avoid component names that preserve the old card-heavy structure:

- `InfoCard`
- `SmallCard`
- `StatusCard`
- `DetailCard`
- `GenericCard2`
- `TicketCard`
- `WorkspaceCard`

Do not create a generic `Card` component for this phase. The one-panel-deep rule still applies:
a component may own spacing and grouping, but it must not become another bordered card layer
inside a panel.

## Extraction Threshold

Extract a component only when it satisfies at least two of these conditions:

- It owns a semantic region of the workspace.
- It removes meaningful complexity from `TicketDetailPage.jsx` or a tab file.
- It has role-specific or state-specific rendering that needs focused tests.
- It appears in more than one tab or workflow track with the same contract.
- It owns a responsive behaviour that would be hard to audit inline.
- It isolates a temporary layer, list, timeline, summary or action hierarchy.
- It prevents duplicated status, spacing, badge or action logic.

Do not extract when:

- The JSX is a one-line wrapper.
- The component only adds margin, padding, border or background.
- The component has no semantic name beyond "card", "box", "panel" or "section".
- The component passes through most props unchanged and hides no complexity.
- The component duplicates an existing shared primitive such as `Button`, `StatusBadge`,
  `Modal`, `ConfirmDialog`, `FileUploadField`, `Tabs`, `DescriptionList` or `Timeline`.
- The component needs new business logic, permission rules, API contracts or status values.

## Ownership Layers

| Layer | Owns | Must not own |
|---|---|---|
| `TicketDetailPage.jsx` | Route orchestration, data fetching, mutations, cache updates, URL tab state, role projection, action resolver inputs | Decorative layout details, row rendering, tab body internals |
| Workspace semantic components | Header, metadata strip, work-state banner, workflow summary, action bar, tab projection adapters | API calls, permission changes, cache invalidation, backend state-machine assumptions |
| Tab panels | The active tab's job, local rows/lists/forms, role-sensitive display using existing gates | Global current-work truth, duplicated primary action, hidden-tab query side effects not documented |
| Shared primitives | Accessibility, keyboard behaviour, common state patterns, tokenized spacing | Ticket-specific business vocabulary or deal workflow decisions |
| Temporary layers | Modal/drawer/dialog form focus and local submit/cancel hierarchy | Competing with the sticky primary action or background page controls |

Server `availableActions` remains authoritative. Component extraction must not widen what a role
can do.

## Approved Workspace Components

These are approved semantic targets when they meet the extraction threshold.

| Component | Intended location | Semantic purpose | Owns | Must not own |
|---|---|---|---|---|
| `DealWorkspaceHeader` | `frontend/src/features/tickets/workspace/` or the existing `DealStateHeader.jsx` file if renamed/chosen deliberately | Persistent command header for the operational record | Deal identity, compact current-stage context, work-state slot, metadata strip slot, refresh utility slot | Full pipeline, historical events, five metric cards, multiple primary actions |
| `DealMetadataStrip` | `frontend/src/features/tickets/workspace/` | Inline label/value strip for cross-track metadata | Compact pairs such as pricing, payment, import and deal value | Rounded cards, badges for ordinary metadata, duplicated stage/work-state labels |
| `DealWorkStateBanner` | `frontend/src/features/tickets/workspace/` | Viewer-specific current work state | Needs action, waiting, blocked, returned, overdue, terminal and informational messaging from `dealWorkState.js` | Backend permission decisions, invented actions, long messages inside badges |
| `DealWorkflowSummary` | `frontend/src/features/tickets/workspace/` | Compact cross-track Overview summary | Price, quotation, deposit, procurement, delivery, payment and close rows with owning tab links | Seven cards, full child-panel duplication, child-tab action walls |
| `DealWorkspaceTabs` | `frontend/src/features/tickets/workspace/` | Ticket-specific adapter around shared `Tabs` | Role-projected tab list, `?tab=` ids, fallback to Overview, mobile tab reachability | Reimplementing keyboard tab semantics already owned by `Tabs.jsx` |
| `DealOverviewPanel` | `frontend/src/features/tickets/tabs/` | Overview tab body as concise operational summary | Current-work alert, deal description rows, item summary, workflow summary, recent activity cap | Pricing/Money/Fulfilment/Documents/Activity as full stacked panels |
| `DealItemSummary` | `frontend/src/features/tickets/tabs/` or `workspace/` | Deal item table/list and edit-mode display | Product identity, quantity, role-sensitive price visibility, mobile record reflow, existing item edit field errors | Raw cost/margin leakage, new item mutation semantics, row cards inside cards |
| `DealActivityTimeline` | `frontend/src/features/tickets/tabs/` | Ticket activity/history adapter around shared `Timeline` | Events, comments and follow-up records as one chronological structure | A second history panel, duplicated tracking cards, permanent large composer |
| `DealDocumentList` | `frontend/src/features/tickets/tabs/` | Canonical document and attachment list | Grouped dense file rows, row-local download/delete/upload state | Document actions scattered across stage panels, large empty document cards |
| `DealFinancialSummary` | `frontend/src/features/tickets/tabs/` | Money tab summary | Aligned financial rows, tabular numbers, due/follow-up dates, permitted finance action context | Three metric cards, repeated global blocker/status badges |
| `DealActionBar` | `frontend/src/features/tickets/workspace/` | Ticket-specific adapter around shared `StickyActionBar` | One primary action, up to two secondary actions, overflow, separated destructive actions, safe-area behaviour | Business action invention, hidden destructive actions without confirmation, five equal buttons |
| `CompactEmptySection` | `frontend/src/components/common/` only if used by multiple tabs; otherwise keep inline | Compact, action-aware state row for successful empty/not-applicable/completed cases | Small empty row/alert with optional onward action and explicit state reason | A roomy decorative empty card, illustration-first layout, generic card styling, loading/error/permission states disguised as empty |
| `WorkflowStepList` | `frontend/src/features/tickets/workspace/` or common only after multiple non-ticket consumers exist | Ordered current/complete/waiting step list | Connected stage, deposit, import and delivery steps with current-step emphasis | Full pipeline dominance, badge wall for every step, hidden role/action rules |

Use existing child workflow panels as extraction boundaries where they already own real business
flows: `PricingRequestPanel`, `DealQuotationPanel`, `DealDepositPanel`, `DealFulfilmentPanel`.
Phase 5A may simplify their presentation, but it must not bury their existing query/mutation
contracts inside new generic wrappers.

## Naming Rules

- Prefix ticket-specific components with `Deal` when they encode ticket/deal semantics.
- Use shared primitive names only for reusable behaviour: `Tabs`, `Timeline`,
  `DescriptionList`, `StickyActionBar`, `InlineAlert`.
- Do not use `Card` in new Phase 5A ticket workspace component names.
- Do not create a component name that describes size or decoration: `Small`, `Tiny`, `Box`,
  `Tile`, `Pretty`, `Panel2`.
- Prefer `Summary`, `List`, `Timeline`, `StepList`, `Banner`, `Strip`, `Tabs`, `ActionBar`
  when those words describe the semantic job.

Existing shared `StatCard` remains a KPI primitive elsewhere in the app. It is not approved for
rebuilding the ticket header's five equal-weight summary cards.

## Prop And State Rules

- Prefer explicit data props over passing the entire `ticket` object everywhere.
- Pass callbacks by semantic name: `onRefresh`, `onOpenTab`, `onRecordPayment`,
  `onConfirmClose`, `onDeleteAttachment`.
- Keep query invalidation, `applyTicketUpdate`, `doAction`, `fieldErrors`, `fieldRefs` and URL
  tab state at the orchestration layer unless a moved form already owns them today.
- Component props may narrow what is rendered, but may not decide a role can act without
  `availableActions`.
- Avoid "prop tunnels" where a new component accepts twenty unrelated props only to forward them.
  If that happens, the boundary is wrong or the component should stay inline for now.

## Testing Rules

Every extracted component with conditional rendering needs a focused test.

Test the semantic contract, not the visual wrapper:

- Header answers identity/current-work questions without five cards.
- Metadata strip renders label/value pairs, not individual status cards.
- Work-state banner never shows `needs_my_action` without a backing action.
- Workflow summary links rows to owning tabs and does not duplicate child panels.
- Item summary hides unauthorized cost/margin and reflows for mobile.
- Financial summary uses rows rather than metric tiles.
- Document list keeps row-local document actions.
- Activity timeline uses chronological semantics and compacts history.
- Action bar exposes one primary action and separates destructive actions.
- Compact empty state stays small and action-aware, and does not stand in for loading, error or
  permission-limited content.
- Workflow step list distinguishes current, waiting and completed steps without a badge wall.

Snapshot-only tests are insufficient because this phase is about information architecture and
behavioural hierarchy.

## Acceptance Checks

Implementation is not complete until:

- `TicketDetailPage.jsx` is reduced to orchestration plus high-level workspace composition.
- Each extracted component has a semantic reason to exist.
- No new generic card component is introduced.
- No new Phase 5A ticket workspace component name contains `Card`.
- No component exists only to apply border, elevation, padding or background.
- Existing queries, mutations, cache keys, role projection and backend action gates remain
  unchanged.
- The one-panel-deep rule still holds after extraction.
- Mobile, spacing, badge and action hierarchy contracts remain easier to verify after
  extraction, not harder.
