import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { SignatureCard } from './SignatureCard.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      employees: {
        hasSignature: vi.fn(),
        getSignature: vi.fn((id) => `/api/employees/${id}/signature`),
        uploadSignature: vi.fn(),
        deleteSignature: vi.fn(),
      },
    },
  };
});

const user = { employeeId: 42, role: 'ceo' };

function renderCard() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <SignatureCard user={user} />
    </QueryClientProvider>,
  );
}

describe('SignatureCard -- #M1 probe-before-render and cache-busting', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('probes hasSignature and renders NO <img> while none exists (probe says false)', async () => {
    api.employees.hasSignature.mockResolvedValue(false);
    renderCard();

    await waitFor(() => expect(api.employees.hasSignature).toHaveBeenCalledWith(42));
    expect(await screen.findByText('ยังไม่มีลายเซ็น')).not.toBeNull();
    expect(document.querySelector('img')).toBeNull();
    // No "ลบลายเซ็น" button either -- nothing to delete.
    expect(screen.queryByRole('button', { name: 'ลบลายเซ็น' })).toBeNull();
  });

  it('renders <img> only once the probe confirms a signature exists (200, not just getSignature being truthy)', async () => {
    api.employees.hasSignature.mockResolvedValue(true);
    renderCard();

    const img = await screen.findByRole('img', { name: 'ลายเซ็นที่บันทึกไว้' });
    expect(img).not.toBeNull();
    expect(img.getAttribute('src')).toMatch(/^\/api\/employees\/42\/signature\?t=0$/);
  });

  it('shows neither the broken-image state nor "ยังไม่มีลายเซ็น" while the probe is still in flight', () => {
    api.employees.hasSignature.mockReturnValue(new Promise(() => {})); // never resolves
    renderCard();

    expect(document.querySelector('img')).toBeNull();
    expect(screen.getByText('กำลังตรวจสอบ...')).not.toBeNull();
    expect(screen.queryByText('ยังไม่มีลายเซ็น')).toBeNull();
  });

  it('bumps the cache-busting query param after a successful upload, forcing a fresh <img> src', async () => {
    api.employees.hasSignature.mockResolvedValue(false);
    api.employees.uploadSignature.mockResolvedValue(null); // #H1: the real PUT resolves null (204)
    renderCard();

    await screen.findByText('ยังไม่มีลายเซ็น');
    api.employees.hasSignature.mockResolvedValue(true); // re-probe after upload finds it now exists

    const file = new File(['x'], 'sig.png', { type: 'image/png' });
    const fileInput = document.querySelector('input[type="file"]');
    fireEvent.change(fileInput, { target: { files: [file] } });

    await waitFor(() => expect(api.employees.uploadSignature).toHaveBeenCalledWith(42, file));
    const img = await screen.findByRole('img', { name: 'ลายเซ็นที่บันทึกไว้' });
    expect(img.getAttribute('src')).toMatch(/\?t=1$/); // bumped from the initial cacheBust=0
  });

  it('bumps the cache-busting query param after a successful delete too', async () => {
    api.employees.hasSignature.mockResolvedValue(true);
    api.employees.deleteSignature.mockResolvedValue(null);
    renderCard();

    const firstImg = await screen.findByRole('img', { name: 'ลายเซ็นที่บันทึกไว้' });
    expect(firstImg.getAttribute('src')).toMatch(/\?t=0$/);

    api.employees.hasSignature.mockResolvedValue(false);
    fireEvent.click(screen.getByRole('button', { name: 'ลบลายเซ็น' }));

    await waitFor(() => expect(api.employees.deleteSignature).toHaveBeenCalledWith(42));
    await screen.findByText('ยังไม่มีลายเซ็น');
  });
});
