import { Skeleton } from './Skeleton.jsx';

/**
 * Suspense fallback for lazily-loaded route pages. Reuses the existing Skeleton
 * primitive so a chunk load looks like normal content loading rather than a blank
 * flash. Rendered inside the AppShell content area, so the sidebar/topbar persist.
 */
export function RouteFallback() {
  return (
    <div className="page-stack" role="status" aria-live="polite" aria-busy="true">
      <Skeleton height={28} />
      <Skeleton height={140} />
      <Skeleton height={140} />
    </div>
  );
}
