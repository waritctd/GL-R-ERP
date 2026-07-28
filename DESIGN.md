---
name: GL-R-ERP
description: A calm, reliable operations control desk for GL&R — Thai-first, mobile-aware, built to coordinate work and handoffs across every role.
colors:
  primary: "#4f46e5"
  primary-hover: "#6366f1"
  accent: "#14b8a6"
  accent-deep: "#0f766e"
  success: "#047857"
  warning: "#b45309"
  danger: "#dc2626"
  info: "#1d4ed8"
  override: "#7c3aed"
  workspace: "#eef1f6"
  sidebar: "#0b1220"
  surface: "#ffffff"
  surface-muted: "#f8fafc"
  surface-subtle: "#f1f5f9"
  ink: "#0f172a"
  ink-secondary: "#334155"
  ink-muted: "#5c6b80"
  ink-faint: "#94a3b8"
  border: "#e6eaf0"
  border-input: "#dfe5ee"
typography:
  display:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "34px"
    fontWeight: 800
    lineHeight: 1.15
    letterSpacing: "normal"
  headline:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "26px"
    fontWeight: 800
    lineHeight: 1.2
    letterSpacing: "normal"
  title:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "16px"
    fontWeight: 800
    lineHeight: 1.3
    letterSpacing: "normal"
  body:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
  label:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "13px"
    fontWeight: 700
    lineHeight: 1.4
    letterSpacing: "normal"
  overline:
    fontFamily: "Sarabun, system-ui, -apple-system, 'Segoe UI', sans-serif"
    fontSize: "11px"
    fontWeight: 800
    lineHeight: 1.3
    letterSpacing: "0.04em"
  mono:
    fontFamily: "ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace"
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.5
    letterSpacing: "normal"
rounded:
  sm: "3px"
  md: "8px"
  lg: "20px"
  pill: "999px"
spacing:
  1: "4px"
  2: "8px"
  3: "12px"
  4: "16px"
  5: "20px"
  6: "24px"
  7: "28px"
  8: "32px"
components:
  button-primary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "0 16px"
    height: "38px"
  button-primary-hover:
    backgroundColor: "{colors.primary-hover}"
    textColor: "{colors.surface}"
  button-secondary:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-muted}"
    rounded: "{rounded.md}"
    padding: "0 13px"
    height: "38px"
  button-success:
    backgroundColor: "{colors.success}"
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "0 13px"
    height: "38px"
  button-danger:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.danger}"
    rounded: "{rounded.md}"
    padding: "0 13px"
    height: "38px"
  input:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink}"
    rounded: "{rounded.md}"
    padding: "0 12px"
    height: "40px"
  panel:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.ink-secondary}"
    rounded: "{rounded.md}"
    padding: "20px"
  status-badge:
    rounded: "{rounded.pill}"
    padding: "3px 10px"
    height: "26px"
  nav-item-active:
    textColor: "{colors.surface}"
    rounded: "{rounded.md}"
    padding: "8px 10px"
---

# Design System: GL-R-ERP

> **Status:** Phase 3 design-language reference (analysis / proposal). This
> document is the visual law; the machine-readable token values live in the
> frontmatter above and in `frontend/src/index.css` (`@theme`). Deeper working
> specs — the semantic token table, per-status presentation, per-component
> contracts, and the legacy-CSS migration plan — live under
> [`docs/ui-repair/03-design-foundation/`](docs/ui-repair/03-design-foundation/).
> No production screen is restyled from this document before Phase 4.

## 1. Creative north star

**"The Operations Control Desk."**

GL-R's interface should feel like an operations control desk: calm, organized, reliable, and built for getting real work done without confusion or decoration. It is where the day's work is dispatched — a sales rep opens a deal and requests a price, a pricer and the CEO decide it, account confirms the money, HR runs payroll, a manager approves leave, warehouse gates a delivery. The interface's job is to make what matters legible, show each person what is waiting on *them*, let them act with confidence, and get out of the way. "Control desk," not "control room": control comes from clarity and predictability, never from ornament — and never from density-for-its-own-sake or alarm.

The system is **light and even, with a single dark rail to orient by**: a cool off-blue workspace (`#eef1f6`), white content surfaces, and a deep-navy sidebar (`#0b1220`) that anchors navigation. Density is deliberate — tables and forms run tight where the work demands it, and everything breathes where it doesn't. Type is Thai-first (Sarabun) and carries real weight; labels and headings sit at 700–800 so structure reads at a glance in both Thai and English. Color is rationed: indigo marks actions and focus, teal marks what is *live* (the current nav item, a progress fill), and semantic colors mean exactly one thing each.

This system explicitly rejects the flashy SaaS dashboard (gradient drench, decorative motion, hero-metric templates), the cluttered spreadsheet replacement (everything one weight, tiny gray text, no hierarchy), and the old bureaucratic HR/government form system (dated chrome, cramped grids). It is not a mission-control cockpit either — this is a calm daytime office tool, not a dark control room. **Distinctiveness comes from clarity, precision, and workflow structure — never from decoration.**

**Key characteristics:**
- Light workspace, one dark navigation rail — calm, oriented, never busy.
- Thai-first typography with heavy structural weights; both scripts read correctly in the same layout.
- Rationed color: indigo for action/focus, teal for "live", semantics for meaning only.
- Flat-by-default surfaces; elevation is a response to intent, not decoration.
- Sturdy, legible controls with real touch targets — mobile and the factory floor are first-class surfaces.

## 2. Design principles

The strategic principles live in [`PRODUCT.md`](PRODUCT.md); these are their visual expression — the ones a designer applies at the pixel.

