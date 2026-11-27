import { useMutation } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { Button } from '../../components/common/Button.jsx';

/** The order form (factory copy, no customer or project) of a no-project order, for import to send to the factory. It uses the
 * stored IR download; no email draft exists for these orders. */
export function DownloadIrButton({ importRequestId, docNumber, factoryName, showToast, hidden = false }) {
  const download = useMutation({
    mutationFn: () => api.storedImportRequests.download(importRequestId, 'factory'),
    onSuccess: (blob) => {
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url; a.download = `IR-${docNumber}-${factoryName}.pdf`; document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
    },
    onError: (e) => showToast?.('error', e?.message ?? 'ดาวน์โหลดไม่สำเร็จ'),
  });
  if (hidden) return null;
  return (
    <Button type="button" size="sm" variant="secondary" disabled={download.isPending} onClick={() => download.mutate()}
      data-testid={`ir-pdf-${importRequestId}`}>
      ใบขอซื้อ PDF
    </Button>
  );
}
