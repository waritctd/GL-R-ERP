# Component Contracts

Styling + behaviour contracts for the shared components Phase 4 will build against.
**Proposal/spec — nothing is implemented here.** The rule from Phase 1/2 stands:
**extend the healthy shared system, do not rebuild it.** Each contract notes its
**current status** so Phase 4 knows whether it is *consolidating* an existing
primitive or *creating* a missing one.

Legend: **✅ exists** (mature) · **◐ partial** (exists, has gaps) · **➕ propose**
(no shared primitive yet). All live in `frontend/src/components/common/` unless
noted. Every component must specify **default / hover / focus / active / disabled /
loading / error** — "don't ship half of these."

Cross-cutting requirements (apply to all):
- **Focus:** a global `:focus-visible` ring on every interactive element (fixes A-03; the current `outline:none` at `styles.css:240,414,1634,1736` strips it in places).
- **Tokens only** — no literal hex/px a token covers (see [`TOKENS.md`](TOKENS.md)).
- **Both scripts** — Thai + English at the same size; ≥44px touch on mobile.
- **State is text, not colour alone.**
- **Semantic components only** — new feature components must encode a real job or record type,
  not a decorative box. Do not introduce a generic `Card` primitive to preserve card-heavy page
  structures; existing `StatCard` remains KPI-specific and must not be used for ticket workspace
  metadata strips.
- **Fewer visual layers, not no structure** — remove duplicate borders/backgrounds/radius only
  when headings, dividers, rows, alerts or semantic ordering still preserve current/waiting/
  completed/reference distinctions.

---

## 1. Button — ✅ exists (`Button.jsx`, cva)
- **Purpose:** the sole button primitive. **One button system** — legacy `.primary/.secondary/.danger/.icon-button` CSS classes are migrated onto this and retired (F-13).
- **Variants:** `primary` (filled indigo, the one high-emphasis action per context) · `secondary` (white, border, icon-muted text — the default) · `success` (filled green, approve/confirm only) · `danger` (**outlined**, not filled — quiet until pressed) · `text` (borderless, indigo — inline/back) · `icon` (44×44 square).
- **Sizes:** `md` (default, `min-h-[38px]`, `max-[720px]:min-h-[44px]`) · `sm` (`min-h-[32px]`).
- **States:** default ✅; disabled ✅ (opacity .55 + `not-allowed`); **hover/focus/active ◐** inherited from global CSS — must gain the global focus-visible ring; **loading ➕ MISSING** — add a `loading` prop (spinner + `aria-busy`, disables), so callers stop hand-swapping text.
- **A11y:** native `<button>`, `type="button"` default; **enforce an `aria-label` on the `icon` variant** (fixes A-06). Focus ring required.
- **Mobile:** 44px floor on `md`; `icon` always 44×44; `text` opts out (inline).
- **Tokens:** `action-primary/-hover`, `action-secondary`, `action-success`, `action-danger`, `border-input`, `text-inverse/-muted`.
- **Anti-patterns:** a second button system; a filled danger button; >1 primary per context; an icon button with no name.
- **Outside the component:** business logic, layout/placement, toast/side-effects.

## 2. Icon button — ✅ exists (`Button variant="icon"`)
- **Purpose:** a single-glyph action (close, refresh, paginate, menu).
- **Size:** 44×44 (36px for a documented `.icon-only` dense case).
- **States:** as Button; must show the focus ring.
- **A11y:** **required accessible name** (`aria-label` + `title`) — enforce it, don't rely on the caller (A-06). Glyph is `aria-hidden` via `Icon`.
- **Anti-patterns:** icon-only for a destructive action without a confirm; ambiguous glyph; nesting inside a clickable row (F-02).

## 3. Text input — ◐ partial (global `input` styles + `FormField`)
- **Purpose:** single-line text/number entry.
- **Sizes:** default 40px min-height; **16px font-size (keep — prevents iOS zoom)**.
- **States:** default ✅; focus ✅ (border→`border-focus` + ring); **error ◐** (border swaps, but wire `aria-invalid`/`aria-describedby` via FormField, A-04); disabled ✅; loading (rare) via container.
- **A11y:** label associated (`FormField`); error programmatic + non-colour cue.
- **Mobile:** full-width; icon/search fields pad to the icon.
- **Tokens:** `border-input`, `border-focus`, `focus-ring`, `surface-panel`, `text-primary`, `radius-control`.
- **Anti-patterns:** removing the outline without the ring; error by colour only; sub-16px on mobile.

