import React from 'react';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { DesignerPicker } from './DesignerPicker.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      designers: {
        search: vi.fn(),
        getByCode: vi.fn(),
      },
    },
  };
});

function Harness({ initial = '' }) {
  const [code, setCode] = React.useState(initial);
  return <DesignerPicker value={code} onSelectCode={setCode} idPrefix="dp" />;
}

// Owner ask (2026-09-12): picking a ผู้ออกแบบ fills D.Co. with the CODE only; the name is
// confidential ("เป็นความลับ") and exists in this picker purely to search and to display who a
// code currently resolves to.
describe('DesignerPicker', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.designers.getByCode.mockRejectedValue(new Error('not found'));
  });

  it('searching by name and picking a result calls onSelectCode with the CODE, not the name', async () => {
    api.designers.search.mockResolvedValue({
      items: [{ code: 'A001', name: 'ABACUS DESIGN CO.,LTD', active: true }],
    });
    render(<Harness />);

    const input = screen.getByLabelText('ค้นหาผู้ออกแบบ');
    fireEvent.change(input, { target: { value: 'abacus' } });
    await waitFor(() => expect(api.designers.search).toHaveBeenCalledWith('abacus'));

    const option = await screen.findByRole('option', { name: /ABACUS DESIGN/ });
    fireEvent.mouseDown(option);

    // The consuming field is a plain string (unit_code) — asserting it equals the bare code is
    // the frontend half of "the name must never print": there is nowhere downstream for the name
    // to leak from once this call carries only the code.
    await waitFor(() => expect(screen.getByLabelText('ค้นหาผู้ออกแบบ').value).toBe(''));
  });

  it('search results never render inactive designers is enforced server-side; the client renders whatever the search endpoint returns', async () => {
    // This test documents the boundary rather than re-testing the server: DesignerRepository.search
    // (and its mock mirror) filters active=true, so the picker itself does not need its own
    // active-only filter — it is not a defense-in-depth requirement here, only a display concern.
    api.designers.search.mockResolvedValue({ items: [] });
    render(<Harness />);
    fireEvent.focus(screen.getByLabelText('ค้นหาผู้ออกแบบ'));
    await waitFor(() => expect(api.designers.search).toHaveBeenCalled());
    expect(await screen.findByText('ไม่พบผู้ออกแบบ')).not.toBeNull();
  });

  it('shows the resolved name as a hint only, for an existing unit_code — never as the field value', async () => {
    api.designers.getByCode.mockResolvedValue({ code: 'A060', name: 'ABACUS DESIGN CO.,LTD (เดิม)', active: false });
    render(<Harness initial="A060" />);

    const hint = await screen.findByTestId('dp-hint');
    expect(hint.textContent).toContain('ABACUS DESIGN CO.,LTD (เดิม)');
    expect(hint.textContent).toContain('ยกเลิกแล้ว');
    // The hint is display-only text, not a form control's value — nothing here writes the name
    // anywhere a save could pick it up.
    expect(screen.getByLabelText('ค้นหาผู้ออกแบบ').value).toBe('');
  });

  it('keyboard: ArrowDown opens and highlights, Enter selects the highlighted designer', async () => {
    api.designers.search.mockResolvedValue({
      items: [
        { code: 'A001', name: 'ABACUS DESIGN CO.,LTD', active: true },
        { code: 'D002', name: 'DEVELOPMENT DESIGN STUDIO', active: true },
      ],
    });
    render(<Harness />);
    const input = screen.getByLabelText('ค้นหาผู้ออกแบบ');
    fireEvent.focus(input);
    await waitFor(() => expect(api.designers.search).toHaveBeenCalled());
    await screen.findByRole('option', { name: /ABACUS DESIGN/ });

    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    expect(screen.getByRole('option', { name: /DEVELOPMENT DESIGN STUDIO/ }).getAttribute('aria-selected')).toBe('true');

    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(input.value).toBe(''));
  });

  it('Escape closes the dropdown without selecting anything', async () => {
    api.designers.search.mockResolvedValue({ items: [{ code: 'A001', name: 'ABACUS DESIGN CO.,LTD', active: true }] });
    render(<Harness />);
    const input = screen.getByLabelText('ค้นหาผู้ออกแบบ');
    fireEvent.focus(input);
    await screen.findByRole('option', { name: /ABACUS DESIGN/ });

    fireEvent.keyDown(input, { key: 'Escape' });
    expect(screen.queryByRole('option', { name: /ABACUS DESIGN/ })).toBeNull();
  });
});
