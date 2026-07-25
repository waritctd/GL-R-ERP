# Phase 3 Design-Foundation Decision Log

Decisions made establishing the design language and semantic tokens. Analysis/
proposal phase — **no production code changed.** Extends the Phase-2
`02-information-architecture/IA_DECISION_LOG.md`.

## Foundation decisions

| # | Decision | Rationale |
|---|---|---|
| **D-T1** | **`index.css @theme` is the single source of truth for tokens.** The duplicate `styles.css :root` block (a subset) is collapsed into it in Phase 4. | Two token sources drift; `@theme` is the superset the app resolves. No value changes in the collapse. |
| **D-T2** | **North star = "The Operations Control Desk"** with the anti-control-room guardrail kept. | User decision (Step 3.1). Adopts the brief's wording; keeps "control desk, not control room — calm, not a dark cockpit." |
| **D-T3** | **One controlled default density, tuned per surface. No user-facing density preference.** | No demonstrated need; a preference is a second system to maintain. Add only when a real role asks. |
| **D-T4** | **Two breakpoint tokens only** (`--breakpoint-mobile` 720, `--breakpoint-tablet` 1040); reconcile the 520/560/900 one-offs case by case. | Don't invent many breakpoints. The 720 literal is copy-pasted 121×; a token is the highest-value gap (F-01 root cause). |
| **D-T5** | **Keep Sarabun (CDN) as the sole family**; resolve the missing 800 weight (load it or retune heads to 700) before treating 800 as real; record CDN→self-host as a Phase-4+ consideration. | Sarabun is the correct Thai+Latin family and already the whole system; the 800 gap is a real rendering bug; CDN is an on-prem/offline risk but not urgent. |
| **D-T6** | **~85 statuses → 6 tones**, ratified. Never one colour per status. Attendance lateness never `danger`. | The primitive already meets the anti-goal; codify it. §76 keeps lateness non-punitive. |
| **D-T7** | **Extend the shared system, don't rebuild.** Six named primitives are proposed as *new* (Drawer, Timeline, FilterBar, StickyActionBar, DescriptionList, InlineAlert) + WorklistRow/ApprovalTask; everything else is consolidation. | Audit verdict: the shared system is largely healthy. Rebuilding it would be the risk, not the fix. |
| **D-T8** | **Root DESIGN.md carries the 21-section philosophy** required by the Step-3.2 brief, deviating from Impeccable/Stitch's fixed 6-section body format. Frontmatter tokens stay spec-compliant. | The brief is explicit (21 sections) and section K says don't let `document` overwrite the proposed system. See "Impeccable output review" below for the tradeoff and the reversible alternative. |

## Impeccable output review (spec K)

`/impeccable document`'s job is to **scan the coded design language** and emit a
DESIGN.md from it. That scan was performed thoroughly (two extraction passes over
`index.css`, `styles.css`, all `components/common/*`, `format.js`, fonts, motion —
recorded across [`TOKENS.md`](TOKENS.md), [`STATUS_PRESENTATION.md`](STATUS_PRESENTATION.md),
[`COMPONENT_CONTRACTS.md`](COMPONENT_CONTRACTS.md)). Per the brief, the coded system
was **compared** to the proposed direction — **not auto-overwritten** onto it.

### Existing coded behaviours worth **retaining** (accepted into the system)
- **The token set itself** (`index.css @theme`) — cohesive, cool, rationed; keep as the source of truth.
- **`StatusBadge` = text + colour** (never colour-only) — already WCAG-1.4.1 safe; the model for all status.
- **`format.js` as the canonical value→{label,tone} hub** — one place, ~85 values → 6 tones; keep and extend.
- **The muted floor already remediated** — `text-muted` `#64748b` for body, `text-faint` reserved for icons/placeholder/sidebar. Keep as a hard rule.
- **Flat-by-default elevation** (resting shadow ~invisible) and the reduced-motion handling (4 blocks) — exactly the target restraint.
- **`DataTable` mobile-card reflow**, `Modal` focus-trap/Escape/restore, `FormField` ARIA auto-wiring, `CollapsibleSection` a11y, curated `Icon` wrapper — mature, reuse as-is.
- **The cva `<Button>` as the single button system** — the mature primitive to consolidate onto.

