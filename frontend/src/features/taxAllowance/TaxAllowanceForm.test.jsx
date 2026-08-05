import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TaxAllowanceForm } from './TaxAllowanceForm.jsx';
import { defaultAllowanceValues, TAX_ALLOWANCE_GROUPS } from './taxAllowanceSchema.js';

globalThis.React = React;

// Progressive disclosure (#tax-allowance-sections): TaxAllowanceForm defaults to `sectioned=true`,
// a 2-step "choose a section, then fill only that section" flow modelled on
// LeaveRequestPage.jsx's step wizard. `evidenceMode` is left undefined in every case here on
// purpose -- these tests are about the STEP STRUCTURE and value persistence, not the evidence
// panel (covered separately in TaxAllowanceEvidencePanel.test.jsx and TaxAllowancePage.test.jsx);
// leaving it undefined means the embedded evidence panel never mounts, so no react-query provider
// or API mocking is needed here.

function renderForm(props = {}) {
  const onSubmit = vi.fn();
  const utils = render(
    <TaxAllowanceForm
      caps={[]}
      defaultValues={defaultAllowanceValues(null)}
      onSubmit={onSubmit}
      {...props}
    />,
  );
  return { onSubmit, ...utils };
}

describe('TaxAllowanceForm — progressive disclosure (sectioned, default)', () => {
  it('step 1 lists all five sections as choices, with none of their fields mounted yet', () => {
    renderForm();

    for (const group of TAX_ALLOWANCE_GROUPS) {
      expect(screen.getByText(group.title)).not.toBeNull();
    }
    // Field-level controls from every section stay unmounted until a section is opened -- "only one
    // step's markup is ever mounted" (the task's own correctness constraint), scoped further here to
    // "only one SECTION's markup is ever mounted" once inside step 2.
    expect(screen.queryByLabelText('คู่สมรส (ไม่มีเงินได้)')).toBeNull();
    expect(screen.queryByLabelText('ประกันชีวิต')).toBeNull();
  });

  it('the declaration-level fields (effective month, document reference) stay on step 1, not inside any section', () => {
    renderForm();
    expect(screen.getByLabelText('มีผลตั้งแต่งวดเดือน')).not.toBeNull();
    expect(screen.getByLabelText(/เลขที่เอกสารอ้างอิง/)).not.toBeNull();
  });

  it('selecting a section shows ONLY that section\'s fields', () => {
    renderForm();
    fireEvent.click(screen.getByText('ครอบครัว'));

    expect(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)')).not.toBeNull();
    // A different section's own field must not be mounted just because we're in step 2.
    expect(screen.queryByLabelText('ประกันชีวิต')).toBeNull();
  });

  it('values survive step navigation: fill section A, move to B, fill it, go back to A — A\'s value is still there', () => {
    renderForm();

    fireEvent.click(screen.getByText('ครอบครัว'));
    const spouseInput = screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)');
    fireEvent.change(spouseInput, { target: { value: '60000' } });
    expect(spouseInput.value).toBe('60000');

    fireEvent.click(screen.getByText('กลับไปเลือกหมวด'));
    fireEvent.click(screen.getByText('ประกัน'));
    const lifeInsuranceInput = screen.getByLabelText('ประกันชีวิต');
    fireEvent.change(lifeInsuranceInput, { target: { value: '10000' } });
    expect(lifeInsuranceInput.value).toBe('10000');

    fireEvent.click(screen.getByText('กลับไปเลือกหมวด'));
    fireEvent.click(screen.getByText('ครอบครัว'));

    // This is the one that would fail if the form (or its `react-hook-form` instance) were
    // remounted/reset between steps instead of staying mounted underneath the conditional JSX.
    expect(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)').value).toBe('60000');
  });

  it('step 1 shows a "กรอกแล้ว" indicator once a section has a value, so a returning user can see what they already started', () => {
    renderForm();

    fireEvent.click(screen.getByText('ครอบครัว'));
    fireEvent.change(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)'), { target: { value: '60000' } });
    fireEvent.click(screen.getByText('กลับไปเลือกหมวด'));

    expect(screen.getAllByText('กรอกแล้ว').length).toBeGreaterThan(0);
    // Untouched sections still read as not-yet-filled.
    expect(screen.getAllByText('ยังไม่ได้กรอก').length).toBeGreaterThan(0);
  });

  it('submit is reachable from step 2 and sends the FULL combined declaration, not just the section currently open', async () => {
    const { onSubmit } = renderForm();

    fireEvent.click(screen.getByText('ครอบครัว'));
    fireEvent.change(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)'), { target: { value: '60000' } });
    fireEvent.click(screen.getByText('กลับไปเลือกหมวด'));
    fireEvent.click(screen.getByText('ประกัน'));
    fireEvent.change(screen.getByLabelText('ประกันชีวิต'), { target: { value: '10000' } });

    fireEvent.click(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const values = onSubmit.mock.calls[0][0];
    expect(Number(values.spouseAllowance)).toBe(60000);
    expect(Number(values.lifeInsuranceAllowance)).toBe(10000);
  });

  it('readOnly disables every field but the step flow still navigates', () => {
    renderForm({ readOnly: true });

    fireEvent.click(screen.getByText('ครอบครัว'));
    expect(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)').disabled).toBe(true);
    // No submit control in read-only mode (matches the pre-existing `footer ?? (readOnly ? null : …)` rule).
    expect(screen.queryByRole('button', { name: 'ยื่นแบบแจ้ง' })).toBeNull();
  });
});

describe('TaxAllowanceForm — sectioned=false (TaxAllowanceReviewPage\'s on-behalf modal)', () => {
  it('renders every group expanded at once, unchanged from before this task', () => {
    renderForm({ sectioned: false });

    // Every section's own field is mounted simultaneously -- the ORIGINAL layout, deliberately kept
    // for this caller (see TaxAllowanceReviewPage.jsx's own comment on why).
    expect(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)')).not.toBeNull();
    expect(screen.getByLabelText('ประกันชีวิต')).not.toBeNull();
    // No step chrome at all.
    expect(screen.queryByText(/ขั้นตอนที่/)).toBeNull();
  });
});
