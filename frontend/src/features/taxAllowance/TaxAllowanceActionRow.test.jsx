import React from 'react';
import { MemoryRouter } from 'react-router-dom';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { TaxAllowanceActionRow } from './TaxAllowanceActionRow.jsx';
import { taxAllowanceStatusInfo } from './taxAllowanceStatus.js';

globalThis.React = React;

function renderRow(declaration) {
  return render(
    <MemoryRouter>
      <TaxAllowanceActionRow summary={{ statusInfo: taxAllowanceStatusInfo(declaration) }} />
    </MemoryRouter>,
  );
}

describe('TaxAllowanceActionRow', () => {
  // Absent summary means "status unknown, still loading" — not "nothing filed". Prompting here
  // would flash "you haven't filed" at an employee who has, on every landing-page load.
  it('renders nothing while the status is still unknown', () => {
    const { container } = render(
      <MemoryRouter>
        <TaxAllowanceActionRow summary={undefined} />
      </MemoryRouter>,
    );
    expect(container.firstChild).toBeNull();
  });

  it('prompts a first submission when nothing has been filed', () => {
    renderRow(null);
    expect(screen.getByText('ยังไม่ได้ยื่นแบบแจ้ง ล.ย.01')).not.toBeNull();
  });

  it('shows HR’s rejection reason in place of the generic line', () => {
    renderRow({ status: 'REJECTED', reviewerNote: 'ขาดใบเสร็จเบี้ยประกัน' });
    expect(screen.getByText('ขาดใบเสร็จเบี้ยประกัน')).not.toBeNull();
    expect(screen.getByText('ยื่นฉบับใหม่')).not.toBeNull();
  });

  it('prompts re-verification once expired', () => {
    renderRow({ status: 'EXPIRED' });
    expect(screen.getByText('แบบแจ้ง ล.ย.01 หมดอายุแล้ว')).not.toBeNull();
  });

  // The whole point of the row: it is an action, not a permanent tile. Nothing to do, nothing
  // rendered — otherwise it would occupy landing-page space on every visit to say "all good".
  it('renders nothing while pending review', () => {
    const { container } = renderRow({ status: 'PENDING' });
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing once approved and applied', () => {
    const { container } = renderRow({
      status: 'APPROVED',
      appliedAt: '2026-04-01T00:00:00Z',
      appliedEffectiveMonth: 4,
    });
    expect(container.firstChild).toBeNull();
  });

  it('renders nothing when approved but not yet applied — that is HR’s step, not the employee’s', () => {
    const { container } = renderRow({ status: 'APPROVED', appliedAt: null });
    expect(container.firstChild).toBeNull();
  });
});
