import { useCallback, useEffect, useMemo, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useSearchParams } from 'react-router-dom';
import { api } from '../../api/index.js';
import { queryKeys } from '../../api/queryKeys.js';
import { Button } from '../../components/common/Button.jsx';
import { ConfirmDialog } from '../../components/common/ConfirmDialog.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { PageHeader } from '../../components/common/PageHeader.jsx';
import { PageStack, Panel } from '../../components/common/Layout.jsx';
import { StatusBadge } from '../../components/common/StatusBadge.jsx';
import { TaxAllowanceForm } from './TaxAllowanceForm.jsx';
import { buildAllowanceSubmitBody, defaultAllowanceValues, SIGNED_FORM_EVIDENCE_KEY, UNCATEGORIZED_EVIDENCE_KEY } from './taxAllowanceSchema.js';
import { selectCurrentDeclaration, selectResumableDeclaration, taxAllowanceStatusInfo } from './taxAllowanceStatus.js';

// Editable directly (or via "แก้ไข / ยื่นฉบับใหม่"): no declaration yet, or the current one was
// rejected/expired.
const EDITABLE_STATUS_KEYS = new Set(['NONE', 'REJECTED', 'EXPIRED']);

/**
 * Already confirmed, and revisable. An approved or applied declaration needs an action
 * here, or an employee whose circumstances changed (a child born, a policy lapsed, an amount
 * imported wrong from the legacy report) could not correct their filing or even get a copy.
 *
 * <p>The server has always accepted a revision: {@code TaxAllowanceDeclarationService#submitOwn}
 * refuses ONLY when a PENDING row exists ({@code repository.existsPending}), and {@code #approve}
 * calls {@code repository.supersedeApproved(...)} before approving (decision #7: <i>"a new
 * submission supersedes the previous once approved"</i>), which is why {@code SUPERSEDED} is a
 * status at all. A revision is not destructive: the current declaration keeps applying to payroll
 * until HR approves the replacement, because `supersedeApproved` runs inside `approve`, not at
 * submit.
 */
const REVISABLE_STATUS_KEYS = new Set(['APPROVED_UNAPPLIED', 'APPLIED']);

// `?view=` is gone: the screen is one page of collapsibles in the government form's own order, so
// there are no sub-screens left to address by URL (see TaxAllowanceForm's javadoc).

// Status-specific explanation line for the unified status region below. No entry for
// APPROVED_UNAPPLIED/APPLIED: `taxAllowanceStatusInfo`'s badge label is already a complete
// sentence for those two, so a second line would just echo it.
//
// Each line must say something the BADGE BESIDE IT does not: NONE's badge already reads
// "ยังไม่ได้ยื่น" and PENDING's "รอ HR ตรวจสอบ", so these carry only the next action.
const STATUS_EXPLANATIONS = {
  NONE: 'กรอกแบบฟอร์มด้านล่าง สร้างไฟล์ ล.ย.01 ออกมาลงนาม แล้วแนบกลับเพื่อยื่นให้ฝ่ายบุคคล',
  PENDING: 'แก้ไขไม่ได้ระหว่างนี้ — ต้องการแก้ไขให้ยกเลิกการยื่น แล้วยื่นฉบับใหม่',
  REJECTED: 'แก้ไขแล้วยื่นใหม่ได้ทันที',
  // Points at the button, not "กรอกแบบฟอร์มด้านล่าง": unlike NONE, `editing` does NOT start true for
  // EXPIRED (only `statusInfo.key === 'NONE'` does), so every field stays disabled until
  // "แก้ไข / ยื่นฉบับใหม่" is pressed and telling the reader to fill in the form would be a false
  // instruction. NONE's copy is fine: the form is already open there.
  EXPIRED: 'กด "แก้ไข / ยื่นฉบับใหม่" เพื่อกรอกแบบฟอร์มและยืนยันสิทธิใหม่อีกครั้ง',
};

