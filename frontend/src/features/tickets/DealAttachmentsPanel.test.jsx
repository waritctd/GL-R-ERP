import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealAttachmentsPanel } from './DealAttachmentsPanel.jsx';

globalThis.React = React;

vi.mock('../../api/index.js', () => ({ api: { attachments: { fileUrl: (id) => `/files/${id}` } } }));

// QA-29 leftover: the ประวัติ tab's attachment row printed the raw attachType enum.
describe('DealAttachmentsPanel attachment type', () => {
  it.each(['INVOICE', 'PO', 'OTHER'])('shows %s as a Thai label, never the raw enum', (attachType) => {
    render(
      <DealAttachmentsPanel
        attachments={[{ id: 1, fileName: 'file.pdf', attachType, uploadedBy: 1 }]}
        attachLoading={false}
        user={{ id: 1 }}
      />,
    );
    const row = screen.getByText('file.pdf').parentElement;
    if (attachType !== 'PO') expect(row.textContent).not.toContain(attachType); // PO may stay inside a Thai label
    expect(row.textContent.replace('file.pdf', '').replace('ดูไฟล์', '')).toMatch(/[฀-๿]/);
  });
});
