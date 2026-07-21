import { useEffect, useMemo, useState } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { EmptyState } from '../../components/common/EmptyState.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { formGridSpan2, Panel, RowActions } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { specialMoneyStatusLabel as statusInfo } from '../../utils/format.js';
import { THAI_PROVINCES, isExcludedProvince } from './thaiProvinces.js';
import {
  AID_TYPES,
  MEDICAL_ANNUAL_CAP,
  UNIFORM_TYPES,
  estimateAmount,
  formatThaiMonthYear,
  payrollCutoffInfo,
  taxChip,
  typeCategory,
} from './specialMoneyRules.js';

const TABLE_GRID = 'grid-cols-[minmax(0,1.3fr)_minmax(0,1fr)_minmax(0,0.9fr)_minmax(0,0.7fr)] max-[1040px]:min-w-[820px] reflow-cards';
const FORM_GRID_CLASS = 'grid gap-[14px] max-[720px]:grid-cols-1 grid-cols-2';

function bangkokDateParts(date = new Date()) {
  return Object.fromEntries(new Intl.DateTimeFormat('en-US', {
    timeZone: 'Asia/Bangkok',
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
  }).formatToParts(date).map((part) => [part.type, part.value]));
}

const dateIso = (date = new Date()) => {
  const parts = bangkokDateParts(date);
  return `${parts.year}-${parts.month}-${parts.day}`;
};

const todayIso = () => dateIso();

function currentBuddhistYear() {
  return new Date().getFullYear() + 543;
}

function defaultForm(employeeId = '') {
  return {
    employeeId: employeeId ? String(employeeId) : '',
    requestType: '',
    eventDate: todayIso(),
    eventEndDate: todayIso(),
    receiptDate: todayIso(),
    province: '',
    role: '',
    relation: '',
    shirtCount: '0',
    trouserCount: '0',
    requestedAmountInput: '',
    reason: '',
  };
}

function specialMoneyFormSchema({ requireEmployeeId }) {
  return z.object({
    employeeId: z.string(),
    requestType: z.string().min(1, 'กรุณาเลือกประเภทคำขอ'),
    eventDate: z.string(),
    eventEndDate: z.string(),
    receiptDate: z.string(),
    province: z.string(),
    role: z.string(),
    relation: z.string(),
    shirtCount: z.string(),
    trouserCount: z.string(),
    requestedAmountInput: z.string(),
    reason: z.string().min(1, 'กรุณาระบุเหตุผล/รายละเอียด'),
  }).superRefine((data, context) => {
    if (requireEmployeeId && !data.employeeId) {
      context.addIssue({ code: z.ZodIssueCode.custom, path: ['employeeId'], message: 'กรุณาเลือกพนักงาน' });
    }
    if (!data.requestType) return;
    if (['TRAVEL_PER_DIEM', 'TRAVEL_LODGING'].includes(data.requestType)) {
      if (!data.eventDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventDate'], message: 'กรุณาเลือกวันที่เริ่มเดินทาง' });
      if (!data.eventEndDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventEndDate'], message: 'กรุณาเลือกวันที่สิ้นสุด' });
      if (data.eventDate && data.eventEndDate && data.eventEndDate < data.eventDate) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventEndDate'], message: 'วันที่สิ้นสุดต้องอยู่หลังวันที่เริ่ม' });
      }
      if (!data.province) context.addIssue({ code: z.ZodIssueCode.custom, path: ['province'], message: 'กรุณาเลือกจังหวัดปลายทาง' });
      if (data.requestType === 'TRAVEL_PER_DIEM' && !data.role) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['role'], message: 'กรุณาเลือกบทบาท' });
      }
      if (data.requestType === 'TRAVEL_LODGING' && !(Number(data.requestedAmountInput) > 0)) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['requestedAmountInput'], message: 'กรุณาระบุจำนวนเงิน' });
      }
    } else if (data.requestType === 'MEDICAL') {
      if (!data.receiptDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['receiptDate'], message: 'กรุณาเลือกวันที่ใบเสร็จ' });
      if (!(Number(data.requestedAmountInput) > 0)) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['requestedAmountInput'], message: 'กรุณาระบุจำนวนเงิน' });
      }
    } else if (AID_TYPES.includes(data.requestType)) {
      if (!data.eventDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventDate'], message: 'กรุณาเลือกวันที่เกิดเหตุการณ์' });
      if (data.requestType === 'AID_FUNERAL' && !data.relation) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['relation'], message: 'กรุณาเลือกความสัมพันธ์กับผู้เสียชีวิต' });
      }
    } else if (UNIFORM_TYPES.includes(data.requestType)) {
      if (!data.eventDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventDate'], message: 'กรุณาเลือกวันที่เบิก' });
      if (data.requestType === 'UNIFORM_ANNUAL' && !data.receiptDate) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['receiptDate'], message: 'กรุณาเลือกวันที่ใบเสร็จ' });
      }
      const pieces = Number(data.shirtCount || 0) + Number(data.trouserCount || 0);
      if (pieces <= 0) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['shirtCount'], message: 'กรุณาระบุจำนวนชิ้นอย่างน้อย 1 ชิ้น' });
      }
      if (data.requestType !== 'UNIFORM_ANNUAL' && !(Number(data.requestedAmountInput) > 0)) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['requestedAmountInput'], message: 'กรุณาระบุจำนวนเงิน' });
      }
    } else {
      // TRAINING / OTHER
      if (!data.eventDate) context.addIssue({ code: z.ZodIssueCode.custom, path: ['eventDate'], message: 'กรุณาเลือกวันที่' });
      if (!(Number(data.requestedAmountInput) > 0)) {
        context.addIssue({ code: z.ZodIssueCode.custom, path: ['requestedAmountInput'], message: 'กรุณาระบุจำนวนเงิน' });
      }
    }
  });
}

