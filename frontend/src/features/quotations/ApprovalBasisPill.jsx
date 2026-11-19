import { StatusBadge } from '../../components/common/StatusBadge.jsx';

/** Beside a status: this quotation was approved by the add-on reuse rule (approvalBasis), not by a person. */
export function ApprovalBasisPill({ quotation }) {
  if (quotation?.approvalBasis !== 'REUSED_APPROVED_PRICE') return null;
  return <StatusBadge tone="info">อนุมัติตามราคาเดิม</StatusBadge>;
}
