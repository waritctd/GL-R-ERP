import React from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { TaxAllowanceEvidencePanel } from './TaxAllowanceEvidencePanel.jsx';
import { api } from '../../api/index.js';

globalThis.React = React;

// `browser-image-compression` genuinely returns a plain **Blob**, not a File -- it does not carry `file.name`
// through. A `vi.fn()` returning a File would make the test pass with the fix REMOVED -- the mock must be the
// unhelpful shape the real library actually produces, or it proves nothing.
vi.mock('browser-image-compression', () => ({
  default: vi.fn((file) => Promise.resolve(new Blob([file], { type: file.type }))),
}));

vi.mock('../../api/index.js', async (importOriginal) => {
  const actual = await importOriginal();
  return {
    ...actual,
    api: {
      payroll: {
        listTaxAllowanceAttachments: vi.fn(),
        uploadTaxAllowanceAttachment: vi.fn(),
        deleteTaxAllowanceAttachment: vi.fn(),
        downloadTaxAllowanceAttachment: vi.fn(),
      },
    },
  };
});

function attachment(overrides = {}) {
  return {
    attachmentId: 1,
    fileName: 'evidence.pdf',
    fileSize: 1024,
    deletedAt: null,
    sectionKey: null,
    ...overrides,
  };
}

function renderPanel(props = {}) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <TaxAllowanceEvidencePanel showToast={vi.fn()} {...props} />
    </QueryClientProvider>,
  );
}

describe('TaxAllowanceEvidencePanel — section filtering (V135)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('filters the displayed list to sectionKey and does not show a different section\'s attachment', async () => {
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({
      items: [
        attachment({ attachmentId: 1, fileName: 'insurance-cert.pdf', sectionKey: 'insurance' }),
        attachment({ attachmentId: 2, fileName: 'family-cert.pdf', sectionKey: 'family' }),
      ],
    });

    renderPanel({ mode: 'readonly', declarationId: 55, sectionKey: 'insurance' });

    expect(await screen.findByText('insurance-cert.pdf')).not.toBeNull();
    expect(screen.queryByText('family-cert.pdf')).toBeNull();
  });
});

describe('TaxAllowanceEvidencePanel — mode: direct (an existing, directly-editable declaration)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.payroll.listTaxAllowanceAttachments.mockResolvedValue({ items: [] });
  });

  it('uploads straight to the server, tagged with the current sectionKey', async () => {
    api.payroll.uploadTaxAllowanceAttachment.mockResolvedValue({ attachment: attachment({ sectionKey: 'family' }) });
    renderPanel({ mode: 'direct', declarationId: 55, sectionKey: 'family' });

    const file = new File(['x'], 'cert.pdf', { type: 'application/pdf' });
    const input = document.querySelector('input[type="file"]');
    fireEvent.change(input, { target: { files: [file] } });

    await waitFor(() => expect(api.payroll.uploadTaxAllowanceAttachment).toHaveBeenCalledWith(55, file, 'family'));
  });
});