function formatDate(value) {
  if (!value) return '-';
  const date = new Date(`${value}T00:00:00+07:00`);
  if (Number.isNaN(date.getTime())) return '-';
  return new Intl.DateTimeFormat('th-TH', { dateStyle: 'medium', timeZone: 'Asia/Bangkok' }).format(date);
}

function formatMoney(value) {
  return `฿${Number(value || 0).toLocaleString('th-TH')}`;
}

function evidenceLabel(requestType) {
  const map = {
    MEDICAL: 'ใบเสร็จรับเงินค่ารักษาพยาบาล',
    TRAVEL_LODGING: 'ใบเสร็จค่าที่พัก',
    AID_WEDDING: 'บัตรเชิญ / ทะเบียนสมรส',
    AID_ORDINATION: 'ภาพถ่ายงานบวช',
    AID_CHILDBIRTH: 'สูติบัตรบุตร',
    AID_FUNERAL: 'ใบมรณบัตร / ภาพถ่ายงานศพ',
    UNIFORM_ANNUAL: 'ใบเสร็จรับเงินชุดฟอร์ม',
    UNIFORM_NEW_STAFF: 'ใบเสร็จรับเงินชุดฟอร์ม',
    UNIFORM_PREPROBATION_KIT: 'ใบเสร็จรับเงินชุดฟอร์ม',
    TRAINING: 'ใบสุทธิ / ใบเสร็จค่าอบรม',
    OTHER: 'เอกสารประกอบการเบิก',
  };
  return map[requestType] || 'เอกสารหลักฐาน';
}

