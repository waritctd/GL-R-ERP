import React from 'react';

/**
 * Layout-preserving loading placeholders. Purely decorative — always
 * `aria-hidden`. The caller is responsible for labeling the surrounding
 * container (e.g. `<div aria-busy="true" aria-label="กำลังโหลดข้อมูลพนักงาน">`)
 * so assistive tech announces the loading state; the shimmer blocks
 * themselves carry no semantics.
 */
export function Skeleton({ width = '100%', height = 16, radius = 'var(--radius-md)', className = '' }) {
  return (
    <span
      className={`skeleton block bg-[linear-gradient(90deg,var(--color-surface-muted)_25%,var(--color-border)_37%,var(--color-surface-muted)_63%)] bg-[length:400%_100%] animate-[skeleton-shimmer_1.4s_ease_infinite] motion-reduce:animate-none motion-reduce:bg-none motion-reduce:bg-surface-muted ${className}`.trim()}
      aria-hidden="true"
      style={{ width, height, borderRadius: radius }}
    />
  );
}

export function SkeletonText({ lines = 3, lastLineWidth = '60%', className = '' }) {
  return (
    <div className={`grid gap-2 ${className}`.trim()} aria-hidden="true">
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton key={index} height={12} width={index === lines - 1 ? lastLineWidth : '100%'} />
      ))}
    </div>
  );
}

export function SkeletonCard({ lines = 3, className = '' }) {
  return (
    <div className={`grid gap-3 p-4 border border-border rounded-md bg-surface ${className}`.trim()} aria-hidden="true">
      <div className="flex items-center gap-3">
        <Skeleton width={40} height={40} radius="var(--radius-pill)" />
        <Skeleton width="40%" height={14} />
      </div>
      <SkeletonText lines={lines} />
    </div>
  );
}