## 4. Select / combobox — ➕ propose (native `<select>` used today)
- **Purpose:** choose from a known set; combobox where the set is large/searchable.
- **Sizes/states:** match Text input (40px, focus ring, disabled, error).
- **A11y:** native `<select>` preferred; a custom combobox needs full listbox semantics (`role`, `aria-activedescendant`, type-ahead, Escape) or it is not worth building. **Do not reinvent a select for flavour** (product ban).
- **Mobile:** native picker on touch.
- **Anti-patterns:** a div-based fake select without keyboard/AT support; clipping the popup inside an `overflow:hidden` panel (use popover/`fixed`/portal).

## 5. Checkbox / radio / switch — ◐ partial (native, no shared primitive)
- **Purpose:** boolean / one-of-many / instant toggle.
- **States:** default/checked/focus/disabled; switch conveys on/off with position + label, not colour alone.
- **A11y:** native inputs with associated labels; ≥44px touch target; focus ring; a switch uses `role="switch"` semantics if custom.
- **Anti-patterns:** a switch whose only signal is colour; a click target smaller than the label.

## 6. Status badge — ✅ exists (`StatusBadge.jsx`)
- **Purpose:** the single status pill; tone + Thai text (+ optional icon).
- **Variants:** the 6 tones (`neutral/info/success/warning/danger/indigo`); no size prop.
- **States:** static; interactive (button/link) variant grows to 44px.
- **A11y:** **text is mandatory** (never colour-only); icon additive; interactive badge is a real control with a name.
- **Tokens:** the `status-*` tone pairs; `radius-pill`.
- **Anti-patterns:** a unique colour per backend status; badging a non-state (use plain text); long explanations inside pills; a badge for every step in a sequence; an icon-only badge. (Full mapping: [`STATUS_PRESENTATION.md`](STATUS_PRESENTATION.md).)
- **Outside:** the value→tone mapping (that is `format.js`, the canonical hub — the badge only renders).

## 7. Inline alert — ➕ propose (only `Toast` + `DesktopOnlyNotice` today)
- **Purpose:** an in-context, non-transient message tied to a region (a form-level error, an "already approved" notice, an over-quota warning).
- **Variants:** `info / success / warning / danger` (the semantic tones); optional dismiss; optional retry/action slot for recoverable region failures.
- **States:** static; dismissible variant animates out (reduced-motion: instant).
- **A11y:** `role="status"`/`role="alert"` by severity; not colour-only; focus moved to it on a submitted error.
- **Anti-patterns:** a **side-stripe** `border-left` accent (absolute ban — use a full border + tinted bg); using a toast for a persistent condition; a modal where an inline alert fits; raw server exception text.

## 8. Toast — ✅ exists (`Toast.jsx`)
- **Purpose:** a transient, global confirmation/feedback ("บันทึกแล้ว").
- **Variants:** `success / error / info`.
- **States:** enter/auto-dismiss/manual-dismiss; error persists until dismissed.
- **A11y:** ✅ error = `role="alert" aria-live="assertive"`; else `role="status" aria-live="polite"`; dismiss is a real button.
- **Anti-patterns:** a toast for an error the user must fix (use inline alert); stacking many; firing a toast over a modal it belongs inside (F-06).

## 9. Page header — ✅ exists (`PageHeader.jsx`)
- **Purpose:** page identity + actions row (title · subtitle · actions).
- **States:** static.
- **A11y:** one `<h1>` per page; actions are real controls.
- **Mobile:** title wraps gracefully; actions collapse/stack; **subtitle must not overlap the toolbar** (F-01 tablet).
- **Anti-patterns:** a full-width decorative back bar in addition to the breadcrumb (F-14 — breadcrumb is the single up-nav); an oversized hero.

## 10. Filter bar — ➕ propose (layout helpers only, no shared primitive)
- **Purpose:** search + filter controls above a list, with **URL-persisted state**.
- **Regions:** search field · filter controls · result count/active-filter chips · clear.
- **States:** default; active-filter (chips); filtered-to-empty (offer clear-filter, distinct from truly-empty).
- **A11y:** labelled controls; result count announced; clear is a real button.
- **Mobile:** collapses into a filter sheet/drawer; the search stays reachable.
- **Anti-patterns:** filter state lost on reload (must be in the URL); a filter bar that pushes the list below the fold.