export function SpecialMoneyPanel({ user, currentEmployee, showToast }) {
  const queryClient = useQueryClient();
  const [confirmState, setConfirmState] = useState(null);
  const [statusFilter, setStatusFilter] = useState('');

  const employeesQuery = useQuery({
    queryKey: queryKeys.specialMoneyEmployees(),
    queryFn: () => api.specialMoney.employees().then((response) => response.employees || []),
  });
  const employeeOptions = useMemo(() => employeesQuery.data ?? [], [employeesQuery.data]);

  const typesQuery = useQuery({
    queryKey: queryKeys.specialMoneyTypes(),
    queryFn: () => api.specialMoney.types().then((response) => response.types || []),
  });
  const typeOptions = useMemo(() => typesQuery.data ?? [], [typesQuery.data]);

  const filters = useMemo(() => ({ status: statusFilter }), [statusFilter]);
  const requestsQuery = useQuery({
    queryKey: queryKeys.specialMoneyRequests(filters),
    queryFn: () => api.specialMoney.list({ status: statusFilter || undefined }).then((response) => response.requests || []),
  });
  const requests = useMemo(() => requestsQuery.data ?? [], [requestsQuery.data]);
  const loading = requestsQuery.isLoading || requestsQuery.isFetching;

  useEffect(() => {
    if (employeesQuery.error) showToast('error', employeesQuery.error.message || 'โหลดรายชื่อพนักงานไม่สำเร็จ');
  }, [employeesQuery.error, showToast]);
  useEffect(() => {
    if (requestsQuery.error) showToast('error', requestsQuery.error.message || 'โหลดคำขอเงินสวัสดิการไม่สำเร็จ');
  }, [requestsQuery.error, showToast]);
  useEffect(() => {
    if (typesQuery.error) showToast('error', typesQuery.error.message || 'โหลดประเภทคำขอไม่สำเร็จ');
  }, [typesQuery.error, showToast]);

  const submitEmployeeOptions = useMemo(
    () => employeeOptions.filter((employee) => employee.self || employee.directReport),
    [employeeOptions],
  );
  const hasMultipleSubmitOptions = submitEmployeeOptions.length > 1;
  const formSchema = useMemo(
    () => specialMoneyFormSchema({ requireEmployeeId: hasMultipleSubmitOptions }),
    [hasMultipleSubmitOptions],
  );

  const {
    register,
    handleSubmit,
    reset,
    setValue,
    getValues,
    control,
    formState: { errors },
  } = useForm({
    resolver: zodResolver(formSchema),
    defaultValues: defaultForm(currentEmployee?.id || user.employeeId || ''),
    mode: 'onChange',
    reValidateMode: 'onChange',
  });

  const watched = useWatch({ control });
  const selectedEmployeeId = watched.employeeId;
  const requestType = watched.requestType || '';

  useEffect(() => {
    if (!employeesQuery.data) return;
    const currentEmployeeId = getValues('employeeId');
    const nextEmployeeId = currentEmployeeId
      || submitEmployeeOptions.find((employee) => employee.self)?.employeeId
      || submitEmployeeOptions[0]?.employeeId
      || '';
    if (nextEmployeeId === currentEmployeeId) return;
    setValue('employeeId', String(nextEmployeeId), { shouldValidate: true });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [employeesQuery.data]);

  // The quota panel is scoped to whichever employee the form is currently
  // submitting for (self by default) -- "showing entitlement before intent".
  const usageEmployeeId = selectedEmployeeId ? Number(selectedEmployeeId) : (currentEmployee?.id || user.employeeId || null);
  const usageQuery = useQuery({
    queryKey: queryKeys.specialMoneyUsage(usageEmployeeId, new Date().getFullYear()),
    queryFn: () => api.specialMoney.usage({ employeeId: usageEmployeeId, year: new Date().getFullYear() }).then((response) => response.usage),
    enabled: Boolean(usageEmployeeId),
  });
  const usage = usageQuery.data;

  const medicalUsedThisYear = Number(usage?.approvedAmountThisYearByType?.MEDICAL || 0);
  const medicalRemaining = Math.max(0, MEDICAL_ANNUAL_CAP - medicalUsedThisYear);
  const medicalPercent = Math.min(100, Math.round((medicalUsedThisYear / MEDICAL_ANNUAL_CAP) * 100));
  const uniformClaimedThisYear = ['UNIFORM_ANNUAL', 'UNIFORM_NEW_STAFF', 'UNIFORM_PREPROBATION_KIT']
    .reduce((sum, type) => sum + Number(usage?.approvedAmountThisYearByType?.[type] || 0), 0);
  const weddingUsed = Number(usage?.approvedCountLifetimeByType?.AID_WEDDING || 0) >= 1;
  const ordinationUsed = Number(usage?.approvedCountLifetimeByType?.AID_ORDINATION || 0) >= 1;

  const cutoff = useMemo(() => payrollCutoffInfo(), []);

  const estimate = useMemo(() => estimateAmount(requestType, {
    eventDate: watched.eventDate,
    eventEndDate: watched.eventEndDate,
    province: watched.province,
    role: watched.role,
    shirtCount: watched.shirtCount,
    trouserCount: watched.trouserCount,
    requestedAmount: watched.requestedAmountInput,
  }, { medicalUsedThisYear, lifetimeCountByType: usage?.approvedCountLifetimeByType || {} }), [
    requestType, watched.eventDate, watched.eventEndDate, watched.province, watched.role,
    watched.shirtCount, watched.trouserCount, watched.requestedAmountInput, medicalUsedThisYear, usage,
  ]);

  // Types whose amount is fully server-computed from other fields (no manual
  // amount input shown) vs. types where the requester types an amount.
  const AUTO_COMPUTED_TYPES = ['TRAVEL_PER_DIEM', ...AID_TYPES, 'UNIFORM_ANNUAL'];
  const finalAmount = AUTO_COMPUTED_TYPES.includes(requestType) ? estimate.amount : Number(watched.requestedAmountInput || 0);

  const selectedTypeMeta = typeOptions.find((item) => item.requestType === requestType);
  const chip = selectedTypeMeta ? taxChip(selectedTypeMeta.payrollBucket) : null;

  const blockingWarning = requestType === 'TRAVEL_PER_DIEM' && watched.province && isExcludedProvince(watched.province);

  function invalidateSpecialMoney() {
    return Promise.all([
      queryClient.invalidateQueries({ queryKey: queryKeys.specialMoneyRequests(filters) }),
      queryClient.invalidateQueries({ queryKey: queryKeys.specialMoneyUsage(usageEmployeeId, new Date().getFullYear()) }),
    ]);
  }

  const createMutation = useMutation({
    mutationFn: (payload) => api.specialMoney.create(payload).then((response) => response.request),
    onSuccess: () => {
      reset(defaultForm(currentEmployee?.id || user.employeeId || ''));
      showToast('success', 'ส่งคำขอเงินสวัสดิการแล้ว');
      invalidateSpecialMoney();
    },
    onError: (error) => showToast('error', error.message || 'ส่งคำขอไม่สำเร็จ'),
  });

  const approveMutation = useMutation({
    mutationFn: (id) => api.specialMoney.approve(id, {}).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'อนุมัติคำขอแล้ว');
      setConfirmState(null);
      invalidateSpecialMoney();
    },
    onError: (error) => showToast('error', error.message || 'อนุมัติไม่สำเร็จ'),
  });

  const rejectMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.specialMoney.reject(id, { reviewerNote }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ปฏิเสธคำขอแล้ว');
      setConfirmState(null);
      invalidateSpecialMoney();
    },
    onError: (error) => showToast('error', error.message || 'ปฏิเสธไม่สำเร็จ'),
  });

  const cancelMutation = useMutation({
    mutationFn: ({ id, reviewerNote }) => api.specialMoney.cancel(id, { reviewerNote: reviewerNote?.trim() || null }).then((response) => response.request),
    onSuccess: () => {
      showToast('success', 'ยกเลิกคำขอแล้ว');
      setConfirmState(null);
      invalidateSpecialMoney();
    },
    onError: (error) => showToast('error', error.message || 'ยกเลิกไม่สำเร็จ'),
  });

  const saving = createMutation.isPending || approveMutation.isPending || rejectMutation.isPending || cancelMutation.isPending;

  function detailFor(values) {
    if (values.requestType === 'TRAVEL_PER_DIEM') return { destination: 'DOMESTIC', province: values.province, role: values.role };
    if (values.requestType === 'TRAVEL_LODGING') return { destination: 'DOMESTIC', province: values.province };
    if (values.requestType === 'UNIFORM_ANNUAL') {
      return { uniformMode: 'SELF_BUY', shirtCount: values.shirtCount, trouserCount: values.trouserCount };
    }
    if (UNIFORM_TYPES.includes(values.requestType)) {
      return { shirtCount: values.shirtCount, trouserCount: values.trouserCount };
    }
    if (values.requestType === 'AID_FUNERAL') return { relation: values.relation };
    return {};
  }

  function submitSpecialMoney(values) {
    const isTravel = ['TRAVEL_PER_DIEM', 'TRAVEL_LODGING'].includes(values.requestType);
    const eventDate = values.requestType === 'MEDICAL' ? values.receiptDate : values.eventDate;
    const payload = {
      requestType: values.requestType,
      employeeId: values.employeeId ? Number(values.employeeId) : null,
      eventDate,
      eventEndDate: isTravel ? values.eventEndDate : null,
      receiptDate: (values.requestType === 'MEDICAL' || values.requestType === 'UNIFORM_ANNUAL') ? values.receiptDate : null,
      requestedAmount: finalAmount,
      reason: values.reason.trim(),
      detail: detailFor(values),
    };
    createMutation.mutate(payload);
  }

  function approve(id) {
    setConfirmState({ kind: 'approve', id });
  }
  function confirmApprove() {
    approveMutation.mutate(confirmState.id);
  }
  function reject(id) {
    setConfirmState({ kind: 'reject', id });
  }
  function confirmReject(reviewerNote) {
    if (!reviewerNote?.trim()) return;
    rejectMutation.mutate({ id: confirmState.id, reviewerNote: reviewerNote.trim() });
  }

  function managesRequest(request) {
    const directManager = request.managerEmployeeId && Number(request.managerEmployeeId) === Number(user.employeeId);
    const divisionManager = user.manager && Number(request.employeeId) !== Number(user.employeeId);
    return Boolean(directManager || divisionManager);
  }
  function canManagerApprove(request) {
    return request.status === 'SUBMITTED' && managesRequest(request);
  }
  function canCeoApprove(request) {
    return request.status === 'MANAGER_APPROVED' && user.role === 'ceo';
  }
  function canReviewRequest(request) {
    return canManagerApprove(request) || canCeoApprove(request);
  }
  // Mirrors SpecialMoneyService.cancel(): only the employee or whoever filed on
  // their behalf, and only while SUBMITTED -- no manager-cancel path here,
  // unlike overtime.
  function canCancel(request) {
    const isEmployee = Number(request.employeeId) === Number(user.employeeId);
    const isRequester = request.requestedById != null && Number(request.requestedById) === Number(user.employeeId);
    return request.status === 'SUBMITTED' && (isEmployee || isRequester);
  }
  function cancel(id) {
    setConfirmState({ kind: 'cancel', id });
  }
  function doCancel(id, reviewerNote) {
    cancelMutation.mutate({ id, reviewerNote });
  }

  const requestTypeError = errors.requestType?.message;

  return (
    <div className="grid grid-cols-1 gap-[18px] min-w-0">
      {/* 1. Quota panel — entitlement shown BEFORE intent */}
      <Panel title={`โควตาคงเหลือปี ${currentBuddhistYear()}`}>
        <div className="grid grid-cols-3 max-[900px]:grid-cols-1 gap-3.5">
          <div className="border border-border rounded-md p-3.5">
            <div className="text-xs font-bold text-icon-muted">ค่ารักษาพยาบาลคงเหลือ</div>
            <div className="text-xl font-extrabold mt-1">{formatMoney(medicalRemaining)}</div>
            <div className="h-2 rounded-full bg-border mt-2 overflow-hidden" role="progressbar" aria-valuenow={medicalPercent} aria-valuemin={0} aria-valuemax={100}>
              <div className="h-full bg-primary" style={{ width: `${medicalPercent}%` }} />
            </div>
            <div className="text-xs text-icon-muted mt-1.5">ใช้ไปแล้ว {formatMoney(medicalUsedThisYear)} จาก ฿3,000/ปี</div>
          </div>
          <div className="border border-border rounded-md p-3.5">
            <div className="text-xs font-bold text-icon-muted">ชุดฟอร์มที่เบิกแล้วปีนี้</div>
            <div className="text-xl font-extrabold mt-1">{formatMoney(uniformClaimedThisYear)}</div>
            <div className="text-xs text-icon-muted mt-1.5">ใบเสร็จลงวันที่ พ.ค. · เบิกได้ในเดือน มิ.ย.</div>
          </div>
          <div className="border border-border rounded-md p-3.5">
            <div className="text-xs font-bold text-icon-muted">เงินช่วยเหลือตลอดอายุงาน</div>
            <div className="text-sm font-bold mt-1">
              งานแต่ง: {weddingUsed ? 'ใช้สิทธิแล้ว' : 'ยังไม่ใช้สิทธิ'} · งานบวช: {ordinationUsed ? 'ใช้สิทธิแล้ว' : 'ยังไม่ใช้สิทธิ'}
            </div>
            <div className="text-xs text-icon-muted mt-1.5">งานแต่ง · งานบวช เบิกได้ครั้งเดียวตลอดอายุงาน</div>
          </div>
        </div>
      </Panel>

      {/* 2. Cutoff strip — informational, not a warning */}
      <div className="bg-surface border border-border rounded-md px-3.5 py-2.5 flex items-center gap-2.5 text-sm">
        <Icon name="calendar" size={16} />
        <span>
          ตัดรอบเงินเดือนวันที่ 25 · เหลืออีก <strong>{cutoff.daysRemaining}</strong> วัน — คำขอที่อนุมัติหลังวันที่ 25
          จะจ่ายในงวดถัดไป (จ่ายในงวด {formatThaiMonthYear(cutoff.targetMonth)})
        </span>
      </div>

      {/* 3-5. Submit form */}
      <Panel title="ยื่นคำขอเงินสวัสดิการ">
        <form className={FORM_GRID_CLASS} onSubmit={handleSubmit(submitSpecialMoney)} noValidate>
          {hasMultipleSubmitOptions ? (
            <FormField label="พนักงาน" htmlFor="smr-employee" error={errors.employeeId?.message}>
              <select id="smr-employee" {...register('employeeId')} required>
                <option value="">เลือกพนักงาน</option>
                {submitEmployeeOptions.map((employee) => (
                  <option key={employee.employeeId} value={employee.employeeId}>
                    {employee.employeeName} · {employee.employeeCode}{employee.directReport ? ' · ลูกทีม' : ''}
                  </option>
                ))}
              </select>
            </FormField>
          ) : (
            <FormField label="พนักงาน" htmlFor="smr-employee-display">
              <input id="smr-employee-display" value={currentEmployee?.nameTh || user.name || '-'} disabled />
            </FormField>
          )}

          <FormField label="ประเภทคำขอ" htmlFor="smr-type" error={requestTypeError} required>
            <select id="smr-type" {...register('requestType')} required>
              <option value="">เลือกประเภท</option>
              {['เดินทาง', 'ค่ารักษาพยาบาล', 'เงินช่วยเหลือ', 'เครื่องแต่งกาย', 'อื่นๆ'].map((category) => {
                const options = typeOptions.filter((item) => typeCategory(item.requestType) === category);
                if (options.length === 0) return null;
                return (
                  <optgroup key={category} label={category}>
                    {options.map((item) => (
                      <option key={item.requestType} value={item.requestType}>{item.thaiLabel}</option>
                    ))}
                  </optgroup>
                );
              })}
            </select>
          </FormField>

          {['TRAVEL_PER_DIEM', 'TRAVEL_LODGING'].includes(requestType) ? (
            <>
              <FormField label="วันที่เริ่มเดินทาง" htmlFor="smr-event-date" error={errors.eventDate?.message} required>
                <input id="smr-event-date" type="date" {...register('eventDate')} required />
              </FormField>
              <FormField label="วันที่สิ้นสุด" htmlFor="smr-event-end-date" error={errors.eventEndDate?.message} required>
                <input id="smr-event-end-date" type="date" {...register('eventEndDate')} required />
              </FormField>
              <FormField
                label="จังหวัดปลายทาง"
                htmlFor="smr-province"
                error={errors.province?.message}
                hint={blockingWarning ? undefined : 'ต้องเลือกจากรายการ — ระบบตรวจสอบจังหวัดยกเว้นด้วยชื่อที่ตรงกันเท่านั้น'}
                required
              >
                <select id="smr-province" {...register('province')} required>
                  <option value="">เลือกจังหวัด</option>
                  {THAI_PROVINCES.map((province) => (
                    <option key={province} value={province}>{province}</option>
                  ))}
                </select>
              </FormField>
              {requestType === 'TRAVEL_PER_DIEM' ? (
                <FormField label="บทบาท" htmlFor="smr-role" error={errors.role?.message} required>
                  <select id="smr-role" {...register('role')} required>
                    <option value="">เลือกบทบาท</option>
                    <option value="driver">คนขับ</option>
                    <option value="loader">ผู้ช่วย/ยกของ</option>
                  </select>
                </FormField>
              ) : (
                <FormField label="จำนวนเงินค่าที่พัก" htmlFor="smr-amount" error={errors.requestedAmountInput?.message} required>
                  <input id="smr-amount" type="number" min="0" step="1" {...register('requestedAmountInput')} required />
                </FormField>
              )}
            </>
          ) : null}

          {requestType === 'MEDICAL' ? (
            <>
              <FormField label="วันที่ใบเสร็จ" htmlFor="smr-receipt-date" error={errors.receiptDate?.message} required>
                <input id="smr-receipt-date" type="date" {...register('receiptDate')} required />
              </FormField>
              <FormField label="จำนวนเงินที่ขอเบิก" htmlFor="smr-amount" error={errors.requestedAmountInput?.message} required>
                <input id="smr-amount" type="number" min="0" step="1" {...register('requestedAmountInput')} required />
              </FormField>
            </>
          ) : null}

          {AID_TYPES.includes(requestType) ? (
            <>
              <FormField label="วันที่เกิดเหตุการณ์" htmlFor="smr-event-date" error={errors.eventDate?.message} required>
                <input id="smr-event-date" type="date" {...register('eventDate')} required />
              </FormField>
              {requestType === 'AID_FUNERAL' ? (
                <FormField label="ความสัมพันธ์กับผู้เสียชีวิต" htmlFor="smr-relation" error={errors.relation?.message} required>
                  <select id="smr-relation" {...register('relation')} required>
                    <option value="">เลือกความสัมพันธ์</option>
                    <option value="parent">บิดา/มารดา</option>
                    <option value="spouse">คู่สมรส</option>
                    <option value="child">บุตร</option>
                  </select>
                </FormField>
              ) : null}
            </>
          ) : null}

          {UNIFORM_TYPES.includes(requestType) ? (
            <>
              <FormField label="วันที่เบิก" htmlFor="smr-event-date" error={errors.eventDate?.message} required>
                <input id="smr-event-date" type="date" {...register('eventDate')} required />
              </FormField>
              {requestType === 'UNIFORM_ANNUAL' ? (
                <FormField label="วันที่ใบเสร็จ" htmlFor="smr-receipt-date" error={errors.receiptDate?.message} required>
                  <input id="smr-receipt-date" type="date" {...register('receiptDate')} required />
                </FormField>
              ) : null}
              <FormField label="จำนวนเสื้อ (ชิ้น)" htmlFor="smr-shirt-count" error={errors.shirtCount?.message}>
                <input id="smr-shirt-count" type="number" min="0" step="1" {...register('shirtCount')} />
              </FormField>
              <FormField label="จำนวนกางเกง (ชิ้น)" htmlFor="smr-trouser-count" error={errors.trouserCount?.message}>
                <input id="smr-trouser-count" type="number" min="0" step="1" {...register('trouserCount')} />
              </FormField>
              {requestType !== 'UNIFORM_ANNUAL' ? (
                <FormField label="จำนวนเงินที่ขอเบิก" htmlFor="smr-amount" error={errors.requestedAmountInput?.message} required>
                  <input id="smr-amount" type="number" min="0" step="1" {...register('requestedAmountInput')} required />
                </FormField>
              ) : null}
            </>
          ) : null}

          {(requestType === 'TRAINING' || requestType === 'OTHER') ? (
            <>
              <FormField label="วันที่" htmlFor="smr-event-date" error={errors.eventDate?.message} required>
                <input id="smr-event-date" type="date" {...register('eventDate')} required />
              </FormField>
              <FormField label="จำนวนเงินที่ขอเบิก" htmlFor="smr-amount" error={errors.requestedAmountInput?.message} required>
                <input id="smr-amount" type="number" min="0" step="1" {...register('requestedAmountInput')} required />
              </FormField>
            </>
          ) : null}

          {requestType ? (
            <div className={formGridSpan2}>
              <div className="border border-border rounded-md p-3.5 flex flex-wrap items-center justify-between gap-2.5">
                <div>
                  <div className="text-2xl font-extrabold" data-testid="smr-computed-amount">{formatMoney(finalAmount)}</div>
                  {estimate.working ? <div className="text-xs text-icon-muted mt-1">{estimate.working}</div> : null}
                  <div className="text-xs text-icon-muted mt-0.5">
                    จ่ายในงวด {formatThaiMonthYear(cutoff.targetMonth)}
                  </div>
                </div>
                {chip ? <StatusBadge tone={chip.tone}>{chip.label}</StatusBadge> : null}
              </div>
              {estimate.warnings.map((warning) => (
                <p key={warning} className="m-0 mt-2 text-xs font-bold text-danger" role="alert">{warning}</p>
              ))}
            </div>
          ) : null}

          <div className={formGridSpan2}>
            <FormField label="เหตุผล / รายละเอียด" htmlFor="smr-reason" error={errors.reason?.message} required>
              <textarea id="smr-reason" rows={3} {...register('reason')} required />
            </FormField>
          </div>

          {requestType ? (
            <div className={formGridSpan2}>
              <FormField label="หลักฐานประกอบ" htmlFor="smr-evidence" hint="อัปโหลดไฟล์ยังไม่รองรับในเวอร์ชันนี้ — ระบบยังไม่มี endpoint สำหรับแนบไฟล์ กรุณาเตรียมเอกสารไว้ส่งต่างหากตามที่ผู้จัดการ/HR แจ้ง">
                <div id="smr-evidence" className="border border-dashed border-border rounded-md p-3.5 text-center text-sm text-icon-muted bg-surface-muted opacity-70" aria-disabled="true">
                  <Icon name="paperclip" size={16} />
                  <div className="mt-1">{evidenceLabel(requestType)} (ยังไม่เปิดใช้งานการอัปโหลด)</div>
                </div>
              </FormField>
            </div>
          ) : null}

          <RowActions className={formGridSpan2}>
            <Button type="submit" disabled={saving || !requestType || blockingWarning} className="max-[720px]:min-h-11 max-[720px]:w-full">
              <Icon name="plus" />
              ส่งคำขอ
            </Button>
          </RowActions>
        </form>
      </Panel>

      {/* 6. Request list — bordered rows, not cards */}
      <Panel
        title="คำขอเงินสวัสดิการ"
        actions={(
          <select value={statusFilter} onChange={(event) => setStatusFilter(event.target.value)} aria-label="กรองตามสถานะ">
            <option value="">ทุกสถานะ</option>
            <option value="SUBMITTED">รอผู้จัดการ</option>
            <option value="MANAGER_APPROVED">รอ CEO</option>
            <option value="APPROVED">อนุมัติแล้ว</option>
            <option value="REJECTED">ปฏิเสธ</option>
            <option value="CANCELLED">ยกเลิก</option>
          </select>
        )}
      >
        <div className={`${TABLE_GRID} table-head`}>
          <span>ประเภท / รายละเอียด</span>
          <span>สถานะ</span>
          <span>งวดจ่าย</span>
          <span />
        </div>
        {loading ? (
          <EmptyState icon="badgeDollar" title="กำลังโหลดคำขอ" />
        ) : requests.length === 0 ? (
          <EmptyState icon="badgeDollar" title="ยังไม่มีคำขอเงินสวัสดิการ" description="ยื่นคำขอใหม่ด้านบน" />
        ) : requests.map((request) => {
          const status = statusInfo(request.status);
          const typeMeta = typeOptions.find((item) => item.requestType === request.requestType);
          const reviewable = canReviewRequest(request);
          const approveTitle = canCeoApprove(request) ? 'CEO อนุมัติ' : 'ผู้จัดการอนุมัติ';
          return (
            <div className={`${TABLE_GRID} data-row`} key={request.id}>
              <span data-label="ประเภท / รายละเอียด" className="max-[720px]:order-1">
                <strong>{typeMeta?.thaiLabel || request.requestType}</strong>
                <small>{request.employeeName || request.employeeCode} · {formatDate(request.eventDate)} · {formatMoney(request.approvedAmount ?? request.requestedAmount)}</small>
              </span>
              <span data-label="สถานะ" className="max-[720px]:order-2">
                <StatusBadge tone={status.tone}>{status.label}</StatusBadge>
                {request.reviewerNote ? <small>{request.reviewerNote}</small> : null}
              </span>
              <span data-label="งวดจ่าย" className="max-[720px]:order-3">
                {request.payrollMonth ? `งวด ${formatThaiMonthYear(new Date(`${request.payrollMonth}T00:00:00`))}` : 'ยังไม่กำหนด'}
              </span>
              <span className="row-actions max-[720px]:order-4">
                {reviewable ? (
                  <>
                    <Button
                      type="button"
                      variant="icon"
                      title={approveTitle}
                      aria-label={approveTitle}
                      disabled={saving}
                      style={{ color: 'var(--color-success)', borderColor: 'var(--color-success)' }}
                      onClick={() => approve(request.id)}
                    >
                      <Icon name="check" size={14} />
                    </Button>
                    <Button
                      type="button"
                      variant="icon"
                      title="ปฏิเสธ"
                      aria-label="ปฏิเสธ"
                      disabled={saving}
                      style={{ color: 'var(--color-danger)', borderColor: 'var(--color-danger)' }}
                      onClick={() => reject(request.id)}
                    >
                      <Icon name="close" size={14} />
                    </Button>
                  </>
                ) : null}
                {canCancel(request) ? (
                  <Button type="button" variant="icon" title="ยกเลิก" aria-label="ยกเลิก" disabled={saving} onClick={() => cancel(request.id)}>
                    <Icon name="close" size={14} />
                  </Button>
                ) : null}
              </span>
            </div>
          );
        })}
      </Panel>

      <ConfirmDialog
        open={confirmState?.kind === 'approve'}
        title="ยืนยันการอนุมัติ"
        message="ยืนยันการอนุมัติคำขอเงินสวัสดิการนี้?"
        confirmLabel="อนุมัติ"
        busy={saving}
        onConfirm={confirmApprove}
        onCancel={() => setConfirmState(null)}
      />
      <ConfirmDialog
        open={confirmState?.kind === 'reject'}
        title="ปฏิเสธคำขอ"
        message='ยืนยันการปฏิเสธคำขอนี้? สถานะจะเปลี่ยนเป็น "ปฏิเสธ"'
        confirmLabel="ปฏิเสธคำขอ"
        tone="danger"
        busy={saving}
        requireReason
        reasonLabel="เหตุผลการปฏิเสธ"
        onConfirm={confirmReject}
        onCancel={() => setConfirmState(null)}
      />
      <ConfirmDialog
        open={confirmState?.kind === 'cancel'}
        title="ยกเลิกคำขอ"
        message='ยืนยันการยกเลิกคำขอนี้? สถานะจะเปลี่ยนเป็น "ยกเลิก" ถาวร'
        confirmLabel="ยกเลิกคำขอ"
        tone="danger"
        busy={saving}
        requireReason
        optionalReason
        reasonLabel="หมายเหตุการยกเลิก (ถ้ามี)"
        onConfirm={(reason) => doCancel(confirmState.id, reason)}
        onCancel={() => setConfirmState(null)}
      />
    </div>
  );
}
