// "Is it even your turn" wrapper around the per-role next-action resolvers
// (salesActions.nextSalesAction / importActions.nextImportAction /
// accountActions.nextAccountAction) — ticket-detail IA rebuild Phase 1 (see
// docs/ui-repair/02-information-architecture/TICKET_INFORMATION_ARCHITECTURE.md,
// "Work-state banner" / "Action bar (sticky)").
//
// FIX 1 (P2, clutter-follow-up review round 2): this used to gate on the
// deal's CURRENT stage BEFORE ever calling a resolver — a deal sitting in a
// stage gated to a role other than the viewer read as "not your turn" even
// when that viewer's own resolver had a real action pending. That was wrong
// for every AUTO stage (SALES_STAGES' `auto: true` entries): an auto stage's
// `gate` names the role whose action CAUSED entry into it, not the role whose
// action is pending NOW — so the role about to act is always looking at the
// stage BEFORE the one their own action would produce. Two real examples
// (verified against TicketService.java):
//   - Account needs to confirm the deposit (nextAccountAction ->
//     confirmDeposit) while the deal still sits at ORDER_RECEIVED (gate:
//     'sales' — TicketService.java:1029 only advances to DEPOSIT_RECEIVED
//     once account confirms). The old gate-first check showed account
//     "รอฝ่ายขาย" and hid their own pending action.
//   - Import needs to issue the IR (nextImportAction -> issueImportRequest)
//     while the deal still sits at DEPOSIT_RECEIVED (gate: 'account' —
//     TicketService.java:702 only advances to PROCUREMENT once import issues
//     the IR). Same false "รอฝ่ายบัญชี" negative.
//
// The fix: ask the matching resolver FIRST, unconditionally. Only when the
// resolver comes back empty (nothing pending for this viewer) does the
// module fall back to reading the CURRENT stage's `gate` (stageMeta.js,
// SALES_STAGES) to say whose turn it looks like instead — the same field
// DealStagePanel/UpdateStageModal use to decide who may manually set a
// stage. ceo and sales_manager never get that fallback banner at all (see
// resolveWorkState's own doc comment) — only sales/import/account have a
// worklist resolver, and only those three's "nothing pending" case should
// ever read as a real waiting state for anyone with actual work to look for.
//
// Presentation only, same convention as salesViewScope.js / accountActions.js
// / importActions.js: this NEVER claims an action the server would reject,
// and never hides one the server would allow. canEditStage / canAdvance /
// canLost / canHold / canDormant / canResume in DealStagePanel.jsx (backed by
// the real `GET /{id}/actions`) remain the sole authority on what is
// actually clickable; this module only decides which ONE action (if any)
// leads the sticky bar, and what the header banner says when there isn't
// one for this viewer.

import { GATE_LABEL, stageMeta } from './stageMeta.js';
import { nextSalesAction } from './salesActions.js';
import { nextImportAction } from './importActions.js';
import { nextAccountAction } from './accountActions.js';

/**
 * The single next action for `user` on `deal`, or the "whose turn is it"
 * waiting state when the matching resolver has nothing pending for this
 * viewer right now.
 *
 * `pricingRequests` is passed straight through to nextSalesAction /
 * nextImportAction (see their own doc comments for the expected shape —
 * the ticket's own scoped list, e.g. `api.pricingRequests.listForTicket`).
 *
 * Returns `{ action, waitingRoleLabel }`:
 * - `action` is whatever the matching resolver returned for sales/import/
 *   account viewers — checked FIRST, before any stage-gate reasoning (FIX 1
 *   above). `null` for every other role (ceo's own two-signature-close
 *   action already has a real, server-gated button on the page — see
 *   TicketDetailPage's own `primaryAction` — this module does not duplicate
 *   it; sales_manager has no worklist resolver of its own yet).
 * - `waitingRoleLabel` (Thai, from stageMeta's GATE_LABEL) is set only when
 *   `action` is null — either because the resolver had nothing pending
 *   (sales/import/account) or because the role has no resolver at all
 *   (anything other than ceo/sales_manager, which get neither) — read off
 *   the deal's CURRENT stage gate. Never set alongside a real `action`.
 *
 * A CLOSED_LOST / ON_HOLD / DORMANT deal returns `{ action: null,
 * waitingRoleLabel: null }`: DealStagePanel already renders a dedicated,
 * unambiguous state banner for those (reopen / resume / dormant), so this
 * module stays out of the way rather than saying something that would
 * compete with it.
 */
export function resolveWorkState(user, deal, pricingRequests = []) {
  const role = user?.role;
  if (!deal || deal.lifecycle !== 'ACTIVE') return { action: null, waitingRoleLabel: null };

  const action = role === 'sales' ? nextSalesAction(deal, pricingRequests)
    : role === 'import' ? nextImportAction(deal, pricingRequests)
      : role === 'account' ? nextAccountAction(deal)
        : null;

  if (action) return { action, waitingRoleLabel: null };

  // ceo always has its own dedicated close-action button elsewhere; sales_manager
  // has no worklist resolver of its own yet. Neither gets a "waiting on someone
  // else" banner — showing one to a role that never has a resolver-driven action
  // in the first place would read as a permission statement this module doesn't
  // make (see the module's own doc comment).
  if (role === 'ceo' || role === 'sales_manager') return { action: null, waitingRoleLabel: null };

  const meta = stageMeta(deal.salesStage);
  return { action: null, waitingRoleLabel: meta ? GATE_LABEL[meta.gate] : null };
}
