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

// Owner ask (2026-09-14): the picker displays designer NAME + CODE in one field, while the
// consuming quotation state receives the CODE only.
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

    // The consuming field is a plain string (unit_code) — asserting the closed field shows the
    // bare CODE (not the name) right after picking, before getByCode's own hint lookup has even
    // resolved (it rejects by default in this file's beforeEach), is the frontend half of "the
    // name must never print": there is nowhere downstream for the name to leak from once this
    // call carries only the code, and the raw-code fallback proves it without waiting on a hint.
    await waitFor(() => expect(screen.getByLabelText('ค้นหาผู้ออกแบบ').value).toBe('A001'));
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

  it('shows the resolved designer name and code together, flagged ยกเลิกแล้ว, for a cancelled unit_code', async () => {
    api.designers.getByCode.mockResolvedValue({ code: 'A060', name: 'ABACUS DESIGN CO.,LTD (เดิม)', active: false });
    render(<Harness initial="A060" />);

    await waitFor(() => expect(screen.getByLabelText('ค้นหาผู้ออกแบบ').value)
      .toBe('ABACUS DESIGN CO.,LTD (เดิม) (A060) · ยกเลิกแล้ว'));
  });

  it('a code the directory does not resolve (unknown, or the lookup failed) shows the raw code rather than going blank', async () => {
    api.designers.getByCode.mockRejectedValue(new Error('not found'));
    render(<Harness initial="Z999" />);

    // getByCode already rejects by default (beforeEach) — this just asserts the closed field
    // never blanks out a code the directory cannot explain.
    await waitFor(() => expect(api.designers.getByCode).toHaveBeenCalledWith('Z999'));
    expect(screen.getByLabelText('ค้นหาผู้ออกแบบ').value).toBe('Z999');
  });

  it('typing the box down to blank and blurring clears a previously-picked code', async () => {
    api.designers.getByCode.mockResolvedValue({ code: 'A001', name: 'ABACUS DESIGN CO.,LTD', active: true });
    render(<Harness initial="A001" />);
    const input = screen.getByLabelText('ค้นหาผู้ออกแบบ');
    await waitFor(() => expect(input.value).toBe('ABACUS DESIGN CO.,LTD (A001)'));

    fireEvent.focus(input);
    fireEvent.change(input, { target: { value: '' } });
    fireEvent.blur(input);

    await waitFor(() => expect(input.value).toBe(''));
  });

  // Wrong-way-round: neither of these leaves `search` non-blank on its own, so a clear-on-blank
  // guard with no "did the rep actually type" check would wipe the code in both cases too.
  it('does NOT clear after an Enter selection followed by blur', async () => {
    api.designers.search.mockResolvedValue({ items: [{ code: 'A001', name: 'ABACUS DESIGN CO.,LTD', active: true }] });
    api.designers.getByCode.mockResolvedValue({ code: 'A001', name: 'ABACUS DESIGN CO.,LTD', active: true });
    render(<Harness />);
    const input = screen.getByLabelText('ค้นหาผู้ออกแบบ');
    fireEvent.focus(input);
    await screen.findByRole('option', { name: /ABACUS DESIGN/ });
    fireEvent.keyDown(input, { key: 'ArrowDown' });
    fireEvent.keyDown(input, { key: 'Enter' });
    await waitFor(() => expect(input.value).toBe('ABACUS DESIGN CO.,LTD (A001)'));

    fireEvent.blur(input);

    await waitFor(() => expect(input.value).toBe('ABACUS DESIGN CO.,LTD (A001)'));
  });

  it('does NOT clear after a plain focus followed by blur, with no typing', async () => {
    api.designers.getByCode.mockResolvedValue({ code: 'A001', name: 'ABACUS DESIGN CO.,LTD', active: true });
    api.designers.search.mockResolvedValue({ items: [] });
    render(<Harness initial="A001" />);
    const input = screen.getByLabelText('ค้นหาผู้ออกแบบ');
    await waitFor(() => expect(input.value).toBe('ABACUS DESIGN CO.,LTD (A001)'));

    fireEvent.focus(input);
    fireEvent.blur(input);

    await waitFor(() => expect(input.value).toBe('ABACUS DESIGN CO.,LTD (A001)'));
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
    // The closed field shows the picked CODE right away (raw-code fallback, getByCode rejects by
    // default), not the name and not blank.
    await waitFor(() => expect(input.value).toBe('D002'));
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
