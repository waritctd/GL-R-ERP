// The deal pipeline's shape, fetched from the backend instead of declared here.
//
// This module replaces stageMeta.js's SALES_STAGES/SALES_PHASES literals and every lookup built on
// them. It holds no rule and no list: the fifteen stages, their display number, phase, write gate
// and auto-advance flag all arrive from GET /api/meta/deal-stages, which serves them straight off
// th.co.glr.hr.ticket.DealStage. The functions below are lookups INTO that payload — they decide
// nothing.
//
// Why: the old literal was a hand-maintained copy of DealStage.ORDER plus a hand-maintained copy of
// TicketService's three write-gate sets, and nothing compared either to the Java. When V143 split
// QUOTE_OWNER out of QUOTE_DESIGN_SIDE the copy stayed at fourteen entries, so a deal legitimately
// sitting at the new stage rendered with no number, no phase and no gate — silently, because a
// missing entry just returned null.
//
// "Which stages may I move THIS deal to?" is a different question and is NOT answered here: it is
// per-deal and per-user, so it comes from the ticket's own decision payload
// (GET /api/tickets/{id}/actions -> stageDecisions). Nothing in this file may grow a `can*`.

import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { assertStageLabelsComplete } from './stageMeta.js';

/**
 * What every consumer gets before the catalog has loaded. Deliberately empty rather than a
 * hardcoded fallback: a fallback would be the mirror this module exists to delete, and would hide
 * a broken endpoint behind stale-but-plausible data. Callers render a stage's raw code (or
 * nothing) for the one frame it takes to arrive.
 */
export const EMPTY_STAGE_CATALOG = Object.freeze({
  stages: [], phases: [], lostReasons: [], cancelReasons: [], routes: {},
});

/**
 * The catalog is immutable server data — the same fifteen constants for every user, for the life
 * of the deployed build — so it is fetched once and never refetched. staleTime/gcTime Infinity
 * rather than a shorter window because there is no event that could invalidate it short of a
 * backend deploy, which reloads the app anyway.
 */
export function useStageCatalog() {
  const query = useQuery({
    queryKey: queryKeys.dealStageCatalog(),
    queryFn: () => api.meta.dealStages(),
    staleTime: Infinity,
    gcTime: Infinity,
    // THE GUARD. Every stage code the backend knows about must have a Thai label on this side, and
    // a code without one must be loud. Running it in `select` means it fires the moment the
    // payload lands, on every page that reads the catalog, in dev and in prod — not only if a
    // human happens to open the one deal sitting on the unlabelled stage. See
    // assertStageLabelsComplete for what "loud" means in each environment.
    select: (data) => {
      const catalog = normalizeCatalog(data);
      assertStageLabelsComplete(catalog.stages.map((stage) => stage.code));
      return catalog;
    },
  });
  return {
    catalog: query.data ?? EMPTY_STAGE_CATALOG,
    isLoading: query.isLoading,
    error: query.error ?? null,
  };
}

function normalizeCatalog(data) {
  return {
    stages: data?.stages ?? [],
    phases: data?.phases ?? [],
    lostReasons: data?.lostReasons ?? [],
    cancelReasons: data?.cancelReasons ?? [],
    // entryChannel -> ordered stage codes (DealRoute.path). Absent on an older backend.
    routes: data?.routes ?? {},
  };
}

/** The stage's own row, or null for a code the backend did not serve. */
export function findStage(catalog, code) {
  return catalog?.stages?.find((stage) => stage.code === code) ?? null;
}

/** 0-based pipeline position, or -1. Mirrors DealStage.indexOf's contract for an unknown code. */
export function stageIndexIn(catalog, code) {
  return catalog?.stages?.findIndex((stage) => stage.code === code) ?? -1;
}

/** The next stage in pipeline order, or null at the end (and for an unknown code). */
export function nextStageIn(catalog, code) {
  const stages = catalog?.stages ?? [];
  const idx = stageIndexIn(catalog, code);
  return idx >= 0 && idx < stages.length - 1 ? stages[idx + 1] : null;
}

/** The stages belonging to one phase, in pipeline order. */
export function stagesInPhase(catalog, phaseId) {
  return (catalog?.stages ?? []).filter((stage) => stage.phase === phaseId);
}

/** The phase id a stage belongs to, or null. */
export function phaseIdOf(catalog, code) {
  return findStage(catalog, code)?.phase ?? null;
}

