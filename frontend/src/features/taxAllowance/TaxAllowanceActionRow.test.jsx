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
  it('prompts a first submission when nothing has been filed', () => {
    renderRow(null);
    expect(screen.getByText('ยังไม่ได้ยื่นแบบแจ้ง ล.ย.01')).not.toBeNull();
  });

  it('renders nothing once approved and applied', () => {
    const { container } = renderRow({
      status: 'APPROVED',
      appliedAt: '2026-04-01T00:00:00Z',
      appliedEffectiveMonth: 4,
    });
    expect(container.firstChild).toBeNull();
  });
});
