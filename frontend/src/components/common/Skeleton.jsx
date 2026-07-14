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
      className={`skeleton ${className}`.trim()}
      aria-hidden="true"
      style={{ width, height, borderRadius: radius }}
    />
  );
}

export function SkeletonText({ lines = 3, lastLineWidth = '60%', className = '' }) {
  return (
    <div className={`skeleton-text ${className}`.trim()} aria-hidden="true">
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton key={index} height={12} width={index === lines - 1 ? lastLineWidth : '100%'} />
      ))}
    </div>
  );
}

export function SkeletonCard({ lines = 3, className = '' }) {
  return (
    <div className={`skeleton-card ${className}`.trim()} aria-hidden="true">
      <div className="skeleton-card-header">
        <Skeleton width={40} height={40} radius="var(--radius-pill)" />
        <Skeleton width="40%" height={14} />
      </div>
      <SkeletonText lines={lines} />
    </div>
  );
}
