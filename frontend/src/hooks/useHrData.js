import { useMemo } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '../api/index.js';
import { queryKeys } from '../api/queryKeys.js';
import { hasPermission } from '../app/permissions.js';
import { declaredAllowanceTotal } from '../features/taxAllowance/taxAllowanceSchema.js';
import { selectCurrentDeclaration, taxAllowanceStatusInfo } from '../features/taxAllowance/taxAllowanceStatus.js';

export function useHrData({ user, showToast }) {
  const queryClient = useQueryClient();

  const canViewEmployees = !!user && hasPermission(user.role, 'canViewEmployees');

  // --- Reads (all enabled-gated so nothing fires until `user` is set) ---
  const currentEmployeeQuery = useQuery({
    queryKey: queryKeys.currentEmployee(user?.employeeId),
    queryFn: () => api.employees.get(user.employeeId).then((response) => response.employee),
    enabled: !!user?.employeeId,
  });
  const currentEmployee = currentEmployeeQuery.data ?? null;

  const employeesQuery = useQuery({
    queryKey: queryKeys.employees(),
    queryFn: () => api.employees.list().then((response) => response.employees),
    enabled: canViewEmployees,
  });

  // Users without canViewEmployees never run the list query; their "employees"
  // is derived from their own record, preserving the pre-Query behavior.
  const derivedEmployees = useMemo(
    () => (currentEmployee ? [currentEmployee] : []),
    [currentEmployee],
  );
  const employees = canViewEmployees ? (employeesQuery.data ?? []) : derivedEmployees;

  const profileRequestsQuery = useQuery({
    queryKey: queryKeys.profileRequests(),
    queryFn: () => api.profileRequests.list()
      .then((response) => response.profileRequests)
      .catch(() => []),
    enabled: !!user,
  });
  const profileRequests = profileRequestsQuery.data ?? [];

  const dashboardSummaryQuery = useQuery({
    queryKey: queryKeys.dashboardSummary(),
    queryFn: () => api.dashboard.summary()
      .then((response) => response?.summary ?? response ?? null)
      .catch(() => null),
    enabled: !!user && !!api.dashboard?.summary,
  });
  const dashboardSummary = dashboardSummaryQuery.data ?? null;

  // Tax-allowance profile summary (issue #387 screen 4) — fetched once here and passed down as a
  // prop, matching `profileRequests`'s own pattern, rather than ProfilePage fetching it itself.
  const currentTaxYear = new Date().getFullYear();
  const taxAllowanceDeclarationsQuery = useQuery({
    queryKey: queryKeys.taxAllowanceDeclarationsMe(currentTaxYear),
    queryFn: () => api.payroll.getMyTaxAllowanceDeclarations(currentTaxYear).then((response) => response.items || []),
    enabled: !!user?.employeeId,
  });
  const currentTaxAllowanceDeclaration = useMemo(
    () => selectCurrentDeclaration(taxAllowanceDeclarationsQuery.data ?? []),
    [taxAllowanceDeclarationsQuery.data],
  );
  // Same queryKey/queryFn shape (an array of attachment rows) as TaxAllowanceEvidencePanel.jsx's
  // own attachments query for this declaration — sharing one cache entry, not colliding under the
  // same key with a differently-shaped one. This query used to return a bare count instead of the
  // array, which crashed TaxAllowanceEvidencePanel's `(attachmentsQuery.data ?? []).filter(...)`
  // with "filter is not a function" the moment both queries were active for the same declaration
  // (e.g. viewing /tax-allowance right after this hook had already populated the profile summary).
  const taxAllowanceEvidenceQuery = useQuery({
    queryKey: queryKeys.taxAllowanceAttachments(currentTaxAllowanceDeclaration?.declarationId ?? null),
    queryFn: () => api.payroll
      .listTaxAllowanceAttachments(currentTaxAllowanceDeclaration.declarationId)
      .then((response) => response.items || []),
    enabled: !!currentTaxAllowanceDeclaration?.declarationId,
  });
  const taxAllowanceSummary = useMemo(() => ({
    statusInfo: taxAllowanceStatusInfo(currentTaxAllowanceDeclaration),
    declaredTotal: declaredAllowanceTotal(currentTaxAllowanceDeclaration),
    evidenceCount: (taxAllowanceEvidenceQuery.data ?? []).filter((item) => !item.deletedAt).length,
    expiresOn: currentTaxAllowanceDeclaration?.expiresOn ?? null,
    // The row itself, so a landing page can sort a ล.ย.01 entry into its "คำขอของฉัน" feed by
    // `submittedAt` alongside leave/OT/profile requests. Null when nothing has been filed.
    declaration: currentTaxAllowanceDeclaration,
  }), [currentTaxAllowanceDeclaration, taxAllowanceEvidenceQuery.data]);

  // --- Mutations (exposed as same-signature async wrappers) ---
  const createEmployeeMutation = useMutation({
    mutationFn: (payload) => api.employees.create(payload).then((response) => response.employee),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.employees() });
      showToast('success', 'เพิ่มพนักงานเรียบร้อย');
    },
  });

  const updateEmployeeMutation = useMutation({
    mutationFn: ({ id, payload }) => api.employees.update(id, payload).then((response) => response.employee),
    onSuccess: (employee, { id }) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.employees() });
      queryClient.invalidateQueries({ queryKey: queryKeys.currentEmployee(id) });
      queryClient.invalidateQueries({ queryKey: queryKeys.employeeDetail(id) });
      showToast('success', 'บันทึกข้อมูลพนักงานแล้ว');
    },
  });

  const createProfileRequestMutation = useMutation({
    mutationFn: (payload) => api.profileRequests.create(payload).then((response) => response.profileRequest),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.profileRequests() });
      showToast('success', 'ส่งคำขอแก้ไขเรียบร้อย');
    },
  });

  const reviewProfileRequestMutation = useMutation({
    mutationFn: ({ id, status, reviewerNote }) => api.profileRequests
      .update(id, reviewerNote ? { status, reviewerNote } : { status })
      .then((response) => response.profileRequest),
    onSuccess: (profileRequest, { status }) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.profileRequests() });
      showToast(status === 'approved' ? 'success' : 'info', status === 'approved' ? 'อนุมัติคำขอแล้ว' : 'ปฏิเสธคำขอแล้ว');
    },
  });

  function resetData() {
    // Drop the WHOLE cache on logout, not an enumerated list of HR keys.
    //
    // Query keys are scoped by entity id, never by user, so any cached response
    // computed for the previous session is served verbatim to the next one. The
    // enumerated version of this function missed every sales/ticket key, which
    // meant a CEO logging in after an accountant was shown the ACCOUNTANT's
    // availableActions for the same deal — role-dependent data leaking across a
    // logout, and the wrong workflow buttons rendered.
    //
    // Keep this a blanket clear: an allowlist has to be updated every time a
    // query is added, and forgetting is silent.
    queryClient.clear();
  }

  return {
    currentEmployee,
    employees,
    profileRequests,
    dashboardSummary,
    taxAllowanceSummary,
    resetData,
    createEmployee: (payload) => createEmployeeMutation.mutateAsync(payload),
    updateEmployee: (id, payload) => updateEmployeeMutation.mutateAsync({ id, payload }),
    createProfileRequest: (payload) => createProfileRequestMutation.mutateAsync(payload),
    reviewProfileRequest: (id, status, reviewerNote) => reviewProfileRequestMutation.mutateAsync({ id, status, reviewerNote }),
  };
}
