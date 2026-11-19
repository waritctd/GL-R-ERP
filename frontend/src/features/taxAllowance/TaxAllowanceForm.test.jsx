import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TaxAllowanceForm } from './TaxAllowanceForm.jsx';
import {
  emptyAllowanceValues,
} from './taxAllowanceSchema.js';

/** Guards the ล.ย.01 form after it stopped being a five-category wizard and became the government form's own
 * ข้อ 1–15, one collapsible each. What matters now is different: that the page really is the document, that
 * collapsing does not eat data, and that an unsigned declaration cannot be filed. */

function renderForm(props = {}) {
  return render(
    <TaxAllowanceForm
      defaultValues={emptyAllowanceValues()}
      onSubmit={vi.fn()}
      {...props}
    />,
  );
}

describe('TaxAllowanceForm — the signed form gates submission', () => {
  it('the submit button is disabled until the signed scan is attached', () => {
    const { rerender } = renderForm({ signedFormAttached: false });
    expect(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }).disabled).toBe(true);

    rerender(
      <TaxAllowanceForm defaultValues={emptyAllowanceValues()} onSubmit={vi.fn()} signedFormAttached />,
    );
    expect(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }).disabled).toBe(false);
  });
});

describe('TaxAllowanceForm — read-only', () => {
  it('offers neither a submit control nor the sign-and-file panel', () => {
    renderForm({ readOnly: true, signedFormAttached: true });
    expect(screen.queryByRole('button', { name: 'ยื่นแบบแจ้ง' })).toBeNull();
    expect(screen.queryByRole('button', { name: /ตรวจทาน ลงนาม/ })).toBeNull();
  });
});

