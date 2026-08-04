import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TaxAllowanceSummaryPanel } from './TaxAllowanceSummaryPanel.jsx';
import { taxAllowanceStatusInfo } from './taxAllowanceStatus.js';

globalThis.React = React;

function renderPanel(summary) {
  return render(
    <MemoryRouter>
      <TaxAllowanceSummaryPanel summary={summary} />
    </MemoryRouter>,
  );
}

// Built through the real status helper rather than hand-written statusInfo objects, so a change to
// the status vocabulary surfaces here instead of passing against a stale fixture.
function summaryFor(declaration, extra = {}) {
  return {
    statusInfo: taxAllowanceStatusInfo(declaration),
    declaredTotal: 120000,
    evidenceCount: 2,
    expiresOn: null,
    ...extra,
  };
}

describe('TaxAllowanceSummaryPanel', () => {
  it('invites a first submission when nothing has been filed', () => {
    renderPanel(summaryFor(null));
    expect(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง ล.ย.01' })).not.toBeNull();
  });

  it('is read-only wording while the declaration is pending review', () => {
    renderPanel(summaryFor({ status: 'PENDING' }));
    expect(screen.getByRole('button', { name: 'ดูแบบแจ้ง ล.ย.01' })).not.toBeNull();
    // PENDING is not actionable on the form, so the panel must not promise a resubmission.
    expect(screen.queryByRole('button', { name: 'ยื่นฉบับใหม่' })).toBeNull();
  });

  it('surfaces the rejection reason inline — one of only two places it can be discovered', () => {
    renderPanel(summaryFor({ status: 'REJECTED', reviewerNote: 'หลักฐานเบี้ยประกันไม่ครบ' }));
    expect(screen.getByText(/หลักฐานเบี้ยประกันไม่ครบ/)).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ยื่นฉบับใหม่' })).not.toBeNull();
  });

  it('warns that an expired declaration stops the deduction', () => {
    renderPanel(summaryFor({ status: 'EXPIRED' }, { expiresOn: '2026-01-31' }));
    expect(screen.getByText(/หมดอายุแล้ว/)).not.toBeNull();
    expect(screen.getByRole('button', { name: 'ยื่นฉบับใหม่' })).not.toBeNull();
  });

  it('shows no warning strip once approved and applied', () => {
    renderPanel(summaryFor({ status: 'APPROVED', appliedAt: '2026-02-01T00:00:00Z', appliedEffectiveMonth: 2 }));
    expect(screen.queryByText(/ถูกปฏิเสธ/)).toBeNull();
    expect(screen.queryByText(/หมดอายุแล้ว/)).toBeNull();
    expect(screen.getByRole('button', { name: 'ดูแบบแจ้ง ล.ย.01' })).not.toBeNull();
  });
});