1. **Work before decoration.** Every element carries information or aids a task. If it only looks nice, it does not ship. Distinctiveness is earned by how well the work reads, not by ornament.
2. **Next action before summary metrics.** A screen opens on the work to do — the worklist — not a wall of counts. Metrics are a compact strip that *supports* the worklist, never the hero (fixes F-04).
3. **Ownership must be visible.** Every record shows whose move it is. "Mine to act" is visually distinct from "waiting on someone else" (the work-state model, §15).
4. **Waiting is distinguishable from action.** Colour, weight, and placement separate a queue of my work from a list I'm only watching. Never one undifferentiated list (fixes F-05).
5. **Density supports scanning.** Tightness is a tool for legibility — aligned figures, consistent rows, one weight per role — not cramming. Density that hurts scanning is a defect.
6. **Progressive disclosure, never hidden critical information.** Depth folds into tabs and sections; the one thing a user must act on is never behind a click.
7. **Status is text, not colour alone.** Every state carries a Thai word; colour reinforces, it is never the sole signal (WCAG 1.4.1; §15).
8. **Mobile gets prioritisation, not compression.** Phone and floor flows are re-thought for the device, not shrunk (§17).
9. **Role permissions must be understandable.** A user sees what they can act on; a denied path explains itself (no silent bounce, F-03). The UI classifies "mine to act" with the *same* gates the Java service enforces — never a looser mock rule.
10. **Destructive and consequential actions are deliberate.** Reject, cancel, void, and money-committing actions require a reason or a confirm; they never fire on one stray tap.
11. **Thai is real content, not a translation afterthought.** Load-bearing verbs are Thai-first; layouts survive both scripts (§18).

## 3. Information-density philosophy

**One controlled default density, tuned per surface — not a global "compact mode."** The ERP is dense by necessity (payroll grids, deal lists, pricing queues), but density is applied where the *work* is dense, not everywhere by reflex. There is deliberately **no user-facing density preference** yet — it would be a second system to maintain with no demonstrated need. Introduce one only when a real role asks for it.

Density is expressed through three tuned levels, chosen by surface, not by toggle:

- **Dense** — data tables and reconciliation grids (payroll, commissions, deal list, PCR queue). Rows ~13px vertical padding, `13px` body, aligned/tabular figures, one weight per column role. This is where tightness earns its keep.
- **Default** — panels, forms, detail records, worklists. `20px` panel padding, `14px` body, comfortable field spacing. The everyday working density.
- **Roomy** — approval tasks, empty states, first-run, confirm dialogs, and the mobile/floor surfaces. Bigger targets, more air, one decision in focus.

Precise recommendations (elaborated in [`03-design-foundation/TOKENS.md`](docs/ui-repair/03-design-foundation/TOKENS.md) §Density):
- **Table rows** — Dense (13px pad); sticky header; never wrap, truncate + tooltip.
- **Form controls** — Default field spacing; 40px input height; 16px input font (iOS no-zoom).
- **Page headings** — one Title (16px/800) per panel, one Display (34px) per screen at most.
- **Filters** — a single filter bar, Default density, URL-persisted state; collapses on mobile.
- **Mobile cards** — Roomy: identity · status · 2–3 facts · one full-width action.
- **Approval panels** — Roomy: the decision and its evidence in focus, one-tap approve.
- **Timelines** — Default vertical rhythm; actor + time per event.
- **Document rows** — Dense list; name · type · date · download.

## 4. Surface hierarchy

A small, ordered set of surfaces — depth reads through **tone and border**, not shadow (§10). From back to front:

1. **Canvas** (`--color-bg` `#eef1f6`) — the cool off-blue page everything sits on.
2. **Panel / surface** (`--color-surface` `#fff`) — the working surfaces: cards, tables, forms, the content area. 1px `--color-border`, resting shadow only.
3. **Subtle / muted insets** (`--color-surface-subtle` `#f1f5f9`, `--color-surface-muted` `#f8fafc`) — table headers, inset zones, tracks, dividers. Grouping *within* a panel uses these tonal steps, **never a nested card**.
4. **Selected** (`--color-info-row-active` `#eff6ff` / info tints) — the current row or item.
5. **Navigation rail** (`--color-sidebar-bg` `#0b1220`) — the one dark surface, always present, orienting.
6. **Transient layers** (popovers, drawers, modals, toasts) — the only things that leave the desk, and the only place real shadow appears (§10, §16).

**The one-panel-deep rule.** Content is at most one panel deep. Never a card inside a card; reach for a tonal inset or a divider instead.

## 5. Colour philosophy

A cool, **rationed** palette: neutral workspace and white surfaces carry the work; indigo and teal do the pointing; semantic colours are reserved for state. Colour is spent, not sprinkled.

- **Indigo** (`#4f46e5`, hover `#6366f1`) — the single **action** colour. Primary buttons, active tabs, links, focus-ring hue. Nothing decorative wears it.
- **Teal** (`#14b8a6`) — the **"live/current"** accent, used in a handful of places app-wide: the active nav item's tint and progress fills. Its rarity is the meaning. Small count badges use **Teal Deep** (`#0f766e`) instead — plain Teal only clears ~2.5:1 as badge text, a straight AA failure (see fix/ui-contrast-tokens, 2026-07-28).
- **Semantic** — success `#047857`, warning `#b45309`, danger `#dc2626`, info `#1d4ed8`, each with a tinted bg + border. One colour, one meaning.
- **Override purple** (`#7c3aed`) — exactly one use: a CEO manual price override on a line item. Purple only ever appears here (indigo is spoken for).
- **Neutrals** — the workspace/surface/muted/subtle steps plus the ink ramp (§6 text) and the borders.

