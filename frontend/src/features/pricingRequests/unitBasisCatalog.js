// The unit-basis vocabulary, fetched from the backend instead of hand-copied a second time.
//
// Mirrors GET /api/meta/unit-bases (UnitBasisMetaController, pricingrequest/), which serves
// UnitBasis's four canonical codes with their Thai display units. This is the fetch-based sibling
// of pricingRequestMeta.js's UNIT_BASIS_OPTIONS — the pre-existing hand-maintained mirror that
// file's other consumers (PricingRequestCreateModal's unit picker, unitBasisLabel()) still use.
// Kept in its own file rather than folded into pricingRequestMeta.js because that file is imported
// by mockApi.js; this is the same split stageCatalog.js/stageMeta.js already draws for deal
// stages, for the same reason (a React Query hook has no business in the data layer's import graph).
//
// Only PricingRequestDetailPage's factory-quote-response unit select reads this today (owner
// ruling: Import must be able to set the unit a factory actually quoted in, at runtime, from the
// backend's own list — not a hardcoded one). Nothing else was asked to move off
// UNIT_BASIS_OPTIONS, and this task's "keep it minimal" scope deliberately leaves it in place.

import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

/** What every consumer gets before the catalog has loaded. See EMPTY_STAGE_CATALOG's own reasoning
 * in stageCatalog.js: deliberately empty rather than a hardcoded fallback, so a broken endpoint
 * shows up as an empty select for one frame instead of hiding behind stale-but-plausible data. */
export const EMPTY_UNIT_BASIS_CATALOG = Object.freeze({ unitBases: [] });

/**
 * Immutable server data — the same four constants for every user, for the life of the deployed
 * build — so it is fetched once and never refetched. Same staleTime/gcTime reasoning as
 * useStageCatalog.
 */
export function useUnitBasisCatalog() {
  const query = useQuery({
    queryKey: queryKeys.unitBasisCatalog(),
    queryFn: () => api.meta.unitBases(),
    staleTime: Infinity,
    gcTime: Infinity,
  });
  return {
    unitBases: query.data?.unitBases ?? EMPTY_UNIT_BASIS_CATALOG.unitBases,
    isLoading: query.isLoading,
    error: query.error ?? null,
  };
}
