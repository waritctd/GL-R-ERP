// The deal pipeline's shape, fetched from the backend instead of declared here. This module replaces
// stageMeta.js's SALES_STAGES/SALES_PHASES literals and every lookup built on them. The functions below are
// lookups INTO that payload — they decide nothing.

import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { QUOTATION_RECIPIENT_OPTIONS } from '../quotations/quotationMeta.js';
import { entryChannelLabel } from '../../utils/format.js';
import { assertStageLabelsComplete } from './stageMeta.js';

/** What every consumer gets before the catalog has loaded. Deliberately empty rather than a hardcoded fallback:
 * a fallback would be the mirror this module exists to delete, and would hide a broken endpoint behind
 * stale-but-plausible data. */
export const EMPTY_STAGE_CATALOG = Object.freeze({
  stages: [], phases: [], lostReasons: [], cancelReasons: [], routes: {},
});

/** The catalog is immutable server data — the same fifteen constants for every user, for the life of the
 * deployed build — so it is fetched once and never refetched. */
export function useStageCatalog() {
  const query = useQuery({
    queryKey: queryKeys.dealStageCatalog(),
    queryFn: () => api.meta.dealStages(),
    staleTime: Infinity,
    gcTime: Infinity,
    // Validate Thai labels when stage data arrives so newly added backend stages cannot silently display raw
    // codes.
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

// Route-aware lookups ──────────────────────────────────────────────────────────────────────── A deal's entry
// channel decides which of the fifteen stages it visits (DealRoute.java). Only an explicit `onRoute: false`
// takes a stage off the path.

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

/** "Which step of how many, on THIS deal's route" — the honest replacement for the catalog-wide `index + 1` /
 * `stages.length`. An unknown code has position 0. */
export function routePosition(catalog, salesStage, stageDecisions) {
  const stages = catalog?.stages ?? [];
  const off = offRouteCodes(stageDecisions);
  const total = stages.filter((stage) => !off.has(stage.code)).length;
  const currentIdx = stageIndexIn(catalog, salesStage);
  if (currentIdx < 0) return { position: 0, total };
  const passed = stages.slice(0, currentIdx + 1).filter((stage) => !off.has(stage.code)).length;
  return { position: Math.min(Math.max(passed, 1), Math.max(total, 1)), total };
}

/** The next stage on this deal's route, or null at the end (and for an unknown code). Same contract as
 * nextStageIn — which is left untouched for its other caller — except it steps over off-route stages. */
export function nextOnRoute(catalog, salesStage, stageDecisions) {
  const stages = catalog?.stages ?? [];
  const idx = stageIndexIn(catalog, salesStage);
  if (idx < 0) return null;
  const off = offRouteCodes(stageDecisions);
  return stages.slice(idx + 1).find((stage) => !off.has(stage.code)) ?? null;
}

// Route lookups by ENTRY CHANNEL (for rows that have no per-deal decisions) ────────────────── A deal LIST row
// carries `deal.entryChannel` but not the per-deal `stageDecisions` payload above, and fetching one per row
// would be an N+1 on the hottest sales endpoint.

/** The stages this channel's route visits, as catalog rows in catalog order; all stages if unserved. */
export function routeForChannel(catalog, entryChannel) {
  const stages = catalog?.stages ?? [];
  const served = entryChannel == null ? null : catalog?.routes?.[entryChannel];
  if (!Array.isArray(served) || served.length === 0) return stages;
  const codes = new Set(served);
  return stages.filter((stage) => codes.has(stage.code));
}

/**
 * The quotation parties (DESIGNER/OWNER/BUYER) whose quote stage this channel's served route skips —
 * the deal page's and the worklist's one source for the sales route choice. null while the
 * catalog's routes have not loaded: an unknown route is never "every party is on it".
 */
export function offRoutePartiesFor(catalog, entryChannel) {
  if (Object.keys(catalog?.routes ?? {}).length === 0) return null;
  const onRoute = new Set(routeForChannel(catalog, entryChannel).map((stage) => stage.code));
  return new Set(QUOTATION_RECIPIENT_OPTIONS.filter((option) => !onRoute.has(option.stage)).map((option) => option.code));
}

/**
 * The sentence the server refuses a direct quotation with when it is addressed to a party off the deal's
 * route (DealRoute.refusalMessage, verbatim: the channel phrase, the stage's display number, the remedy),
 * or null when the party is on the route, the channel has none (UNSPECIFIED) or the catalog has not loaded.
 * Shown beside a disabled recipient pill and passed to the checklist as `offRouteRecipientMessage`.
 */
export function recipientRouteRefusal(catalog, entryChannel, recipientCode) {
  const option = QUOTATION_RECIPIENT_OPTIONS.find((o) => o.code === recipientCode);
  if (!option || !offRoutePartiesFor(catalog, entryChannel)?.has(recipientCode)) return null;
  return `ดีลนี้เป็น${entryChannelLabel(entryChannel).label} — ขั้นที่ ${findStage(catalog, option.stage)?.no} ไม่อยู่ในเส้นทางของดีลนี้ — แก้ช่องทางดีลก่อน`;
}

/** "Which step of how many, on this CHANNEL's route" — routePosition's contract, keyed by channel instead of
 * per-deal decisions. */
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