**Named rules:**
- **The Rationed Teal Rule.** Teal marks what is *live* and nothing else. More than a few teals on one screen and it has become decoration — remove it. Indigo = action; teal = state; not interchangeable.
- **The one-hue-per-meaning rule.** A colour means exactly one thing across the whole app. Do not reuse a semantic hue decoratively.
- **Colour is never the only signal.** Pair every coloured state with text or an icon (§15, §19).
- **Do not choose a colour because it looks attractive.** Every value is validated for contrast against its surface (§19); the token spec records the ratio.

## 6. Typography philosophy

**One humanist family, Sarabun, in many weights — hierarchy from weight and size, never from a second typeface.** Sarabun renders Thai and Latin with equal care in one layout; a second family would only add noise to a dense product. A system mono stack (`ui-monospace, …`) is used only for codes, IDs, and figures where digit alignment matters.

Ramp (values in the frontmatter / `index.css`):
- **Display** 34px/800 — one per screen at most.
- **Headline** 26px/800 — section headers, key stat figures.
- **Title** 16px/800 — panel/card headings; the workhorse.
- **Body** 14px/400 — reading and data text; prose capped 65–75ch, tables denser.
- **Label** 13px/700 — form labels, controls, buttons; bold anchors dense forms.
- **Overline** 11px/800/0.04em/UPPERCASE — table column headers, small eyebrows. **The only place uppercase tracking is allowed.**
- **Mono** 13px/400 — codes, reference numbers, currency figures needing alignment.

**Available weights are 300–500–700** (the CDN loads Sarabun 300/400/500/600/700). The ramp uses **400 / 700**, and calls for **800**; 800 is not in the loaded set, so it currently falls back to the nearest available (700) — a real gap flagged in §18 and [`TOKENS.md`](docs/ui-repair/03-design-foundation/TOKENS.md). Either load 800 or retune "800" heads to 700; do not assume 800 renders today.

**Named rules:**
- **The Weight-Not-Family Rule.** More contrast → go heavier or larger within Sarabun; never introduce a display or serif font into UI labels, buttons, or data.
- **The Both-Scripts Rule.** Every choice reads correctly in Thai *and* English at the same size — watch line-height (Thai marks need the 1.4–1.5 floors), truncation, and label width. No layout depends on Latin-only metrics.

## 7. Spacing philosophy

**A single 4px-based scale** (`--space-1`…`--space-8` = 4/8/12/16/20/24/28/32) drives every gap, pad, and gutter. Spacing creates rhythm and grouping; vary it deliberately (a section break is more air than a row gap), never randomly.

- **Page gutter** scales by breakpoint (§G / mobile 16px → desktop 24–32px).
- **Panel padding** 20px; **stat card** 16px.
- **Compact control spacing** (dense tables, toolbars) draws from `--space-1/2/3`.
- **Comfortable control spacing** (forms, approvals) draws from `--space-4/5/6`.

**Gap to close:** many legacy paddings are raw px (`gap: 7px`, `padding: 0 16px`, `3px 10px`) that bypass the scale. The token spec proposes reconciling control padding to the scale during Phase 4 migration — without changing rendered metrics where they already match.

## 8. Shape and radius philosophy

**Gently rounded, never soft.** A restrained radius scale (`--radius-sm` 3px, `--radius-md` 8px, `--radius-lg` 20px, `--radius-pill` 999px):

- **`sm` (3px)** — small inline chips, tight insets.
- **`md` (8px)** — the default: buttons, inputs, panels, cards, modals. Almost everything.
- **`lg` (20px)** — reserved; used sparingly, not a default. Over-rounding is an AI-UI tell (anti-pattern §20).
- **`pill` (999px)** — **only** true pills: status badges and count badges. Never a pill button as decoration.

## 9. Border and divider philosophy

**Borders and tonal steps do the separating that shadow does elsewhere.** Hairline borders define surfaces; dividers separate rows and sections.

- **Default border** `--color-border` `#e6eaf0` — card/panel edges.
- **Input border** `--color-border-input` `#dfe5ee`, 1.5px — form controls, sturdy on purpose.
- **Subtle divider** `--color-border-subtle` `#e2e8f0` — row and light section dividers.
- **Strong border** `--color-border-strong` `#d9e0ea` — where a heavier separation is needed.
- **Focus border** — input focus shifts to `--color-primary-hover` + the focus-ring halo (§10, §19).
- **Error border** — `--color-danger` on `.is-invalid`.

**Banned:** a coloured `border-left`/`border-right` > 1px as a decorative accent stripe on cards, alerts, or list items (a named absolute ban, §20). Use a full border, a background tint, or a leading icon instead.

## 10. Elevation philosophy

**Flat by default; lift only on intent.** Surfaces sit flat on the workspace, separated by borders and tone. Shadow means "this element left the desk." Four steps only:

- **None** — the desk itself and content on it.
- **Resting** (`--shadow-sm`, `0 1px 2px rgba(15,23,42,0.03)`) — a near-invisible seat under panels/cards/tables. Grounds without lifting.
- **Popover / floating** (`--shadow-md`, `0 18px 44px`) — dropdowns, menus, popovers.
- **Dialog / overlay** (`--shadow-lg` / `-lg-heavy`) — modals and drawers, the only place a heavy shadow is correct.
- **Focus ring** (`--shadow-focus-ring`, `0 0 0 3px rgba(99,102,241,0.13)`) — always present on `:focus-visible`.

**The Flat-Desk Rule.** A resting card with a big soft shadow is the SaaS-dashboard tell — forbidden. Excessive shadows and glassmorphism are named anti-references.

## 11. Motion philosophy

