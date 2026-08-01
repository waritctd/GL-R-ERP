import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

/**
 * Per-row "หลักฐาน (n)" count for the HR register. One query per declaration rather than a single
 * batched call — there is no batched attachment-count endpoint (`TaxAllowanceDeclarationDto` does
 * not carry one), and inventing a new one would be an API-contract change this UI task is not
 * authorised to make. React Query caches each declarationId's result, so re-expanding/re-filtering
 * the table does not refire it.
 */
export function TaxAllowanceEvidenceCount({ declarationId }) {
  const query = useQuery({
    queryKey: queryKeys.taxAllowanceAttachments(declarationId),
    queryFn: () => api.payroll.listTaxAllowanceAttachments(declarationId).then((response) => response.items || []),
    enabled: declarationId != null,
  });
  if (declarationId == null) return <span>-</span>;
  if (query.isLoading) return <span className="text-text-faint">...</span>;
  const count = (query.data ?? []).filter((item) => !item.deletedAt).length;
  return <span>{count}</span>;
}