// ── Route-aware lookups ────────────────────────────────────────────────────────────────────────
// A deal's entry channel decides which of the fifteen stages it visits (DealRoute.java). The
// backend serves that verdict per stage as `onRoute` on GET /api/tickets/{id}/actions
// (`stageDecisions`); the functions below only READ it — they never derive a route from a channel
// name, so there is no second copy of DealRoute's tables to drift.
//
// Only an explicit `onRoute: false` takes a stage off the path. A missing decision, a decision
// without the field (an older backend, a mock), or no decisions at all leave every stage on-route,
// which is exactly today's behaviour.

/** Codes the server marked `onRoute: false` for this deal. */
function offRouteCodes(stageDecisions) {
  return new Set(
    (stageDecisions ?? []).filter((decision) => decision?.onRoute === false).map((d) => d.stage),
  );
}

/** The stages this deal's route visits, in catalog order (never re-ordered). */
export function routePath(catalog, stageDecisions) {
  const off = offRouteCodes(stageDecisions);
  return (catalog?.stages ?? []).filter((stage) => !off.has(stage.code));
}

/**
 * "Which step of how many, on THIS deal's route" — the honest replacement for the catalog-wide
 * `index + 1` / `stages.length`. `position` counts the on-route stages up to and including the
 * current one, so a deal sitting on an off-route stage (channel corrected after it got there)
 * reads as the number of on-route stages it has passed, never 0 and never past `total`.
 * An unknown code has position 0.
 */
export function routePosition(catalog, salesStage, stageDecisions) {
  const stages = catalog?.stages ?? [];
  const off = offRouteCodes(stageDecisions);
  const total = stages.filter((stage) => !off.has(stage.code)).length;
  const currentIdx = stageIndexIn(catalog, salesStage);
  if (currentIdx < 0) return { position: 0, total };
  const passed = stages.slice(0, currentIdx + 1).filter((stage) => !off.has(stage.code)).length;
  return { position: Math.min(Math.max(passed, 1), Math.max(total, 1)), total };
}

/**
 * The next stage on this deal's route, or null at the end (and for an unknown code). Same contract
 * as nextStageIn — which is left untouched for its other caller — except it steps over off-route
 * stages. A lookup, not a permission: whether the user may actually move there is still the
 * server's verdict on that stage's decision.
 */
export function nextOnRoute(catalog, salesStage, stageDecisions) {
  const stages = catalog?.stages ?? [];
  const idx = stageIndexIn(catalog, salesStage);
  if (idx < 0) return null;
  const off = offRouteCodes(stageDecisions);
  return stages.slice(idx + 1).find((stage) => !off.has(stage.code)) ?? null;
}

// ── Route lookups by ENTRY CHANNEL (for rows that have no per-deal decisions) ──────────────────
// A deal LIST row carries `deal.entryChannel` but not the per-deal `stageDecisions` payload above,
// and fetching one per row would be an N+1 on the hottest sales endpoint. So the backend also
// serves each channel's route as `catalog.routes` (GET /api/meta/deal-stages -> DealRoute.path).
// These functions only READ that served data; the route table lives in DealRoute.java, and
// stageCatalog.test.js pins the mock fixture against it.
//
// Anything without a served route — `routes` absent (older backend), an unknown channel, a null
// channel — is the full pipeline, which is what a deal with no stated route has (UNSPECIFIED gates
// nothing on the server either).

/** The stages this channel's route visits, as catalog rows in catalog order; all stages if unserved. */
export function routeForChannel(catalog, entryChannel) {
  const stages = catalog?.stages ?? [];
  const served = entryChannel == null ? null : catalog?.routes?.[entryChannel];
  if (!Array.isArray(served) || served.length === 0) return stages;
  const codes = new Set(served);
  return stages.filter((stage) => codes.has(stage.code));
}

/**
 * "Which step of how many, on this CHANNEL's route" — routePosition's contract, keyed by channel
 * instead of per-deal decisions. `position` counts the route's stages up to and including the
 * current one (so a deal sitting on a since-off-route stage reads as the on-route stages it has
 * passed, never 0 and never past `total`); an unknown stage code is position 0.
 */
export function routePositionForChannel(catalog, salesStage, entryChannel) {
  const route = routeForChannel(catalog, entryChannel);
  const total = route.length;
  const currentIdx = stageIndexIn(catalog, salesStage);
  if (currentIdx < 0) return { position: 0, total };
  const onRoute = new Set(route.map((stage) => stage.code));
  const passed = (catalog?.stages ?? [])
    .slice(0, currentIdx + 1)
    .filter((stage) => onRoute.has(stage.code)).length;
  return { position: Math.min(Math.max(passed, 1), Math.max(total, 1)), total };
}
