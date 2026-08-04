import React from 'react';
import { Skeleton } from './Skeleton.jsx';

/**
 * Suspense fallback for lazily-loaded route pages. Reuses the existing Skeleton
 * primitive so a chunk load looks like normal content loading rather than a blank
 * flash. Rendered inside the AppShell content area, so the sidebar/topbar persist.
 */
export function RouteFallback() {
  return (
    <div
      className="grid w-full grid-cols-1 gap-[18px] min-w-0 max-w-[1320px] route-fallback"
      role="status"
      aria-live="polite"
      aria-busy="true"
      aria-label="กำลังโหลดหน้าที่เลือก"
    >
      <span className="sr-only">กำลังโหลดหน้าที่เลือก</span>
      <Skeleton height={28} />
      <Skeleton height={140} />
      <Skeleton height={140} />
    </div>
  );
}
