# Step 3.1 — Product & Design Context Reconciliation

**Command:** `/impeccable init` (register: **product**, platform: **web** — both
already captured; this was a refresh, not a first-time init).
**Scope of change:** strategic + prose only. Root [`PRODUCT.md`](../../../PRODUCT.md)
refreshed; root [`DESIGN.md`](../../../DESIGN.md) prose updated with **every token
value left byte-identical** (`git diff DESIGN.md` = 3 insertions / 3 deletions, all
prose). No production code, no token value, no component spec changed.

The Impeccable init flow's rule is *"do not blindly keep the generated PRODUCT.md or
DESIGN.md — review them against Phase 1 evidence and Phase 2 architecture."* This
doc is that review.

---

## 1. What the refresh changed, and why

Both root files already existed and were strong, but they predated two facts the
repo has since established. The refresh reconciles them.

| # | Was (stale) | Now | Source of truth for the change |
|---|-------------|-----|--------------------------------|
| R-1 | "HR portal… v0.1.0 HR-core foundation. The frozen sales/CRM stack is out of scope and flag-hidden." | HR **+ Sales/CRM** operations portal; sales/CRM is a **live** part of the current release line. | `CLAUDE.md` → *"Sales/CRM stack — UNFROZEN (2026-07-16)"*; v0.1.0 is historical. |
| R-2 | Users = **3** contexts (HR / managers / employees). | Users = **8** role contexts (Sales, Import, CEO, Account, HR, managers, Warehouse/QC, employees). | Phase 3 brief's confirmed user list; Phase 2 `ROLE_JOB_MAP` / `DivisionAccessPolicy` (9 roles). |
| R-3 | North star = "The Steady Operations Desk." | North star = **"The Operations Control Desk."** | Phase 3 brief. Reconciled per user decision (see §3). |
| R-4 | Purpose framed as HR self-service + admin correctness. | Purpose framed as **coordinating operational work and handoffs across roles** ("whose move is it?"). | Phase 2 `ROLE_HANDOFF_MAP` (17 handoffs), `WORK_STATE_MODEL`, and Phase 1 F-05. |
| R-5 | No `## Positioning` section (init template requires one). | Added: the single strategic claim (see the work you must act on, move it one hand). | Init template §Step 4; Phase 2 IA. |
| R-6 | Anti-references generic to "SaaS / admin template." | Added the brief's explicit bans: **marketing website, banking/crypto app, purple-gradient admin template, card gallery, glassmorphism.** | Phase 3 brief "Not:" list + the shared Impeccable absolute bans. |
| R-7 | Design principles were 5, HR-centric. | 6 principles; added **"Every record answers 'whose move is it?'"** and broadened "mobile" to include the **factory floor** (warehouse/QC). | Phase 2 IA + Phase 1 F-05/F-12. |

`## Register` (product) and `## Platform` (web) were already correct and unchanged.

---

## 2. Review against Phase 1 evidence

The refreshed context was checked against the Phase 1 audit
([`../01-audit/`](../01-audit/)). The design language must be able to *absorb the
fixes* the audit calls for without inventing a new system. It can — the audit's own
verdict is that the shared design system is "largely healthy and well-adopted," and
every repair direction points **back into the existing tokens**, not away from them.

- **F-04 / F-05 / F-12 (landings, worklists, mobile stat cards)** → matches new
  principle **"Every record answers 'whose move is it?'"** and the north star's
  "show each person what is waiting on *them*." The audit's repair (demote metrics
  to a compact stat strip, promote the worklist, split "mine to act" vs "waiting")
  is a **primitive** to propose in a later 3.x step, not a token change. Recorded
  here so the foundation phase owns it. `COMPONENT_DUPLICATION §5` already flags a
  shared `WorklistRow` as a Phase-3 proposal.
- **F-13 / `COMPONENT_DUPLICATION §1` (dual button system)** → the one systemic
  primitive duplication (cva `<Button>` in 26 files vs legacy `.*-button` CSS in
  16–22). DESIGN.md §5 already specifies **one** button vocabulary; the refresh
  does not touch it. Consolidation is a Phase 4 migration, but the *foundation*
  decision — `<Button>` is the single source — is affirmed by DESIGN.md as-is.
- **F-08 (no global `:focus-visible` ring)** and **F-17 (color literals vs
  tokens)** → both are the design language being **under-applied**, not wrong.
  `--shadow-focus-ring` and `--color-danger` already exist in `index.css`; the gap
  is coverage, not vocabulary. Nothing to add to DESIGN.md; flagged for the
  consolidation notes.