export function TaxAllowancePage({ user, showToast }) {
  const queryClient = useQueryClient();
  const [searchParams, setSearchParams] = useSearchParams();
  const currentYear = new Date().getFullYear();
  // Past years are viewable but never editable: submitting always targets the year in the body, and there is no
  // product story for filing a fresh ล.ย.01 against a closed tax year.
  const yearOptions = useMemo(
    () => [currentYear, currentYear - 1, currentYear - 2],
    [currentYear],
  );

  // `year`/`view` live in the URL (same `updateParams` idiom as TaxAllowanceReviewPage.jsx's
  // register): shareable, survives a reload, and gives HUB/SECTION/REVIEW a real address.
  const requestedYear = Number(searchParams.get('year'));
  // An out-of-range or garbage `?year=` falls back to the current year rather than leaving the <select> on a
  // blank option that matches nothing in the list.
  const taxYear = yearOptions.includes(requestedYear) ? requestedYear : currentYear;
  const isCurrentYear = taxYear === currentYear;


  // `replace` defaults to false (a real history entry): HUB/SECTION/REVIEW are URL-addressable
  // screens, so Back should step through them rather than leave `/tax-allowance` and drop
  // everything typed. Year switches opt back into `replace: true`: a year is a filter on the same
  // screen. The merge/delete-empty logic is TaxAllowanceReviewPage.jsx's idiom, copied verbatim.
  const updateParams = useCallback((patch, { replace = false } = {}) => {
    setSearchParams((previous) => {
      const next = new URLSearchParams(previous);
      Object.entries(patch).forEach(([key, value]) => {
        if (value === '' || value == null) next.delete(key);
        else next.set(key, String(value));
      });
      return next;
    }, { replace });
  }, [setSearchParams]);

  const [editing, setEditing] = useState(false);
  const [withdrawing, setWithdrawing] = useState(false);
  // True while the employee is being asked to confirm abandoning a revision that has unsaved work.
  const [cancellingRevision, setCancellingRevision] = useState(false);
  // Set from TaxAllowanceForm's own `formState.isDirty` via `onDirtyChange` below — guards the year switch
  // against silently discarding unsaved edits, the same way `hasStagedEvidence` guards it against silently
  // discarding staged-but-unsent files.
  const [formDirty, setFormDirty] = useState(false);
  // The year the employee picked while a switch is blocked on confirmation; null means none is pending. Kept
  // separate from `taxYear` itself so the <select> (`value={taxYear}`) keeps showing the OLD year until the
  // switch is actually confirmed — that "don't commit yet" is the point.
  const [pendingYearChange, setPendingYearChange] = useState(null);
  // The values a submit event asked to file, held while the employee confirms; null means no confirmation is
  // open. See `handleSubmit` below.
  const [pendingSubmitValues, setPendingSubmitValues] = useState(null);

  // Staged (not-yet-uploaded) evidence, keyed by TAX_ALLOWANCE_GROUPS' `key` (the null key holds
  // the hub's "general/uncategorized" bucket). While `editing` there is no declarationId a real
  // upload could attach to (a brand-new declaration doesn't exist server-side until submit; a
  // REJECTED/EXPIRED one being re-prepared has only an OLD declarationId), so files picked
  // mid-fill-in are held here and uploaded once submit succeeds (see `flushStagedEvidence`). See
  // TaxAllowanceEvidencePanel's javadoc.
  const [stagedEvidence, setStagedEvidence] = useState({});
  const stagedEvidenceKey = (sectionKey) => sectionKey ?? UNCATEGORIZED_EVIDENCE_KEY;
  const hasStagedEvidence = useMemo(
    () => Object.values(stagedEvidence).some((files) => files.length > 0),
    [stagedEvidence],
  );

  function stageEvidence(sectionKey, file) {
    const key = stagedEvidenceKey(sectionKey);
    const tempId = `staged-${Date.now()}-${Math.random().toString(36).slice(2)}`;
    setStagedEvidence((prev) => ({
      ...prev,
      [key]: [...(prev[key] ?? []), { tempId, file, fileName: file.name, fileSize: file.size }],
    }));
  }

  function unstageEvidence(sectionKey, tempId) {
    const key = stagedEvidenceKey(sectionKey);
    setStagedEvidence((prev) => ({
      ...prev,
      [key]: (prev[key] ?? []).filter((item) => item.tempId !== tempId),
    }));
  }

  // Guards the year <select>: switching years reloads a DIFFERENT declaration under the same form,
  // which resets it (TaxAllowanceForm's `reset(defaultValues)` effect) and drops anything staged
  // for the year being left, so a dirty form or staged files get a confirm first, via
  // `pendingYearChange` below.
  function requestYearChange(nextYear) {
    if (nextYear === taxYear) return;
    if (formDirty || hasStagedEvidence) {
      setPendingYearChange(nextYear);
    } else {
      // Reset back to the hub too: a section open for the OLD year's declaration has no claim to
      // still being open for a different one. `replace: true`, unlike a plain view change (see
      // `updateParams`' comment).
      updateParams({ year: nextYear }, { replace: true });
    }
  }

  function confirmYearChange() {
    updateParams({ year: pendingYearChange }, { replace: true });
    setPendingYearChange(null);
  }

  const capsQuery = useQuery({
    queryKey: queryKeys.taxAllowanceCaps(taxYear),
    queryFn: () => api.payroll.getTaxAllowanceCaps(taxYear).then((response) => response.caps || []),
  });
  const caps = capsQuery.data ?? [];

  // Keeps the WHOLE envelope rather than mapping straight to `.items`: the same response carries
  // `headerPrefill`. Riding on this one query is deliberate: a second, independently-timed request
  // would land after the form mounted and change `defaultValues`' identity mid-typing, and
  // TaxAllowanceForm's `reset(defaultValues)` effect would wipe what the employee entered. React
  // Query's structural sharing keeps this object's identity stable across refetches that return
  // the same JSON.
  const declarationsQuery = useQuery({
    queryKey: queryKeys.taxAllowanceDeclarationsMe(taxYear),
    queryFn: () => api.payroll.getMyTaxAllowanceDeclarations(taxYear),
    enabled: !!user?.employeeId,
  });
  const declarations = useMemo(() => declarationsQuery.data?.items ?? [], [declarationsQuery.data]);
  const headerPrefill = declarationsQuery.data?.headerPrefill ?? null;
  const current = useMemo(() => selectCurrentDeclaration(declarations), [declarations]);
  // Feeds ONLY the form's prefill when there is no current declaration for this tax year -- see
  // selectResumableDeclaration's own doc comment for why WITHDRAWN specifically and why this must never feed
  // statusInfo/canStartEdit/evidenceMode, all of which stay keyed on `current` alone below.
  const resumable = useMemo(() => selectResumableDeclaration(declarations), [declarations]);
  const statusInfo = useMemo(() => taxAllowanceStatusInfo(current), [current]);
  const canStartEdit = EDITABLE_STATUS_KEYS.has(statusInfo.key) && isCurrentYear;
  // Kept SEPARATE from `canStartEdit` rather than folded into it, because the two differ in what they mean for
  // the prefill below: a revisable declaration is one HR has already accepted, so it must keep showing what was
  // filed until the employee actually asks to revise it.
  const canRevise = REVISABLE_STATUS_KEYS.has(statusInfo.key) && isCurrentYear;

  // Three-way evidence mode -- see TaxAllowanceEvidencePanel's own
  // javadoc for what each does. `editing` is never true while `current.status === 'PENDING'`
  // (PENDING is outside EDITABLE_STATUS_KEYS), so these two branches are mutually exclusive with
  // the pre-existing `canEdit={current?.status === 'PENDING'}` behaviour this generalizes.
  const evidenceMode = editing ? 'staging' : (current?.status === 'PENDING' ? 'direct' : 'readonly');

  useEffect(() => {
    // Do not decide "this year has never been filed" until the query has actually settled. While
    // it is in flight `current` is null, which reads as statusInfo 'NONE' and would flip the whole
    // page to editable for one paint -- live submit button, and a staged-evidence manifest of
    // zeros -- before snapping back to read-only once the real declaration lands. Deep-linking to
    // `?view=review` on a PENDING declaration is where that flash is visible, and a false `0 ไฟล์`
    // there is exactly what the manifest's own `evidenceMode === 'staging'` gate exists to prevent.
    // `isLoading` rather than `isPending`: a disabled query stays pending forever.
    if (declarationsQuery.isLoading) return;
    setEditing(statusInfo.key === 'NONE' && isCurrentYear);
    // A different declaration (or none) is now current -- any staged-but-unsent evidence belonged to whatever was
    // being edited a moment ago; carrying it forward across an identity change would attach it to the wrong
    // declaration once flushed.
    setStagedEvidence({});
  }, [statusInfo.key, current?.declarationId, isCurrentYear, declarationsQuery.isLoading]);

  // `taxYear` is in the dep list ALONGSIDE `current`: when neither the old year nor the new one has
  // a declaration, `current` is `null` before AND after the switch, so a memo keyed on `current`
  // alone never recomputes, `defaultValues` keeps its identity, TaxAllowanceForm's
  // `reset(defaultValues)` effect never fires, and typed-but-unsaved values would survive into the
  // new year, contradicting the year-switch confirm dialog's promise to discard them.
  // `defaultAllowanceValues` always returns a fresh object, so adding `taxYear` guarantees the
  // identity change `reset` needs. No render loop: `taxYear` is derived read-only from
  // `searchParams` and nothing downstream of this memo calls `setSearchParams`.
  //
  // `resumable` (the newest WITHDRAWN declaration for this year, see selectResumableDeclaration) is
  // a genuine input via `current ?? resumable`: withdrawing a PENDING declaration must not wipe
  // what the employee typed (the PENDING banner promises "ยกเลิกการยื่นเพื่อแก้ไข" and the withdraw
  // dialog "กลับมาแก้ไข"). This is PREFILL ONLY: `current` is untouched, so
  // `statusInfo`/`canStartEdit`/`evidenceMode` keep reporting ยังไม่ได้ยื่น.
  //
  // The disable is load-bearing, not noise-suppression: exhaustive-deps calls `taxYear`
  // "unnecessary" because the memo body never reads it, but it is a cache-busting key. Deleting it
  // silently restores the bug described above.
  // `headerPrefill` is gated on `headerPrefillAllowed` below, and BOTH halves of that expression
  // are load-bearing:
  //
  //   - It must not reach a READ-ONLY view. A PENDING or APPROVED declaration shows what was
  //     actually filed; seeding its blank header slots from today's master record would show the
  //     employee an address they never declared, on a document HR has already accepted. Past tax
  //     years are the same defect with more distance.
  //   - Gating on `editing` ALONE would make the memo recompute the moment "แก้ไข / ยื่นฉบับใหม่"
  //     is pressed, firing TaxAllowanceForm's `reset(defaultValues)` on a click that is supposed to
  //     do nothing but unlock the fields.
  //
  // `canStartEdit || editing` satisfies both at once, and the boolean is hoisted OUT of the
  // dependency array on purpose: the memo then recomputes when the PERMISSION changes, not every
  // time `editing` moves. For a REJECTED/EXPIRED declaration `canStartEdit` is already true, so
  // pressing the button leaves the boolean at `true` and no reset fires — exactly the old
  // behaviour. For a revisable one it flips false→true at the moment the employee asks to revise,
  // which is precisely when their own master data should start filling the blanks (an APPLIED
  // declaration imported from the legacy allowance report carries no ล.ย.01 header at all, so
  // without this the revision would open on an empty name, tax ID and address).
  //
  // `defaultAllowanceValues` composes them per slot: anything the declaration already holds wins,
  // and the prefill only reaches slots the employee left blank.
  const headerPrefillAllowed = canStartEdit || editing;
  // The disable below has to sit immediately above the DEPENDENCY ARRAY, not above the `useMemo` call:
  // exhaustive-deps reports on the array's own line, and this call no longer fits on one.
  const defaultValues = useMemo(
    () => defaultAllowanceValues(current ?? resumable, headerPrefillAllowed ? headerPrefill : null),
    // eslint-disable-next-line react-hooks/exhaustive-deps
    [current, resumable, taxYear, headerPrefillAllowed, headerPrefill],
  );

  /**
   * The signed scan gates submit.
   *
   * Reads the STAGED bucket, not the server's attachment list, because while `editing` there is no
   * declarationId to have uploaded against yet — the file is held client-side and flushed after
   * submit succeeds, exactly like every other piece of evidence on this screen. The backend refuses
   * to APPROVE a declaration with no signed_form attachment, so this gate is the courtesy and that
   * one is the enforcement; neither alone is enough.
   */
  const signedFormAttached = (stagedEvidence[SIGNED_FORM_EVIDENCE_KEY] ?? []).length > 0;

  /** Hands a Blob to the browser as a download. Shared by the two ล.ย.01 PDF paths below. */
  function saveBlob(blob, fileName) {
    const url = URL.createObjectURL(blob);
    const link = document.createElement('a');
    link.href = url;
    link.download = fileName;
    document.body.appendChild(link);
    link.click();
    link.remove();
    URL.revokeObjectURL(url);
  }

  /** Renders the filled ล.ย.01 from the CURRENT, unsaved values and hands it to the browser to download. Nothing
   * is persisted — the employee prints it, signs it, and attaches the scan back. */
  const pdfMutation = useMutation({
    mutationFn: (body) => api.payroll.renderMyTaxAllowanceForm(body),
    onSuccess: (blob) => {
      saveBlob(blob, `loryor01-${taxYear}.pdf`);
      showToast?.('success', 'สร้างไฟล์ PDF แล้ว — พิมพ์ ลงนาม แล้วแนบกลับเพื่อยื่น');
    },
    onError: (error) => showToast?.('error', error?.message || 'สร้างไฟล์ PDF ไม่สำเร็จ'),
  });

  /**
   * The employee's own copy of a declaration they have ALREADY filed. `GET /declarations/{id}/form.pdf`
   * and `hrApi.downloadTaxAllowanceForm` have no other call site: the only other PDF button lives in
   * the sign-off panel, which read-only does not render, so a filed declaration would be
   * undownloadable.
   *
   * <p>Distinct from `pdfMutation` above in what it renders: this asks the server for the SAVED
   * row, so HR's ข้อ 13 figure and the original submission date come out, not today's draft.
   * `TaxAllowanceDeclarationService#renderLorYor01` gates it with `requireOwnerOrHr`, so the owning
   * employee may pull their own at any status (verified against the Java service; the mock's
   * handler is a "not supported in mock mode" stub).
   */
  const downloadFiledFormMutation = useMutation({
    mutationFn: (declarationId) => api.payroll.downloadTaxAllowanceForm(declarationId),
    onSuccess: (blob) => {
      saveBlob(blob, `loryor01-${taxYear}.pdf`);
      showToast?.('success', 'ดาวน์โหลดแบบ ล.ย.01 ที่ยื่นไว้แล้ว');
    },
    onError: (error) => showToast?.('error', error?.message || 'ดาวน์โหลดแบบ ล.ย.01 ไม่สำเร็จ'),
  });

  function handleGeneratePdf(values) {
    if (!values) return;
    pdfMutation.mutate(buildAllowanceSubmitBody(values, {
      taxYear,
      documentReference: values.documentReference,
    }));
  }

  const submitMutation = useMutation({
    mutationFn: (body) => api.payroll.submitMyTaxAllowanceDeclaration(body),
    onSuccess: async (created) => {
      queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceDeclarationsMe(taxYear) });
      showToast?.('success', 'ยื่นแบบแจ้งเรียบร้อย รอ HR ตรวจสอบ');
      // Closes the confirmation. Deliberately here and in onError rather than immediately after `mutate`, so the
      // dialog stays up showing its busy state for the whole round trip — that is also what stops a second confirm
      // click firing a duplicate filing.
      setPendingSubmitValues(null);
      setEditing(false);
      // Land back on the hub -- REVIEW's own submit button is about to disappear under the now-PENDING declaration's
      // `readOnly`, and leaving the employee stranded there with the control they just used suddenly gone would read
      // as broken, not successful.
      await flushStagedEvidence(created.declarationId);
    },
    onError: (error) => {
      // The dialog closes on failure too, and the form stays editable underneath with everything still typed — the
      // employee can fix whatever the server objected to and file again.
      setPendingSubmitValues(null);
      showToast?.('error', error.message || 'ยื่นแบบแจ้งไม่สำเร็จ');
    },
  });

  // Sends every staged file (see the `stagedEvidence` state's own comment above) against the declarationId the
  // submit above JUST created -- the earliest point a real one exists.
  async function flushStagedEvidence(declarationId) {
    const entries = Object.entries(stagedEvidence).flatMap(([key, files]) =>
      files.map((item) => ({ sectionKey: key === UNCATEGORIZED_EVIDENCE_KEY ? null : key, item })));
    if (entries.length === 0) return;
    let failureCount = 0;
    for (const { sectionKey, item } of entries) {
      try {
        // Sequential by design, see the comment above -- no-await-in-loop isn't enabled in this project's ESLint
        // config, so no disable directive is needed here.
        await api.payroll.uploadTaxAllowanceAttachment(declarationId, item.file, sectionKey ?? undefined);
      } catch {
        failureCount += 1;
      }
    }
    setStagedEvidence({});
    queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceAttachments(declarationId) });
    if (failureCount > 0) {
      showToast?.('error', `แนบหลักฐานไม่สำเร็จ ${failureCount} ไฟล์ — ยื่นแบบแจ้งสำเร็จแล้ว กรุณาแนบไฟล์ที่เหลือใหม่อีกครั้ง`);
    } else {
      showToast?.('success', 'แนบหลักฐานที่เตรียมไว้เรียบร้อย');
    }
  }

  // DELETE /declarations/{id}: without it a PENDING declaration is a dead end (read-only here, and
  // only HR rejecting it releases the employee). Ownership is enforced server-side: a foreign id
  // 404s rather than 403s (TaxAllowanceDeclarationService#withdrawOwn), covered by
  // TaxAllowanceDeclarationScopeIntegrationTest#employeeCannotWithdrawAnotherEmployeesDeclaration...
  const withdrawMutation = useMutation({
    mutationFn: (id) => api.payroll.withdrawMyTaxAllowanceDeclaration(id),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: queryKeys.taxAllowanceDeclarationsMe(taxYear) });
      showToast?.('success', 'ยกเลิกการยื่นแล้ว แก้ไขและยื่นใหม่ได้');
      setWithdrawing(false);
      setEditing(true);
    },
    onError: (error) => {
      showToast?.('error', error.message || 'ยกเลิกการยื่นไม่สำเร็จ');
      setWithdrawing(false);
    },
  });

  /**
   * The submit event does NOT file anything: it opens the confirmation. Once the signed scan is
   * staged the submit button is enabled, and HTML's implicit submission gives Enter in any of the
   * form's text inputs a real `event.submitter`, so `SafeForm`'s two guards both pass and Enter
   * would file the declaration outright. The guard is not Enter-specific: putting the dialog
   * between the submit EVENT and the mutation covers every trigger (Enter, click, anything added
   * later). It also matches this page's other confirms (withdrawing, a year switch that discards a
   * draft); filing is the most consequential action here and cannot be undone without HR.
   *
   * `SafeForm`'s own guards are untouched; they still cover the disabled-submit state.
   */
  function handleSubmit(values) {
    setPendingSubmitValues(values);
  }

  function confirmSubmit() {
    if (!pendingSubmitValues) return;
    // No `employeeId` field, ever: the server resolves the caller from the session.
    const body = buildAllowanceSubmitBody(pendingSubmitValues, { taxYear });
    submitMutation.mutate(body);
  }

  // ONE status region (four conditional banners folded into one): the status badge, a plain-language
  // explanation of what that status means now, and the single next action. "Single" holds by
  // construction: withdrawal and edit are mutually exclusive (PENDING is never in
  // EDITABLE_STATUS_KEYS, so `canStartEdit` is false exactly when the withdraw action applies).
  const statusExplanation = !isCurrentYear
    ? `กำลังดูข้อมูลย้อนหลังของปีภาษี ${taxYear} — ยื่นหรือแก้ไขได้เฉพาะปีภาษี ${currentYear}`
    : STATUS_EXPLANATIONS[statusInfo.key] ?? null;

  // One string for the form's submit button AND the confirmation's confirm button. Extracted so the dialog
  // cannot end up promising a different action from the control that opened it.
  const submitLabel = statusInfo.key === 'NONE' ? 'ยื่นแบบแจ้ง' : 'ยื่นฉบับใหม่';

  /**
   * Leaving a revision without filing it: "ยื่นฉบับแก้ไข" would otherwise be a one-way door out of
   * the view of a declaration that is applying to payroll, with no way back except a reload.
   * Nothing server-side has changed at this point, so this only puts the screen back, confirming
   * first when there is unsaved work (the same guard the year switch uses).
   */
  function requestCancelRevision() {
    if (formDirty || hasStagedEvidence) setCancellingRevision(true);
    else setEditing(false);
  }

  function confirmCancelRevision() {
    setStagedEvidence({});
    setEditing(false);
    setCancellingRevision(false);
  }

  const statusAction = statusInfo.key === 'PENDING' && isCurrentYear
    ? { label: 'ยกเลิกการยื่น', variant: 'danger', onClick: () => setWithdrawing(true) }
    // A revision in progress owns the slot: the way OUT of it is the only action that still makes sense there, and
    // it keeps the region's one-action rule intact.
    : (editing && canRevise
      ? { label: 'ยกเลิกการแก้ไข', variant: 'secondary', onClick: requestCancelRevision }
      : (!editing && (canStartEdit || canRevise)
        ? {
          // Different words for a different act. "แก้ไข / ยื่นฉบับใหม่" answers a rejected or expired filing; this one
          // replaces a declaration that is already in force, and the label should not pretend those are the same thing.
          label: canRevise ? 'ยื่นฉบับแก้ไข' : 'แก้ไข / ยื่นฉบับใหม่',
          variant: 'secondary',
          onClick: () => setEditing(true),
        }
        : null));

  return (
    <PageStack>
      <PageHeader
        title="ค่าลดหย่อนภาษี (แบบ ล.ย.01)"
        subtitle="แจ้งรายการเพื่อการหักลดหย่อนภาษี พร้อมแนบสำเนาหลักฐานแสดงสิทธิ"
        actions={(
          <FormField label="ปีภาษี" htmlFor="tax-allowance-year">
            <select
              id="tax-allowance-year"
              value={taxYear}
              onChange={(event) => requestYearChange(Number(event.target.value))}
            >
              {yearOptions.map((year) => (
                <option key={year} value={year}>ปีภาษี {year}</option>
              ))}
            </select>
          </FormField>
        )}
      />

      <div className="grid gap-2 rounded-md border border-border bg-surface-subtle p-4">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex flex-wrap items-center gap-2">
            <StatusBadge tone={statusInfo.tone}>{statusInfo.label}</StatusBadge>
            {statusExplanation ? <span className="text-sm text-text-muted">{statusExplanation}</span> : null}
          </div>
          {statusAction ? (
            <Button
              type="button"
              variant={statusAction.variant}
              className="mobile:w-full"
              onClick={statusAction.onClick}
            >
              {statusAction.label}
            </Button>
          ) : null}
        </div>
        {/* The rejection reason folds into this same region as an extra line: one status region,
            not a status region plus a second panel that only sometimes appears. */}
        {statusInfo.key === 'REJECTED' && statusInfo.note ? (
          <p className="m-0 text-sm font-bold text-danger">เหตุผลที่ปฏิเสธ: {statusInfo.note}</p>
        ) : null}
      </div>

      {/* No `title` here: it would repeat the PageHeader above, and TaxAllowanceForm's own
          HUB/SECTION/REVIEW heading would repeat it a third time. The Panel stays: the form
          still earns a bordered card. */}
      <Panel>
        <TaxAllowanceForm
          caps={caps}
          defaultValues={defaultValues}
          readOnly={!editing}
          submitting={submitMutation.isPending}
          submitLabel={submitLabel}
          onSubmit={handleSubmit}
          onDirtyChange={setFormDirty}
          onGeneratePdf={handleGeneratePdf}
          generatingPdf={pdfMutation.isPending}
          // Only offered once a declaration actually exists to fetch — there is no saved row to ask the server for on a
          // year that was never filed.
          onDownloadFiledForm={current?.declarationId
            ? () => downloadFiledFormMutation.mutate(current.declarationId)
            : undefined}
          downloadingFiledForm={downloadFiledFormMutation.isPending}
          signedFormAttached={signedFormAttached}
          evidenceMode={evidenceMode}
          evidenceDeclarationId={current?.declarationId ?? null}
          stagedEvidenceBySection={stagedEvidence}
          onStageEvidence={stageEvidence}
          onUnstageEvidence={unstageEvidence}
          showToast={showToast}
        />
      </Panel>

      {/* Filing is the one action on this page that HR has to undo for you, so it asks first. The
          copy states the two consequences plainly rather than asking "are you sure": the
          declaration goes to HR, and the form locks until it is withdrawn. */}
      <ConfirmDialog
        open={pendingSubmitValues != null}
        title={canRevise ? 'ยื่นแบบแจ้ง ล.ย.01 ฉบับแก้ไข' : 'ยื่นแบบแจ้ง ล.ย.01'}
        // A revision needs its own sentence, because the consequence differs in the way that worries people: the
        // declaration already applying to their payroll is NOT switched off by filing this.
        message={canRevise
          ? 'แบบแจ้งฉบับแก้ไขจะถูกส่งให้ฝ่ายบุคคลตรวจสอบ ฉบับที่ใช้อยู่ปัจจุบันจะยังมีผลกับเงินเดือนต่อไปจนกว่าฝ่ายบุคคลจะอนุมัติฉบับใหม่ ต้องการยื่นหรือไม่'
          : 'แบบแจ้งฉบับนี้จะถูกส่งให้ฝ่ายบุคคลตรวจสอบ และแบบฟอร์มจะถูกล็อกไม่ให้แก้ไขจนกว่าจะยกเลิกการยื่น ต้องการยื่นหรือไม่'}
        confirmLabel={submitLabel}
        cancelLabel="ตรวจทานอีกครั้ง"
        busy={submitMutation.isPending}
        onConfirm={confirmSubmit}
        onCancel={() => setPendingSubmitValues(null)}
      />

      <ConfirmDialog
        open={cancellingRevision}
        title="ยกเลิกการแก้ไข"
        message="ข้อมูลที่แก้ไขไว้และไฟล์ที่เตรียมแนบจะถูกละทิ้ง แบบแจ้งฉบับที่ยื่นไว้เดิมจะยังคงอยู่และมีผลตามเดิม ต้องการยกเลิกการแก้ไขหรือไม่"
        tone="danger"
        confirmLabel="ยกเลิกการแก้ไข"
        cancelLabel="กลับไปแก้ไขต่อ"
        onConfirm={confirmCancelRevision}
        onCancel={() => setCancellingRevision(false)}
      />

      <ConfirmDialog
        open={withdrawing}
        title="ยกเลิกการยื่นแบบแจ้ง ล.ย.01"
        message="แบบแจ้งฉบับนี้จะถูกยกเลิก และกลับมาแก้ไขเพื่อยื่นใหม่ได้ หลักฐานที่แนบไว้จะยังอยู่กับฉบับที่ยกเลิก"
        tone="danger"
        confirmLabel="ยกเลิกการยื่น"
        cancelLabel="ไม่ใช่ตอนนี้"
        busy={withdrawMutation.isPending}
        onConfirm={() => withdrawMutation.mutate(current.declarationId)}
        onCancel={() => setWithdrawing(false)}
      />

      <ConfirmDialog
        open={pendingYearChange != null}
        title="เปลี่ยนปีภาษี"
        message={pendingYearChange != null
          ? `การเปลี่ยนไปปีภาษี ${pendingYearChange} จะละทิ้งข้อมูลที่กรอกไว้และไฟล์แนบที่เตรียมไว้สำหรับปีนี้ซึ่งยังไม่ได้ยื่น ต้องการดำเนินการต่อหรือไม่`
          : ''}
        tone="danger"
        confirmLabel="ยืนยันเปลี่ยนปีภาษี"
        cancelLabel="ไม่ใช่ตอนนี้"
        onConfirm={confirmYearChange}
        onCancel={() => setPendingYearChange(null)}
      />
    </PageStack>
  );
}