**Minimal and functional. Motion conveys state, never decorates.** Durations 100–250ms, ease-out. Today the app uses exactly one keyframe (`skeleton-shimmer`) and a few 100–180ms transitions (nav chevron, collapsibles, the drawer slide) — that restraint is correct and is the target, not a deficiency.

- **Fast** (~120–160ms) — hover/press feedback, chevrons, small state flips.
- **Standard** (~180–200ms) — drawers, collapsibles, entering/leaving transient layers.
- **Slow** (reserved, ≤250ms) — only where a larger surface genuinely needs it.
- **No orchestrated page-load sequences**, no scroll choreography, no staggered card reveals. Product loads into a task.
- **Reduced motion is not optional.** Every animation has a `prefers-reduced-motion: reduce` alternative (crossfade or instant). The skeleton falls back to a flat muted fill; drawer/collapsible transitions drop to none.

## 12. Iconography philosophy

**A single curated line-icon set (lucide-react), one stroke weight, decorative by default.** Icons are wrapped in one `Icon` component with a fixed name→glyph map (`Icon.jsx`), default `size=18`, `strokeWidth=2`, and `aria-hidden="true" focusable="false"` — so an icon never becomes an unlabelled control.

- **Line, not filled**; consistent 2px stroke; 16–20px in-flow, 18px default.
- **Curated, not open-ended.** Add a glyph to the map deliberately; do not import ad-hoc icons per feature. Unknown names fall back to a neutral circle.
- **Icons support text, they don't replace it.** No icon-only status, no icon tile above every heading (an anti-pattern, §20). An icon-only *button* must carry an `aria-label` (§19, A-06).
- **Meaning is stable.** One glyph = one concept across the app (e.g. `check`=done, `triangleAlert`=warning), mirroring the one-hue-per-meaning rule.

## 13. Data-table philosophy

Tables are the heart of the product; one shared `DataTable` primitive, never per-page table styling.

- **Structure** — sticky `--color-surface-muted` header in Overline type (11px/800/uppercase, `--color-text-muted`); white rows, ~13px vertical padding, `--color-text-secondary`; `--color-surface-subtle` bottom divider; hover to a faint surface. Columns align by role; **cells truncate with ellipsis + tooltip, never wrap**.
- **Figures** — right-aligned, tabular/mono where alignment matters (§6, §18 currency).
- **Row action** — a row is **not** a `<button>` wrapping more buttons (the current F-02/A-01 defect). One clear "open" affordance; per-row actions are siblings. A11y contract rebuilt to native `<table>`/correct `grid` semantics with `rowgroup`/`gridcell` (A-02).
- **Mobile** — reflows to record cards via the `mobileCard` prop (already the mechanism); any caller without a `mobileCard` is a defect. Never a squeezed desktop grid.
- **States** — loading = skeleton rows (not announced as data); empty = `EmptyState` with an onward link (never a dead end); error = inline retry preserving loaded rows; overflow = contained horizontal scroll, never body-level.

## 14. Form philosophy

Sturdy, legible, correct. One shared `FormField` vocabulary.

- **Inputs** — full-width, 40px min-height, **16px font-size** (prevents iOS focus-zoom — deliberate, keep it), 1.5px `--color-border-input`, 8px radius, white surface.
- **Label** — stacked above, 13px/700, `--color-text-secondary`.
- **Focus** — border → `--color-primary-hover` + focus-ring halo; the outline is replaced by the ring, never simply removed.
- **Error** — `aria-invalid` + `aria-describedby` linking field→message (fixes A-04/F-09), danger border, and a non-colour cue; message in danger text below the field. Surface validation *before* submit where the rule is known (e.g. SICK leave needs a cert).
- **Disabled** — reduced opacity + `not-allowed`; a disabled action explains *why* (the WHY gap) rather than sitting silently dead.
- **Consequential submit** — money/irreversible actions confirm (§16) or require a reason.

## 15. Status and work-state philosophy

Two distinct layers, both text-first:

**(a) Backend lifecycle status** — the persisted value (ticket status, `PricingRequestStatus`, `LeaveStatus`, `CommissionStatus`, …). Shown as a `StatusBadge`: a pill with tinted bg + matching dark text + Thai label, always text (never colour-only). Backend statuses are **many**; they do **not** each get a unique colour — they map onto a small semantic set (neutral / info / success / warning / danger). Full mapping in [`STATUS_PRESENTATION.md`](docs/ui-repair/03-design-foundation/STATUS_PRESENTATION.md).

**(b) UX work-state** — the *computed* "whose move is it?" classification, per viewer, from data the app already has (never a new backend status). The nine states (from [`02-information-architecture/WORK_STATE_MODEL.md`](docs/ui-repair/02-information-architecture/WORK_STATE_MODEL.md)): **Needs-my-action, Waiting, Blocked, Overdue, Draft, Completed, Cancelled, Returned, Informational.** Rules:

- **Mine-to-act** (Needs-my-action / Overdue / Returned) reads with the highest weight and sits at the top of every worklist. **Waiting / Informational** are muted — visible, not urgent. **Completed / Cancelled** are archived-tone (greyed, struck).
- **Overdue is a modifier**, an escalation of the underlying state, not a slot of its own.
- **The two-way split** (mine vs waiting) is conveyed by heading + text + placement, not colour alone.
- **Already-decided is a required state** (no backend `@Version`): on a 409/422, render the new work-state and disable the stale action *with a reason*; live-approval surfaces refetch on window focus.

## 16. Dialog, drawer, and full-screen-task philosophy

**A modal is a last resort, not a first thought.** Exhaust inline and progressive alternatives first.