### Existing coded behaviours to **deprecate**
- **The legacy `.*-button` CSS classes** (dual button system) → migrate to `<Button>`, retire (F-13).
- **The duplicate `styles.css :root` token block** → collapse into `@theme` (D-T1).
- **The bare `720` literal ×121** → a breakpoint token (D-T4).
- **Hardcoded scrim rgba ×5 + `NotificationBell` hex ×5 + `TicketCreateModal` `#ef4444` ×14** → tokens.
- **`outline:none` in 4 places** without a replacement ring → a global `:focus-visible` ring (A-03).
- **`ChangePasswordModal`'s hand-rolled backdrop** → `Modal.jsx` (F-19).
- **The metric-card-hero landings** → compact stat strip + promoted worklist (F-04).
- **The stale `.impeccable/design.json` sidecar** (Jul 17) → regenerate alongside a future DESIGN.md refresh (optional; not blocking).

### Impeccable recommendations **accepted**
- Product register (not brand): distinctiveness from clarity/workflow, not decoration — applied throughout.
- The shared **absolute bans** (side-stripe borders, gradient text, glassmorphism-as-default, hero-metric template, identical card grids, tracked eyebrows, 01/02/03 markers) — folded into DESIGN.md §20 and PRODUCT.md anti-references.
- The product-register guidance (state-rich semantic vocabulary; skeletons over spinners; empty states that teach; consistent affordances; 150–250ms functional motion; modal-as-last-resort) — folded into the component contracts and philosophy.
- The a11y quality floor (visible focus, reduced motion, contrast) — §19.

### Impeccable recommendations **rejected** (with reason)
- **The fixed 6-section DESIGN.md body format** — *rejected for this repo.* Reason: the Step-3.2 brief explicitly requires a 21-section design-language document, and an ERP repair effort needs the density philosophy, status/work-state philosophy, data-table/form/dialog philosophies, and a screenshot checklist as first-class sections — folding them into "Overview/Components" (as the spec directs) would bury the exact guidance Phase 4 needs. **Tradeoff:** root DESIGN.md no longer matches the Stitch/awesome-design-md 6-section parser; the **frontmatter tokens remain fully spec-compliant**, so the linter and Impeccable live panel still work. **Reversible alternative** if strict tooling compliance is wanted later: keep a 6-section DESIGN.md at root and move the 21-section content to `03-design-foundation/DESIGN_LANGUAGE.md`.
- **The brand-register "take one real aesthetic risk / signature element / display-face pairing" framing** (frontend-design skill) — *rejected.* Reason: this is product register. The "risk" here is disciplined restraint and workflow legibility; a signature display face or hero moment would violate the anti-references. Distinctiveness comes from clarity, not a decorative signature.
- **Auto-overwriting DESIGN.md from the scan** — *rejected* per the brief's explicit instruction (section K) and the `document` reference's own "do not silently overwrite" rule.
- **Fabricating a dark-mode token set** — *rejected/deferred.* Dark mode is out of scope; dark values are marked `future`, not invented (TOKENS.md).

## Open risks before token implementation (Phase 4)
1. **Ticket-status case mismatch (D-S1)** — frontend lowercase keys vs backend UPPERCASE `TicketStatus`; a raw value falls through to an English string + neutral tone. Confirm the API lowercases, or normalise — this is a contract question, verify against the service, not the mock.
2. **The breakpoint-token migration touches ~124 sites** (121 utilities + 3 defs). High-volume, must be sliced and screenshotted, not swept.
3. **The 800-weight fallback** silently degrades headings today; resolve before Phase-4 typography work assumes 800 renders.
4. **Layer-order `!important`** — moving rules out of `@layer legacy` can flip which declaration wins; every migrated slice must re-verify the cascade.
5. **Authz must not ride along** — no token/primitive slice may touch a role gate or scope; if one appears to, it stops being a UI-repair slice (governance).
6. **`frontend/.claude/rules/frontend-ui.md` is absent** from the tree though cited as "design law"; DESIGN.md now carries that law. Recreating the charter is a separate task.
