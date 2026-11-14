// The unit-basis vocabulary, fetched from the backend instead of hand-copied a second time. Mirrors GET
// /api/meta/unit-bases (UnitBasisMetaController, pricingrequest/), which serves UnitBasis's four canonical
// codes with their Thai display units.

import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

/** What every consumer gets before the catalog has loaded. See EMPTY_STAGE_CATALOG's own reasoning in
 * stageCatalog.js: deliberately empty rather than a hardcoded fallback, so a broken endpoint shows up as an
 * empty select for one frame instead of hiding behind stale-but-plausible data. */
const EMPTY_UNIT_BASIS_CATALOG = Object.freeze({ unitBases: [] });

/** Immutable server data — the same four constants for every user, for the life of the deployed build — so it
 * is fetched once and never refetched. Same staleTime/gcTime reasoning as useStageCatalog. */
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
