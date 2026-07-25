import React from 'react';
import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { DealDocumentList } from './DealDocumentList.jsx';

globalThis.React = React;

describe('DealDocumentList', () => {
  it('renders document rows without decorative nested cards', () => {
    const onDelete = vi.fn();
    const { container } = render(
      <DealDocumentList
        attachments={[
          {
            id: 10,
            fileName: 'invoice-final.pdf',
            attachType: 'INVOICE',
            uploadedAt: '2026-07-01T08:00:00.000Z',
          },
        ]}
        canDelete
        fileUrl={(id) => `#file-${id}`}
        onDelete={onDelete}
      />,
    );

    expect(screen.getByTestId('deal-document-list')).not.toBeNull();
    expect(screen.getByText('invoice-final.pdf')).not.toBeNull();
    expect(screen.getByText(/ใบกำกับภาษี/)).not.toBeNull();
    expect(screen.getByRole('link', { name: 'ดูไฟล์' }).getAttribute('href')).toBe('#file-10');
    expect(container.querySelectorAll('.rounded-lg, .shadow-sm')).toHaveLength(0);

    fireEvent.click(screen.getByRole('button', { name: 'ลบไฟล์ invoice-final.pdf' }));
    expect(onDelete).toHaveBeenCalledWith(10, 'invoice-final.pdf');
  });

  it('uses compact loading and empty states', () => {
    const { rerender } = render(<DealDocumentList loading fileUrl={() => '#'} />);
    expect(screen.getByLabelText('กำลังโหลดไฟล์แนบ')).not.toBeNull();

    rerender(<DealDocumentList attachments={[]} fileUrl={() => '#'} />);
    expect(screen.getByText('ยังไม่มีไฟล์แนบ')).not.toBeNull();
    expect(screen.queryByText(/แนบ PO หรือใบเซ็นได้ด้วยปุ่มด้านบน/)).toBeNull();
  });
});