## 11. Data table — ◐ partial (`DataTable.jsx`, has a11y debt)
- **Purpose:** the one shared dense table (sort, search, paginate, CSV, mobile-card, expandable).
- **States:** loading ✅ (skeleton rows, `aria-busy`); empty ✅ (`EmptyState`); **error ◐** (caller-owned today — standardise an inline retry); overflow = contained scroll.
- **A11y:** **rebuild the contract** — no `<button>`-in-`<button>` (A-01/F-02); native `<table>` or a correct `grid` with `rowgroup`/`gridcell` (A-02/F-07); `aria-sort` (✅); skeleton rows not announced as data.
- **Row action:** one "open" affordance; per-row actions are **siblings**, never nested in a row-button.
- **Mobile:** `mobileCard` reflow (✅ mechanism); any caller **without** a `mobileCard` is a defect.
- **Tokens:** `surface-panel/-muted/-subtle/-raised`, `border-subtle`, Overline header type.
- **Anti-patterns:** per-page table CSS; the row-as-button pattern; wrapping cells; a caller without a mobile card.
- **Outside:** column definitions, data fetching, per-page grid widths.

## 12. Mobile record card — ◐ partial (`mobileCard` renderers: `DealCard`, etc.)
- **Purpose:** the mobile form of a table row — one scannable record.
- **Regions:** identity · status badge · 2–3 facts that matter · one primary full-width action.
- **States:** as Data table; tap target ≥44px.
- **A11y:** list-item/landmark semantics; status via text+badge.
- **Anti-patterns:** a squeezed desktop grid; more than one primary action; clipped Thai (F-12).
- **Consolidate:** the per-page card renderers into **one card contract** (they already share the mechanism).

## 13. Dialog / Modal — ◐ partial (`Modal.jsx` strong; `ConfirmDialog.jsx`)
- **Purpose:** a short, blocking, focused interruption. **Last resort** — exhaust inline/progressive first.
- **States:** open/close; `ConfirmDialog` busy = "กำลังดำเนินการ..." disables both buttons and blocks Escape/backdrop.
- **A11y:** ✅ focus-trap, Escape, restore, `role="dialog"`, `aria-modal`; **fix A-05:** label by the visible `<h2>` (`aria-labelledby`, not `aria-label`), describe the subtitle (`aria-describedby`), mark background `inert`.
- **Mobile:** full-screen sheet with fixed footer, safe-area padding, internal scroll for long
  content, and submit/cancel controls that remain reachable at `390 x 844`.
- **Consolidate:** `ChangePasswordModal` hand-rolls its own backdrop — move it onto `Modal.jsx` (F-19). `ConfirmDialog` footer uses legacy `.*-button` classes → migrate to `<Button>`.
- **Anti-patterns:** a modal as the first thought; a multi-step flow trapped in a modal (F-06); a modal that isn't focus-trapped.

## 14. Drawer — ➕ propose (mobile nav drawer exists in `AppShell`, not a primitive)
- **Purpose:** a side/bottom panel for context or a secondary list without leaving the page.
- **States:** open/close; overlay scrim (tokenise, TOKENS §overlay).
- **A11y:** focus-trap + Escape + restore (the nav drawer already models this); labelled; background inert.
- **Mobile:** full-height or bottom sheet as appropriate; backdrop-dim; swipe/tap-out close;
  safe-area padding; internal scroll when content is long.
- **Anti-patterns:** a drawer where an inline panel fits; a non-trapped drawer.

## 15. Sticky action bar — ➕ propose
- **Purpose:** keep the primary action reachable on a long record/form (bottom bar).
- **Regions:** one primary next action; up to two visible secondary actions; overflow/other-actions control; destructive actions separated; on mobile, thumb-reachable with safe-area padding.
- **States:** the primary action reflects the viewer's one allowed transition; **disabled actions explain why** (the WHY gap).
- **A11y:** real buttons; does not trap scroll; visible focus.
- **Mobile:** page content reserves enough bottom padding so the bar never covers the last row,
  form action, empty state or timeline entry.
- **Anti-patterns:** a bar full of equally-weighted actions; destructive actions competing with the primary; hiding the primary action off-screen.

## 16. Approval bar / Approval task — ➕ propose (a shared shell)
- **Purpose:** decide on someone else's submitted work (pricing, commission, OT/SM, leave, close-verify).
- **Regions:** what's decided · who submitted · the evidence to decide · approve / reject(+reason) / return(where modelled) · where-it-goes-next routing. **Two-hop** (OT/SM/commission) and **two-signature** (deal close) flows show **which hop/signature** this is.
- **States:** loading; empty ("ไม่มีรายการรออนุมัติ" + onward link); error (keep decision context); **already-decided (required)** — on 409/422 refetch, render new state, disable the stale action with a reason; `refetchOnWindowFocus` (no `@Version`).
- **A11y:** real buttons; reject/return reason field with associated errors; decision result announced.
- **Mobile:** one-tap approve; reason as a focused sheet (CEO/managers are mobile-heavy — this must be excellent on a phone).
- **Anti-patterns:** letting the UI claim "you can approve" for an action the service would 403 (classify with the real gates); a raw toast on a race.

