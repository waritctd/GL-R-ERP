import { useQuery } from '@tanstack/react-query';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { FormField } from '../../components/common/FormField.jsx';

/** ผู้จัดทำ of a DRAFT deposit notice / remaining invoice: the deal's owner, keyer or a sales manager.
 * A stored preparer who is no longer an option (left the company, deal re-owned) still shows as the current value. */
export function DocumentPreparerSelect({ ticketId, id, value, storedName, onChange }) {
  const optionsQuery = useQuery({
    queryKey: queryKeys.documentPreparerOptions(ticketId),
    queryFn: () => api.depositNotices.preparerOptions(ticketId).then((r) => r.items ?? []),
    enabled: Boolean(ticketId),
  });
  const options = optionsQuery.data ?? [];
  const missing = value != null && !options.some((o) => o.id === value);
  return (
    <FormField label="ผู้จัดทำ" htmlFor={id}>
      <select id={id} value={value == null ? '' : String(value)} disabled={optionsQuery.isLoading}
        onChange={(e) => onChange(e.target.value === '' ? null : Number(e.target.value))}>
        {value == null ? <option value="">-</option> : null}
        {missing ? <option value={String(value)}>{storedName || `#${value}`}</option> : null}
        {options.map((o) => <option key={o.id} value={String(o.id)}>{o.name}</option>)}
      </select>
    </FormField>
  );
}