- **Dialog / modal** — for a *short, focused* interruption that must block (a confirm, a single-field reason). Shared `Modal` (focus-trap, Escape, restore, `role="dialog"`); labelled by the visible heading (`aria-labelledby`, fixing A-05); background inert. Overlay shadow; centred; capped at viewport height.
- **Drawer** — a side/bottom panel for context or a secondary list without leaving the page; the mobile nav is a focus-trapped drawer.
- **Full-screen task** — a multi-step aggregate build (create-deal, create-PCR) is a **full-page route** with a progress checklist and save-as-draft, **not** a wizard trapped in a modal (fixes F-06). On mobile it is a full-screen sheet, deep-linkable and resumable.
- **Confirm dialog** — reserved for consequential/destructive actions; states the consequence plainly (§14, principle 10).

## 17. Mobile adaptation philosophy

**Prioritisation, not compression.** A mobile screen shows the *most important* thing for that role and device, re-thought — not the desktop layout shrunk.

- **Navigation** collapses to a focus-trapped drawer ≤ the mobile breakpoint.
- **Tables → record cards** (identity · status · 2–3 facts · one full-width action).
- **Landing stat rows** reflow to a wrapping 2-up grid or a summary line — never a clipping horizontal scroll (fixes F-12).
- **Actions** are thumb-reachable, ≥44px; the cva `<Button>` carries the 44px floor (legacy `.*-button` may not — a migration target).
- **Multi-step creation** is a full-screen sheet, not a modal (§16).
- **The tablet band (721–1040px) is a first-class surface**, currently the weakest (F-01): the shell must keep a labelled rail longer *or* present a true icon-rail with tooltips and fully-suppressed group-header text — never fragmented labels. See §responsive in [`TOKENS.md`](docs/ui-repair/03-design-foundation/TOKENS.md).
- **Payroll is desktop-only by design** (a month-end grid) and says so via `DesktopOnlyNotice` — an accepted, labelled exception, not a bug.

## 18. Thai-content rules

Thai is primary content. Every rule here is a hard requirement, not a nicety.

- **One family for both scripts** (Sarabun); no Latin-only fallback that would split the look.
- **Line-height floors** — body/label at 1.4–1.5 so Thai upper/lower marks (sara, tone) are not clipped or crowded. Never tighten Thai below 1.4.
- **Load-bearing verbs are Thai** — the buttons that run payroll, approve a price, confirm money are Thai-first (fixes F-10 English-verb violations); English may be a *helper* subtitle only.
- **Long Thai names/labels** truncate with ellipsis + tooltip; they never clip mid-word or force a broken wrap (fixes F-12). Thai has no spaces between words, so truncation and line-breaking must be tested with real Thai strings.
- **Numerals** — Arabic numerals for figures; tabular/mono alignment for currency and quantities.
- **Currency** — `฿` with thousands separators and consistent decimal places; amounts right-aligned and digit-aligned in tables. Money is never ambiguous (principle: trust near money).
- **Available weights** — Sarabun 300–700 load today; the ramp's "800" heads currently fall back to 700 (§6). Resolve before treating 800 as real.
- **Mixed Thai/English** in one label (e.g. "พัก dormant") is banned — pick one script per label with a real Thai term.

## 19. Accessibility rules

Target **WCAG 2.1 AA** (2.2 AA where already met). The shared primitives are the leverage points; fixing them fixes the app.

- **Contrast** — body/data text ≥4.5:1, large text ≥3:1. `--color-text-muted` `#5c6b80` (~4.7-5.4:1 across the app's light surfaces — white, surface-muted, surface-subtle, workspace) is the **floor** for body/data text on light surfaces; it fails on the dark sidebar (~3.5:1), where `--color-text-faint` `#94a3b8` is used instead (~7.3:1 on `--color-sidebar-bg`). `--color-text-faint` on a *light* surface (~2.6:1) is reserved for **icons/placeholders only, never text** — its only two legitimate roles are decorative icons and navy-sidebar text (fixed 2026-07-28, see `fix/ui-contrast-tokens`: it had drifted onto real body text in `TicketCreateModal`/`TicketDetailPage`, and the sidebar's own brand subtitle had drifted the other way, onto `--color-text-muted`).
- **Focus visible** — a single global `:focus-visible` ring on *every* interactive element (fixes A-03/F-08); the outline is replaced by the ring, never stripped without one.
- **Status not colour-only** — text (+ optional icon) on every badge and state (§15).
- **Form errors programmatic** — `aria-invalid` + `aria-describedby` in the shared `FormField` (fixes A-04).
- **Tables** — valid semantics; no `<button>`-in-`<button>`; skeleton rows not announced as data (fixes A-01/A-02).
- **Dialogs** — labelled by the visible heading; background inert (fixes A-05). Icon-only buttons carry an accessible name (A-06).
- **Keyboard** — full operability; focus trapped in modals/drawers, restored on close.
- **Reduced motion** — honoured everywhere; its removal never hides information.
- **Touch** — ≥44px targets on mobile/floor surfaces.
- **Permission denied** — a calm "ไม่มีสิทธิ์เข้าถึง" notice, not a silent bounce to `/` (fixes F-03).

## 20. Anti-patterns

Match-and-refuse. If a screen is about to do any of these, it is wrong here:

