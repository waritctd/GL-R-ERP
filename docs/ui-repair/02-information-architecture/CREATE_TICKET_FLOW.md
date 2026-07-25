# Create-Ticket (Create-Deal) Flow

The current create flow is a **6-section wizard trapped in a modal** (`TicketCreateModal.jsx`;
Phase-1 F-06): customer / project / contact / lines / details / review, "0/6 เสร็จ", with a
disabled submit until required sections are done. It reflows to a full-screen sheet on mobile,
but a rep loses page context, can't deep-link or resume a draft as a first-class URL, and
fights a cramped overlay on the phone-first surface. This defines the flow the create
experience should support and makes a reasoned container decision.

> Structure/flow only. No visual design (Phase 3), no build (Phase 4). Business rules cited
> from the create path (`TicketService.create` → `draft`; `canCreateTickets:['sales']`).

## Who and when
- **Permission:** creating a deal is `sales`-only (`canCreateTickets:['sales']`,
  `routes.js:298`). CEO can act on any deal but deal *creation* is the rep's job.
- **Result:** a new deal is born `status=draft`, `lifecycle=ACTIVE`, stage `LEAD_APPROACH`.
  Pricing is **not** part of creation — it's a later PCR sub-workflow (a deal exists long
  before it has a price).

## Flows the create experience must handle

| Case | Behaviour |
|---|---|
| **Draft creation** | A deal can be created and saved as a working draft before it's "complete"; the deal itself is `draft` status until it advances. Creation should not demand everything up front. |
| **Customer selection** | Search existing customers (`customers.search(q)`, `?search=`); pick one. |
| **Customer creation** | Create a new customer inline when not found (without leaving the flow). |
| **Project information** | Capture project/site + contact; project is the deal's context. |
| **One or many items** | Add ≥1 line item; support several. |
| **Known catalog product** | Pick from catalog (`catalog.search`) with a snapshot. |
| **Product not yet in catalog** | Allow a free-text / provisional line — a real sales case is "product not in catalog yet" (the catalog snapshot becomes mandatory only at PCR-submit, not at deal creation). |
| **Incomplete product requirement** | Allow a line with partial detail (qty/spec TBD) so a lead isn't blocked on full specs. |
| **Pricing not required yet** | Default — most new deals have no pricing request. Creation must not force a PCR. |
| **Pricing required immediately** | Offer (not force) "start a pricing request" as a *next step after* the deal is created — never inside creation (PCR is its own aggregate). |
| **Multiple pricing requests** | Because a deal can have many PCRs (designer/owner/buyer + revisions), the post-create hand-off leads to the deal's Pricing tab, not a single embedded PCR. |
| **Save and resume** | A partially-entered deal must be resumable — ideally as a real URL (a `draft` deal is a first-class record), not lost with a closed modal. |
| **Validation** | Required = enough to create (customer + ≥1 line + project context); everything else is progressive. Validate inline, associate errors with fields (F-09 fix). |
| **Unsaved changes** | Warn before discarding unsaved input; offer save-as-draft. |
| **Duplicate an existing deal** | Support "duplicate this deal" (copy customer/project/lines into a new draft) — a real repeat-order pattern. |
| **Mobile use** | Must be completable on a phone (the sales persona is field-facing) — a full-screen focused flow, not a modal-in-a-viewport. |
| **Failure recovery** | If create fails (network/validation), preserve input and show a recoverable error — never lose a half-built deal. |
| **Permission behaviour** | Non-sales roles don't see "create deal"; the unlinked-user seed case (`employeeId:null`) must degrade quietly, not throw a raw error toast (F-16). |
| **Review before creation** | A final review step (the existing "ตรวจสอบ" section) confirming customer, project, lines before commit. |

## Container decision

**Options evaluated:**

| Container | Fit | Verdict |
|---|---|---|
| Desktop dialog (small modal) | Too small for a 6-section aggregate; the current pain | ✗ |
| Wide dialog | More room, still an overlay: no deep-link, no resume-as-URL, context lost, mobile = modal-in-viewport | ✗ |
| Dedicated page (desktop) | First-class URL, deep-linkable, resumable, room for progressive sections | ✓ core |
| Responsive full-screen workflow (mobile) | The phone-first requirement; focused, one-thumb, no cramped overlay | ✓ core |
| Hybrid (page on desktop, full-screen flow on mobile, same route) | Same first-class route, adapts by viewport | ✓ **chosen** |

**Decision: a dedicated create route that is a full-page progressive workflow on desktop and a
full-screen focused flow on mobile — one route, responsive.** Rationale:

1. **First-class URL** — `draft` deals are real records; a create route (e.g. a `/tickets/new`
   or a draft deal's own `/tickets/:id` in an editing state — exact path decided in Phase 4,
   see below) can be deep-linked, resumed, and recovered. A modal can't.
2. **Room for the aggregate** — six progressive sections + inline customer creation + multi-
   line items need space the modal doesn't have; the page hosts them without nesting cards.
3. **Mobile is the persona** — a full-screen flow is the correct mobile container (DESIGN.md:
   "complex mobile tasks may become full-screen workflows"), not a modal squeezed into a
   phone viewport with a toast firing over it (the current F-06 failure).
4. **Keep the checklist metaphor** — the "0/6 เสร็จ" progressive-completion idea is *good*;
   it just needs to move out of the overlay onto the page. Sections can be a single scroll
   with a progress rail, or steps — a Phase-4 visual choice, but the *checklist* stays.

**Route note (for Phase 4, not decided/changed now):** whether the create surface is a new
path (`/tickets/new`) or an editing state of a freshly-created `draft` deal at `/tickets/:id`
is an implementation choice with a route-registration implication. Both preserve every
existing route. The IA recommendation is the latter where feasible (create → immediately a
`draft` deal with its own URL → resume by revisiting that URL), because it makes "save and
resume" and "recover from failure" fall out for free and unifies create/edit. This is a
**recommendation carried to Phase 4**, not a Phase-2 route change.

## Flow shape (structure, not visuals)

1. **Start** — from "สร้างดีล" (sales work queue / pipeline). Creates/enters a draft.
2. **Customer** — search or create inline. (Required to proceed to review.)
3. **Project & contact** — project/site context + contact.
4. **Items** — add ≥1 line; catalog pick *or* provisional/non-catalog line; partial specs OK.
5. **Details** — deal metadata (entry channel, tender requirement, etc. — existing fields).
6. **Review** — confirm customer/project/lines; create/commit.
7. **After create** — land on the new deal (`/tickets/:id`); offer next steps as *options*:
   "เริ่มใบขอราคา" (start a PCR), advance stage, add activity. Pricing is offered, never forced.

Save-as-draft available at every step; unsaved-changes guard on exit; failure preserves input.

## What this flow deliberately does NOT do

- Does **not** embed pricing into creation (PCR is a separate aggregate; a deal exists without
  a price).
- Does **not** force a catalog match at creation (catalog snapshot is mandatory at
  *PCR-submit*, not deal birth — supports the "product not in catalog yet" case).
- Does **not** change `canCreateTickets` or any create-endpoint contract.
- Does **not** decide the exact route/visual — those are Phase 3/4. It fixes the *container
  class* (dedicated responsive page/flow) and the *flow content*.
