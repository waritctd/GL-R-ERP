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
  stages: [], phases: [], lostReasons: [], cancelReasons: [],
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