- **Metric-card hero** — 4–6 equal metric cards (with icon tiles) leading a landing while the worklist sits below the fold (F-04). Demote metrics to a compact strip; promote the worklist.
- **Colour-only status**, or a unique colour per backend status (§15).
- **Random gradients, gradient text, glassmorphism, ambient shadows on resting cards** (the SaaS-dashboard tells).
- **Side-stripe accent borders** (coloured `border-left/right` > 1px) on cards/alerts/rows (§9).
- **Nested cards / card-in-card**; over-rounded corners as a default (§8).
- **Decorative card galleries** where a table, list, or worklist is the honest affordance.
- **Uppercase tracked eyebrows or 01/02/03 numbered markers above every section** — brand-surface scaffolding; wrong in a product. Overline is for *table headers*, not decoration.
- **Tiny gray low-contrast text**; body/data text below the muted floor (§19).
- **Spreadsheet-cramming** — everything one weight, no hierarchy, cells crushed.
- **Squeezed-desktop mobile**; a `DataTable` without a `mobileCard`.
- **Modal-first thinking**; a multi-step flow trapped in a modal (§16).
- **Decorative motion**, page-load choreography, staggered reveals (§11).
- **English on a load-bearing Thai control**; mixed-script labels (§18).
- **Icon-only controls without an accessible name** (§12/§19).

## 21. Screenshot review checklist

Every Phase-4 change ships **desktop (1366) and mobile (390)** before/after screenshots (tablet 768 where the shell is touched). Review each shot against:

1. **Worklist first?** Does the primary "what needs me" content lead, above any metric strip? (F-04)
2. **Whose move is it?** Is mine-to-act visually separated from waiting/for-reference? (F-05, §15)
3. **Status legible without colour?** Every badge/state carries a Thai word; readable in greyscale. (§15/§19)
4. **Thai + English both correct?** No clipped marks, no mid-word truncation, no mixed-script label, no English on a load-bearing verb; line-heights fit Thai. (§18)
5. **Contrast holds?** Body/data ≥4.5:1; no faint body text; check tinted surfaces. (§19)
6. **Focus visible?** Tab through — every control shows the ring. (§19)
7. **Flat desk?** No resting-card shadow bloom, no gradient, no glass, no nested card, no side-stripe. (§9/§10/§20)
8. **Colour rationed?** Indigo=action, teal=live only, semantics=state; count the teals. (§5)
9. **Density right for the surface?** Dense where data-heavy, roomy where deciding; nothing crushed or padded aimlessly. (§3)
10. **Mobile re-thought, not shrunk?** Cards not squeezed grids; stat rows wrap not clip; targets ≥44px; tablet rail not fragmented. (§17)
11. **Right affordance?** A table/list where a table belongs; a full-page task where a modal was; a modal only for a true short interruption. (§13/§16)
12. **Tokens, not literals?** No stray hex/px that a token covers; motion within 100–250ms with a reduced-motion path. (§7/§11, TOKENS.md)

---

## Appendix — palette reference

A cool, rationed palette: neutral workspace and white surfaces carry the work, indigo and teal do the pointing, and semantic colors are reserved for state. (Machine-readable values in the frontmatter and `index.css`; the semantic-token table with contrast ratios and prohibited uses is in [`TOKENS.md`](docs/ui-repair/03-design-foundation/TOKENS.md).)

A cool, rationed palette: neutral workspace and white surfaces carry the work, indigo and teal do the pointing, and semantic colors are reserved for state.

### Primary
- **Indigo** (`#4f46e5`): The single action color. Primary buttons, active tabs, links, and the focus ring's hue. Hovers to a lighter **Indigo Bright** (`#6366f1`). This is the color of "you can act here" — nothing decorative wears it.

### Secondary
- **Teal** (`#14b8a6`): The "live" accent, used deliberately sparingly (a handful of places across the whole app): the active sidebar item's tint and progress-bar fills. Its rarity is what makes it read as *current*. **Teal Deep** (`#0f766e`) backs the occasional highlight panel and — as of 2026-07-28 (`fix/ui-contrast-tokens`) — small count badges: plain Teal only clears ~2.5:1 as badge text (the sidebar unread badge, `styles.css` `.nav-item b`) and the numeral in a Teal "pulse" stat (`ImportOverview.jsx`'s `text-accent-dark`), both AA failures; Teal Deep clears 5.47:1+ in the same spots.

### Tertiary — Semantic
- **Success Green** (`#047857`, bg `#dcfce7`, border `#a7f3d0`): approvals, paid status, positive confirmations. Was `#059669` until 2026-07-28 (`fix/ui-contrast-tokens`) — that value measured 3.77:1 both as white-on-success (the Approve button) and as success-on-white (commission-rate text), failing the 4.5:1 floor; `#047857` clears 5.48:1 in both directions. `--color-success-bg`/`--color-success-dark` (below) are unaffected — they're a separate pairing, never combined with plain `--color-success` text.
- **Warning Amber** (`#b45309`, bg `#fef3c7`): pending, needs-attention, caution. The soft warning panel variant (`--color-warning-bg-soft`, `#fffbeb`) pairs with `--color-warning-border` (`#fbbf24`) and `--color-warning-dark` (`#92400e`) text for inline warning callouts (e.g. the ticket "already approved" banner) that need a lighter touch than the amber badge.
- **Danger Red** (`#dc2626`, bg `#fee2e2`, border `#fecaca`): rejections, destructive actions, errors. Danger buttons are *outlined*, not filled — the weight of the action shouldn't shout until pressed.
- **Info Blue** (`#1d4ed8`, bg `#dbeafe`): informational status, neutral notices, selected rows. `--color-info-border` (`#bfdbfe`) and `--color-info-border-strong` (`#93c5fd`) are the two info-tinted border weights used on info panels and inline inputs sitting on an info background.
- **Override Purple** (`--color-override`, `#7c3aed`, border `--color-override-border` `#a78bfa`): reserved for one specific case — a CEO manual price override on a ticket line item. Deliberately purple because indigo is already spoken for as the single action color; this is the only place purple appears.

