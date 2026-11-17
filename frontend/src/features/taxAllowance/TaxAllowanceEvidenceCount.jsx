import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';

/** Per-row "หลักฐาน (n)" count for the HR register. React Query caches each declarationId's result, so
 * re-expanding/re-filtering the table does not refire it. */
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