- **F-01 (tablet band 721–1040px breaks) — the one genuine token GAP.** The audit
  traces it to a **single hardcoded 720px breakpoint in three places**
  (`useIsMobile.js`, `styles.css`, `Button.jsx`) with **no breakpoint token** and
  no tablet treatment. This is the clearest "propose a new shared token" candidate
  in the whole audit. **Deferred to the token-consolidation step (3.2+),** not
  invented here — but named now so it is not lost. See `RESPONSIVE_AUDIT.md`.
- **F-10 (Thai-first violated on load-bearing verbs)** → reinforced the principle
  "load-bearing verbs are Thai-first." This is copy, not a token; no DESIGN.md
  change.

**Rejected directions held.** The audit's "Rejected directions" (hero banners,
gradient/animated stat tiles, teal-as-decoration, glassmorphism) are exactly the
anti-references R-6 strengthened. The refresh moves the docs **toward** the audit's
restraint, not away from it.

---

## 3. Review against Phase 2 architecture

Phase 2 ([`../02-information-architecture/`](../02-information-architecture/))
established the operating model the refreshed purpose now names explicitly:

- **`1 Ticket = 1 Deal → 0..N Pricing Requests`** and the 17-handoff
  `ROLE_HANDOFF_MAP` → the purpose's "coordinate work as it moves between roles"
  and positioning's "move it one hand to the next" are a direct restatement of the
  Phase 2 handoff model, not a new claim.
- **`WORK_STATE_MODEL` (9 per-viewer states incl. "mine to act" / "waiting" /
  "already-decided")** → principle R-7's "whose move is it?" is the visual
  expression of that model. Phase 4 surfaces will need a state-to-visual mapping;
  the tokens for it (semantic status colors, `StatusBadge`) already exist.
- **All 24 routes preserved (`NAVIGATION_MIGRATION_MAP`)** → the refresh changes no
  route, permission, or nav gate. R-2's expanded user list is descriptive of roles
  that **already exist** in `DivisionAccessPolicy`; it grants nothing.
- **North star "Control Desk"** aligns with Phase 2's role-landing strategy (each
  role lands on the work waiting on them) — a dispatcher's desk, per role.

---

## 4. North-star reconciliation (recorded decision)

The brief's north star ("Operations Control Desk") sat in tension with the
committed DESIGN.md, which chose "The Steady Operations Desk" and **explicitly
rejects** a "mission-control cockpit / dark control room" feel.

**Decision (user-confirmed):** adopt **"The Operations Control Desk"** as the north
star, **and keep the anti-control-room guardrail.** The reconciliation is written
into DESIGN.md §1: *"'Control desk,' not 'control room': control comes from clarity
and predictability, never from ornament — and never from density-for-its-own-sake
or alarm."* The existing sentence *"It is not a mission-control cockpit either — a
calm daytime office tool, not a dark control room"* is retained and now does
double duty as the guardrail. The visual direction (light workspace, one dark
rail, rationed color, flat-by-default) is **unchanged**.

This was **not** the "full control-desk pivot" option (which would have dropped the
guardrail for a denser command-center look). Density stays earned, not default.

---

## 5. Known gap (carried, not introduced)

`frontend/.claude/rules/frontend-ui.md` — cited across Phase 1 and the UI-repair
governance as the "design law / frontend charter" — **is absent from this working
tree** (untracked; not present). `DESIGN.md` currently carries the design law that
matters for Phase 3. This is a pre-existing gap recorded in the Phase-1
`AUDIT_GAPS`, not something Step 3.1 changed; recreating the charter is out of
scope for this step. Flagged so the next agent does not assume it exists.

---

## 6. Files changed by Step 3.1

1. `PRODUCT.md` (root) — refreshed: users (8 roles), purpose (coordination /
   handoffs), positioning (added), north star, anti-references, principles (6),
   accessibility. Register/platform unchanged.
2. `DESIGN.md` (root) — **prose only**: frontmatter `description`; §1 north star +
   audience paragraph. **All token values, type ramp, and component specs
   byte-identical.**
3. `docs/ui-repair/03-design-foundation/README.md` — phase index (new).
4. `docs/ui-repair/03-design-foundation/STEP_3.1_PRODUCT_DESIGN_RECONCILIATION.md`
   — this doc (new).

`.impeccable/live/config.json` already existed → left untouched (live mode already
configured).

## 7. Next step

`/impeccable document` (Step 3.2) — generate/refresh the DESIGN.md token & primitive
**catalog** from the real `index.css` + components, and open the token/primitive
consolidation notes: the tablet breakpoint token (F-01), the `WorklistRow` /
stat-strip primitive (F-04/F-05), global `:focus-visible` coverage (F-08), and the
button-system consolidation (F-13). All as **proposals** — still no production
change before Phase 4.