### Neutral
- **Workspace** (`#eef1f6`): the cool off-blue page background everything sits on.
- **Sidebar Navy** (`#0b1220`): the one dark surface — the navigation rail. Text on it is **Slate** (`#cbd5e1`, also aliased as `--color-border-muted` where it appears as a form-control border rather than sidebar text).
- **Surface White** (`#ffffff`): panels, cards, tables, inputs. **Surface Muted** (`#f8fafc`) for table headers and inset zones; **Surface Subtle** (`#f1f5f9`) for tracks and dividers.
- **Ink** (`#0f172a`): primary text. **Ink Secondary** (`#334155`): body/table text. **Ink Muted** (`#5c6b80`, was `#64748b` until 2026-07-28 — see the Muted Floor Rule below): captions and secondary labels — the floor for text on **light** surfaces only; on the dark sidebar rail use Ink Faint instead (below). **Ink Faint** (`#94a3b8`): icons and placeholders only, never body copy — except as sidebar-rail text, where it clears 7.3:1 against Sidebar Navy.
- **Border** (`#e6eaf0`) for card/panel edges; **Border Input** (`#dfe5ee`) for form controls; **Border Subtle** (`#e2e8f0`) for lighter card/row dividers.

### Aliases
- **Link** (`--color-link`, `#2563eb`): inline text links (e.g. "view file"). Shares a value with `--color-indigo-ring` today but is named separately because a link's semantic role (navigable text) is distinct from a focus ring's.

### Named Rules
**The Rationed Teal Rule.** Teal marks what is *live* and nothing else. If teal appears more than a few times on one screen, it has stopped meaning "current" and become decoration — remove it. Indigo is for action; teal is for state; they are not interchangeable.