## 17. Timeline — ➕ propose (ad-hoc per page today; a stage strip + routing strip exist)
- **Purpose:** a record's history and forward routing (activity, status transitions with actor+time, the "who's next" strip "ส่งแล้ว › หัวหน้าฝ่าย › CEO").
- **Variants:** event timeline (chronological) · routing strip (forward path) · stage strip (deal pipeline). Candidates to **unify conceptually**.
- **States:** loading (skeleton); empty ("ยังไม่มีกิจกรรม").
- **A11y:** ordered-list semantics; `<time>` elements; read-only.
- **Anti-patterns:** three divergent timeline implementations; colour-only stage state.

## 18. Empty state — ✅ exists (`EmptyState.jsx`, ×16)
- **Purpose:** communicate "nothing here" **and route onward** — never a dead end (F-04).
- **Regions:** plain-language line · onward CTA · optionally why.
- **Variants:** truly-empty vs **permission-limited** vs **not-applicable in current stage** vs **completed reference** vs filtered-to-empty (offer clear-filter). These must not share one vague message.
- **A11y:** text (not just an illustration); the CTA is a real control.
- **Anti-patterns:** "nothing here" with no next step; an over-cute illustration/empty state (product ban); using empty state for loading or query error; implying permission-limited content is absent.

## 19. Skeleton — ✅ exists (`Skeleton.jsx`)
- **Purpose:** loading placeholder for content (not a spinner mid-content).
- **Variants:** `Skeleton` / `SkeletonText` / `SkeletonCard`.
- **A11y:** ✅ `aria-hidden` (decorative); the container carries the loading label; skeleton rows in a table are **not** announced as data.
- **Motion:** shimmer with a reduced-motion flat fallback (already correct).
- **Anti-patterns:** a spinner where a skeleton fits; skeletons that misrepresent the final layout; replacing previously loaded data with skeletons during background refresh.

## 20. File upload — ✅ exists (`FileUploadField.jsx`)
- **Purpose:** attach a file; real `<input type=file>` kept `sr-only`, styled wrapper.
- **States:** empty/filled; hover/focus-within (`border-focus`); disabled (`opacity .55`, `pointer-events-none`); **upload progress/success/error ◐** (not exercised — specify progress + error).
- **A11y:** ✅ real focusable input; label associated; 44px min target.
- **Anti-patterns:** a fake button with no real input; no error state; a disabled state that reads as broken rather than "not available".

## 21. Description list — ➕ propose (`FieldList`/`InfoGrid` is the closest analog)
- **Purpose:** key→value display of a record's fields (detail pages).
- **Regions:** label (muted) · value; responsive columns (`max-[720px]:grid-cols-1`).
- **A11y:** `<dl>`/`<dt>`/`<dd>` semantics (or an accessible grid); values selectable.
- **Anti-patterns:** a form's look for read-only data; low-contrast labels (muted floor).
- **Consolidate:** standardise `FieldList`/`InfoGrid` into one description-list contract.

---

## Already-healthy primitives (reuse as-is; listed so Phase 4 doesn't rebuild them)
`CollapsibleSection` (strong a11y), `Icon` (curated lucide wrapper), `Breadcrumbs`, `Avatar`, `InfoTip`, `ErrorBoundary`, `RouteFallback`, `DesktopOnlyNotice`, `StatCard` (KPI tiles — separate tone palette), `FormField` (ARIA-wiring), `Layout`/`FormGrid`/`StatGrid` helpers.

## Build order (highest leverage first, Phase 4)
1. **`Button` loading + global focus ring** — unblocks the button consolidation and A-03.
2. **`DataTable` a11y contract** (A-01/A-02/F-02/F-07) — touches every list.
3. **`FormField` error wiring** (A-04) — touches every form.
4. **`Modal` labelling/inert** (A-05) + move `ChangePasswordModal` onto it.
5. **Worklist row + Approval task + Inline alert** — the new primitives the IA needs (F-04/F-05).
6. **Filter bar, Drawer, Sticky action bar, Timeline, Description list** — the remaining proposed primitives, as surfaces migrate.