**The Muted Floor Rule.** `#5c6b80` (Ink Muted, updated 2026-07-28 from `#64748b`, which measured as low as 3.93:1 on the app's actual light surfaces — workspace, surface-subtle, `#efefef` — despite clearing ~4.6:1 on pure white) is the lightest a text color may go on a **light** surface — it clears 4.5:1+ against every light surface it's used on. On the dark sidebar rail, Ink Muted itself fails (~3.5:1); use `#94a3b8` (Ink Faint) there instead, which clears 7.3:1 on Sidebar Navy. On light surfaces, Ink Faint (~2.6:1) is for icons and placeholders only. Never set body or data text in Faint on a light surface; "light gray for elegance" is a banned anti-reference here.

## 3. Typography

**Display / Body / Label Font:** Sarabun (with `system-ui, -apple-system, 'Segoe UI', sans-serif` fallback)
**Mono Font:** `ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace` — for codes, IDs, and figures where digit alignment matters.

**Character:** One humanist family in many weights, chosen because Sarabun renders Thai and Latin with equal care in a single layout. The system leans on *weight*, not typeface pairing, for hierarchy — heavy 800/900 for structure, 400 for reading. There is no display/serif pairing; a product this dense would only get noisier from a second family.

### Hierarchy
- **Display** (800, 34px, 1.15): the largest page/section title. Rare — one per screen at most.
- **Headline** (800, 26px, 1.2): major section headers, key figures on stat cards.
- **Title** (800, 16px, 1.3): panel titles (`.panel h2`), card headings — the workhorse heading size.
- **Body** (400, 14px, 1.5): default reading and data text. Cap prose at 65–75ch; tables may run denser.
- **Label** (700, 13px, 1.4): form labels, controls, buttons. Bold on purpose — labels anchor the dense forms.
- **Overline** (800, 11px, 0.04em, UPPERCASE): table column headers and small section eyebrows. This is the *only* place uppercase tracking is allowed.
- **Mono** (400, 13px): employee codes, reference numbers, currency figures needing alignment.

### Named Rules
**The Weight-Not-Family Rule.** Hierarchy is built from weight and size within Sarabun, never from a second typeface. If a screen needs more contrast, go heavier or larger — do not introduce a display font into UI labels, buttons, or data.

**The Both-Scripts Rule.** Every type choice must read correctly in Thai *and* English at the same size. Watch line-height (Thai ascenders/descenders need the 1.4–1.5 floors), truncation, and label width. No layout may depend on Latin-only metrics.

## 4. Elevation

**Flat-by-default, lift on intent.** Surfaces sit flat on the workspace, separated by hairline borders and tonal steps (workspace → surface → muted), not shadow. Depth is reserved for things that genuinely float *above* the page — modals, menus, and the occasional hover lift. A card at rest casts almost nothing; if it looks like it's floating, the shadow is wrong.

### Shadow Vocabulary
- **Resting** (`box-shadow: 0 1px 2px rgba(15,23,42,0.03)`): the near-invisible seat under panels, stat cards, and tables. Barely there by design — it grounds the surface without lifting it.
- **Floating** (`box-shadow: 0 18px 44px rgba(2,6,23,0.28)`): dropdowns, popovers, anything that overlays content.
- **Overlay** (`box-shadow: 0 24px 70px rgba(2,6,23,0.24–0.28)`): modals and dialogs — the only place a heavy shadow is correct.
- **Focus Ring** (`box-shadow: 0 0 0 3px rgba(99,102,241,0.13)`): the indigo focus halo on inputs and interactive elements. Always present on `:focus` / `:focus-visible`.

### Named Rules
**The Flat-Desk Rule.** Content on the desk is flat. Shadow means "this element left the desk" (modal, menu, drag). A resting card with a big soft shadow is the SaaS-dashboard tell — forbidden. Excessive shadows and glassmorphism are named anti-references; do not reach for them.

## 5. Components

Controls should feel **sturdy and legible**: solid borders, confident bold labels, real touch targets. Nothing delicate near payroll; every control looks dependable and obvious.

### Buttons
- **Shape:** gently rounded (8px, `--radius-md`), 1.5px border on outlined variants, minimum 38–44px tall for touch.
- **Primary:** filled Indigo (`#4f46e5`) on white text, `0 16px` padding. Hover → Indigo Bright (`#6366f1`). The one high-emphasis action per context.
- **Secondary:** white surface, Ink-Muted text, Border-Input outline. The default for non-primary actions.
- **Success:** filled Success Green — for approve/confirm actions specifically.
- **Danger:** *outlined*, not filled — white surface, Danger-Red text and border. Destructive weight stays quiet until pressed.
- **Text / Back:** borderless, Indigo text, no padding — inline navigation and tertiary actions.
- **Icon button:** 44×44 square (36px for `.icon-only`), white surface, muted icon; `.dark` variant is transparent on the sidebar.
- **Disabled:** `opacity: 0.55`, `cursor: not-allowed`. Weight ≥700 across all buttons.

### Cards / Panels
- **Corner Style:** 8px (`--radius-md`).
- **Background:** Surface White, 1px Border edge, Resting shadow only.
- **Internal Padding:** 20px for panels, 16px for stat cards.
- **Header:** `.panel-header` is a space-between row — Title left, actions right, 16px below.
- **Never nest a card inside a card.** Use tonal surfaces (muted/subtle) or dividers for internal grouping.

### Inputs / Fields
- **Style:** full-width, 40px min-height, **16px font-size** (deliberate — prevents iOS auto-zoom on focus), 1.5px Border-Input stroke, 8px radius, white surface.
- **Focus:** border shifts to Indigo Bright + the indigo Focus Ring halo. Outline is removed only because the ring replaces it — focus is always visible.
- **Error:** `.is-invalid` swaps to Danger border; `.form-error` message in Danger below the field.
- **Label:** grid-stacked above the field, 13px, weight 700, Ink-Secondary.
- **Icon/search fields:** icon absolutely positioned left, input padded to 40px.

### Navigation (Sidebar)
- **Style:** 260px fixed rail on Sidebar Navy; collapses on mobile. Items are 3-column grids (icon · label · count) at 48px min-height.
- **Default:** Ink-Faint label on transparent. **Active:** teal-tinted background (`rgba(20,184,166,0.13)`) with white label — the one place teal marks "you are here". Count badges are Teal Deep pills — plain Teal fails AA as badge text (see the Teal Deep note under §5/§19).

### Tables
- **Header:** `.table-head` — Surface Muted background, Overline type (11px, 800, uppercase), Ink-Muted, bottom border; `.is-sticky` pins it.
- **Rows:** white, 13px vertical padding, Ink-Secondary text, `#f1f5f9` bottom divider, hover to Surface Hover. CSS-grid columns per table type keep alignment; cells truncate with ellipsis rather than wrap.
- **Mobile:** dense tables reflow to stacked cards — never a horizontally-squeezed desktop grid.

### Status Badge
- **Style:** pill (999px), 26px tall, 3–10px padding, weight 800, 12px. Semantic bg+text pairs (success/warning/danger/info) — one color, one meaning. When interactive (button/link), grows to a 44px touch target.

### Modal
- **Backdrop:** `rgba(15,23,42,0.52)`, `z-index: 50`, centered. **Panel:** `min(720px, 100%)`, capped at viewport height, 8px radius, Overlay shadow, header/body/footer flex layout. Modals are a last resort — exhaust inline and progressive alternatives first.

## 6. Do's and Don'ts

### Do:
- **Do** ration color: Indigo (`#4f46e5`) for actions and focus, Teal (`#14b8a6`) for "live"/current only, semantics for state. Each color means one thing.
- **Do** keep surfaces flat at rest with a 1px border and the Resting shadow; reserve real shadow for modals, menus, and hover lift ("The Flat-Desk Rule").
- **Do** build hierarchy from Sarabun weight (400 body → 700 label → 800 heading), not from a second typeface.
- **Do** keep body/data text at Ink-Muted (`#5c6b80`) or darker on light surfaces — it clears 4.5:1+. On the dark sidebar rail use Ink Faint (`#94a3b8`, 7.3:1 there) instead — Ink Muted itself fails on that surface. Verify contrast on tinted surfaces.
- **Do** keep inputs at 16px font-size and controls at ≥38–44px touch height; design mobile flows as reflowed cards, not shrunk desktop grids.
- **Do** test every layout in **both Thai and English** — line-height, truncation, and label widths must survive both scripts.
- **Do** honor `prefers-reduced-motion`; motion (150–250ms) conveys state only.

### Don't:
- **Don't** use random gradients, gradient text, or gradient-drenched surfaces — this is not a startup dashboard.
- **Don't** add excessive shadows, glassmorphism, or soft ambient shadows on resting cards. A floating resting card is the SaaS-dashboard tell.
- **Don't** nest cards inside cards, or over-round corners — restraint is the default (`--radius-md` is 8px, not 20px+ everywhere).
- **Don't** set body or data text in Ink-Faint (`#94a3b8`) or any tiny gray low-contrast type — a named anti-reference here.
- **Don't** cram tables to spreadsheet density with everything one weight and no hierarchy; and don't ship a mobile page that's a squeezed desktop screen.
- **Don't** scatter playful icons, decorative widgets, or over-cute empty states; and don't add unnecessary animation. Delight lives in a fast, obvious flow, not ornament.
- **Don't** spend Teal as decoration or use it interchangeably with Indigo ("The Rationed Teal Rule").
- **Don't** introduce a display or serif font into UI labels, buttons, or data ("The Weight-Not-Family Rule").
- **Don't** reach for a modal as the first thought — exhaust inline and progressive alternatives first.
