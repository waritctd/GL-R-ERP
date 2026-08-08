import { useEffect, useMemo, useRef } from 'react';
import { zodResolver } from '@hookform/resolvers/zod';
import { useForm, useWatch } from 'react-hook-form';
import { z } from 'zod';
import { Button } from '../../components/common/Button.jsx';
import { CollapsibleSection } from '../../components/common/CollapsibleSection.jsx';
import { FormField } from '../../components/common/FormField.jsx';
import { Icon } from '../../components/common/Icon.jsx';
import { InfoTip } from '../../components/common/InfoTip.jsx';
import { FormGrid, formGridSpan2 } from '../../components/common/Layout.jsx';
import { QuotaBar } from '../../components/common/QuotaBar.jsx';
import { SafeForm } from '../../components/common/SafeForm.jsx';
import { formatMoney } from '../../utils/format.js';
import {
  ALLOWANCE_COUNT_KEYS,
  ALLOWANCE_MONEY_KEYS,
  AUTO_GRANTED_ROWS,
  LAW_SOURCES,
  TAX_ALLOWANCE_GROUPS,
  declaredAllowanceTotalFromValues,
  groupDeclaredTotal,
  groupHasValue,
  UNCATEGORIZED_EVIDENCE_KEY,
} from './taxAllowanceSchema.js';
import { capMapFrom, computeGroupUsage, fieldCapCaption, isCapUnavailableThisYear } from './taxAllowanceCaps.js';
import { TaxAllowanceEvidencePanel } from './TaxAllowanceEvidencePanel.jsx';

const MONTH_OPTIONS = Array.from({ length: 12 }, (_, index) => index + 1);

function allowanceZodSchema() {
  const shape = {};
  for (const key of ALLOWANCE_MONEY_KEYS) {
    shape[key] = z.coerce.number({ invalid_type_error: 'กรุณาระบุตัวเลข' }).min(0, 'ต้องไม่ติดลบ');
  }
  for (const key of ALLOWANCE_COUNT_KEYS) {
    shape[key] = z.coerce.number({ invalid_type_error: 'กรุณาระบุตัวเลข' }).min(0, 'ต้องไม่ติดลบ').int('ต้องเป็นจำนวนเต็ม');
  }
  shape.disabilityCardHolder = z.boolean().optional().default(false);
  // The blank "มกราคม (ค่าเริ่มต้น)" <option> submits value="" (ล.ย.01 ข้อ 2.2: no override means
  // January). z.coerce.number() on "" coerces to 0, which then failed .min(1) on every submit with
  // the blank option selected — react-hook-form silently focused this field with no rendered error
  // (FormField never receives one here), so the whole form looked broken with no feedback at all.
  // Preprocess "" (and null/undefined) to null before the number check runs instead.
  shape.effectiveMonth = z.preprocess(
    (value) => (value === '' || value === null || value === undefined ? null : value),
    z.coerce.number().min(1).max(12).nullable().optional(),
  );
  shape.documentReference = z.string().optional();
  return z.object(shape);
}

const SCHEMA = allowanceZodSchema();

function MoneyField({ id, label, hint, error, disabled, register, required }) {
  return (
    <FormField label={label} htmlFor={id} hint={hint} error={error} required={required}>
      <span className="relative block">
        <span className="currency-input-symbol absolute left-3 top-1/2 z-[1] text-text-muted font-black leading-none pointer-events-none -translate-y-1/2" aria-hidden="true">฿</span>
        <input
          id={id}
          type="number"
          inputMode="decimal"
          min="0"
          step="0.01"
          placeholder="0.00"
          disabled={disabled}
          {...register}
          className="pl-[34px]"
        />
      </span>
    </FormField>
  );
}

function CountField({ id, label, unit, hint, error, disabled, register }) {
  return (
    <FormField label={unit ? `${label} (${unit})` : label} htmlFor={id} hint={hint} error={error}>
      <input id={id} type="number" inputMode="numeric" min="0" step="1" placeholder="0" disabled={disabled} {...register} />
    </FormField>
  );
}

// Compact, REAL link to a field's underlying legal source (taxAllowanceSchema.js's `LAW_SOURCES`,
// via each field's `lawRef`). Deliberately NOT rendered through InfoTip: `.info-tip-bubble` is
// `pointer-events-none` by design (InfoTip.jsx's own className, Group B primitives port; formerly
// styles.css) -- a hover/focus tooltip meant
// for inert text, never something a mouse can reach inside. A clickable law citation living in there
// would be a link nobody could actually click, which is exactly the "label asserts something the
// code doesn't do" defect class this feature exists to avoid, just aimed at a link instead of a
// number. External, so it opens in a new tab and says so both visibly (the icon) and to assistive
// tech (the sr-only suffix) -- CLAUDE.md's "visible indication they leave the app".
function LawRefLink({ lawRef }) {
  if (!lawRef) return null;
  return (
    <a
      href={lawRef.url}
      target="_blank"
      rel="noopener noreferrer"
      className="inline-flex items-center gap-0.5 font-bold text-primary hover:underline"
    >
      {lawRef.label}
      <Icon name="externalLink" size={11} />
      <span className="sr-only"> (เปิดในแท็บใหม่)</span>
    </a>
  );
}

// Folds a field's own plain-text hint (cap caption, usage note) and its `lawRef` link into the one
// node FormField's `hint` slot expects, so a field that already carries a cap caption does not grow
// an extra row just for the citation -- it rides the existing hint line instead. `field.hint`/the
// cap caption stay plain strings (unchanged); only the composition with a lawRef present becomes a
// node. Mirrors `hint || undefined` (not `null`) when there is no lawRef, so FormField's own
// `Boolean(hint)` gate behaves exactly as it did before this feature existed.
function hintWithLawRef(textHint, lawRef) {
  if (!lawRef) return textHint || undefined;
  return (
    <>
      {textHint || null}
      {textHint ? ' ' : null}
      <LawRefLink lawRef={lawRef} />
    </>
  );
}

// The bar itself now lives in components/common/QuotaBar.jsx -- welfare needs the same readout and
// this was the only accessible implementation in the app. Only the ล.ย.01-specific copy and this
// form's grid placement stay here; everything else is the shared component's job.
function GroupUsageBar({ group }) {
  const { label, total, cap } = group;
  return (
    <QuotaBar
      className={`${formGridSpan2}`}
      label={label}
      caption={`${label} ใช้วงเงินรวมกัน ${formatMoney(cap)}`}
      used={total}
      cap={cap}
      overMessage="ยอดที่ยื่นเกินวงเงินรวมของกลุ่มนี้ — ระบบจะยื่นตามยอดที่กรอกจริง แต่ตอนคำนวณภาษี HR จะเห็นยอดที่ตัดตามเพดานจริง"
    />
  );
}

// Every view (HUB/SECTION/REVIEW) renders exactly one of these as its own top heading. `tabIndex={-1}`
// plus the `useEffect` below that focuses it on every `view` change is the accessibility carry-over
// from the old 2-step wizard's `StepHeading` -- a screen-reader user needs an explicit "you have
// landed on a new screen" cue here, because this whole form never unmounts/remounts between views
// (see the component's own javadoc on why the single `useForm` instance must survive navigation), so
// there is no natural DOM-mount event for assistive tech to announce on its own. The
// `ขั้นตอนที่ n/total` counter this replaces is gone along with the linear "step" framing it implied
// -- HUB/SECTION/REVIEW is a hub-and-spoke, not a wizard (#tax-allowance-ia-hub-review) -- but the
// focus behaviour itself is unrelated to that framing and stays exactly as it was.
//
// `<h2>`, not `<h3>`: this was an h3 back when it sat directly under the caller's own `<Panel
// title="...">`, which rendered an `<h2>` (Layout.jsx's `Panel`). Removing that title (the same
// #tax-allowance-ia-hub-review pass, on TaxAllowancePage) took the page's only h2 with it, which
// would otherwise leave the heading tree skipping straight from the PageHeader's `<h1>` to this one.
function ViewHeading({ innerRef, title }) {
  return (
    <h2 ref={innerRef} tabIndex={-1} className="m-0 text-base font-bold text-text focus-visible:outline-none">
      {title}
    </h2>
  );
}

// `groupHasValue` counts a nonzero COUNT field or a ticked checkbox as "something is here" even
// when `groupDeclaredTotal`'s money-only sum is 0 -- e.g. "จำนวนบุตร" filled in with the "บุตร" baht
// amount still at 0. Showing that state as a bare `฿0.00` next to a checkmark reads as "zero baht
// declared", a more misleading lie than the `ยังไม่ได้กรอก` this whole row replaced (review fix,
// #tax-allowance-ia-hub-review): a genuine ฿0 subtotal and "nothing entered yet" would render
// identically. Money still wins once there is any, since a real subtotal is the more useful number.
function groupSecondaryLabel(hasValue, subtotal, fileCount) {
  if (!hasValue) return 'ไม่ได้ประกาศ';
  const fileSuffix = fileCount > 0 ? ` · ${fileCount} ไฟล์` : '';
  if (subtotal > 0) return `${formatMoney(subtotal)}${fileSuffix}`;
  return `มีข้อมูล — ยังไม่ระบุยอดเงิน${fileSuffix}`;
}

// HUB's section-choice row. Secondary line is `groupSecondaryLabel` above: the group's own declared
// subtotal (money fields only -- `groupDeclaredTotal`) plus how many files are staged for it, or
// `ไม่ได้ประกาศ` when `groupHasValue` says nothing in the group -- money, count, or checkbox -- has
// been touched yet. This replaces the old binary `กรอกแล้ว`/`ยังไม่ได้กรอก` readout
// (#tax-allowance-ia-hub-review, defect #3): a legitimately-empty section (no spouse, no children)
// used to read "ยังไม่ได้กรอก" forever, indistinguishable from a section the employee simply hadn't
// gotten to yet. A real number (or its explicit absence) tells the truth either way.
//
// No `selected`/`aria-current` any more: the old version highlighted whichever group had last been
// opened, using a state (`activeGroupKey`) that survived going back to step 1 on purpose. HUB and
// SECTION are now mutually exclusive views of a single `view` prop with no such "last opened"
// memory of its own -- reintroducing it would mean adding state beyond the one URL param this whole
// redesign is built around (#tax-allowance-ia-hub-review's "driven by ONE URL param `?view=`"), for
// a highlight ring that was never asked for here.
function GroupChoice({ group, hasValue, subtotal, fileCount, onSelect }) {
  return (
    <button
      type="button"
      onClick={() => onSelect?.(group.key)}
      className="flex w-full items-center justify-between gap-3 rounded-md border-[1.5px] border-border-input bg-surface px-4 py-3 text-left transition-colors hover:border-primary-hover"
    >
      <span className="grid gap-0.5">
        <span className="text-sm font-bold text-text">{group.title}</span>
        <span className="text-xs text-text-muted">{groupSecondaryLabel(hasValue, subtotal, fileCount)}</span>
      </span>
      {hasValue ? (
        <Icon name="check" size={16} className="shrink-0 text-primary" />
      ) : (
        <Icon name="chevronRight" size={16} className="shrink-0 text-text-muted" />
      )}
    </button>
  );
}

// REVIEW's per-group recap line list -- every field with something declared (the same per-field
// truthiness `groupHasValue` uses: a checkbox counts as declared when checked, a money/count field
// when its value is a positive number), formatted for read-only display. A field with nothing
// declared is left out entirely; the caller renders the `ไม่ได้ประกาศ` fallback for a group whose
// list comes back empty, rather than this returning a placeholder row per untouched field.
function declaredFieldLines(group, values) {
  if (!values) return [];
  const lines = [];
  for (const field of group.fields) {
    const value = values[field.key];
    if (field.kind === 'checkbox') {
      if (value) lines.push({ key: field.key, label: field.label, value: 'มี' });
      continue;
    }
    const amount = Number(value || 0);
    if (amount <= 0) continue;
    const formatted = field.kind === 'money' ? formatMoney(amount) : `${amount}${field.unit ? ` ${field.unit}` : ''}`;
    lines.push({ key: field.key, label: field.label, value: formatted });
  }
  return lines;
}

/**
 * The ล.ย.01 declaration form — shared between the employee's own `/tax-allowance` screen and HR's
 * "ยื่นแทนพนักงาน" (on-behalf) mode on `/tax-allowance-review`. `readOnly` disables every control
 * without changing the DOM structure a screen reader would announce column-drift on.
 *
 * Deliberately does no Thai tax arithmetic: the group-consumption bars below only total what the
 * employee typed against the `groupCap` `/caps` returned (see taxAllowanceCaps.js), and the
 * submitted body carries whatever was declared verbatim — decision #1's "do not pre-clamp the
 * submitted value, the backend clamps".
 *
 * Hub-and-spoke navigation (#tax-allowance-ia-hub-review), replacing the earlier 2-step wizard
 * (#tax-allowance-sections) it grew out of: `sectioned` (default true) renders three views — HUB
 * (declaration-level fields, the auto-granted info, all five TAX_ALLOWANCE_GROUPS as rows showing
 * what has been declared so far, and a running total), SECTION (one group's own fields plus its
 * evidence panel), and REVIEW (every group's declared lines, the total again, an evidence manifest,
 * and the ONLY submit button in the entire flow) — selected by the controlled `view` prop (`'hub'` |
 * a TAX_ALLOWANCE_GROUPS key | `'review'`) rather than internal state; the PAGE owns `?view=` in the
 * URL and passes it straight through via `view`/`onViewChange`. This replaces the old linear
 * `ขั้นตอนที่ 1/2`/`2/2` framing, which mislabelled what was always a hub-and-spoke (five
 * independent, individually-optional sections, not a sequence) and put the SAME submit control on
 * both steps — on step 2 it sat beside `ย้อนกลับ` and read as "save this section and continue" but
 * actually submitted the whole declaration, so an employee who filled in one section and pressed the
 * obvious button had filed an incomplete ล.ย.01 with no way back short of discovering
 * `ยกเลิกการยื่น`. SECTION now has no submit control at all — only REVIEW does.
 *
 * The single `react-hook-form` instance below is never remounted or reset between views, so values
 * already typed into another section survive navigating away and back — see
 * `TaxAllowanceForm.test.jsx`'s "values survive navigating between views" case for the explicit
 * A→B→back-to-A proof, and its "SECTION view has no submit control" case for the defect this whole
 * change exists to fix.
 *
 * `sectioned={false}` keeps the ORIGINAL "every group expanded via CollapsibleSection" layout,
 * unchanged — TaxAllowanceReviewPage's `OnBehalfModal` opts into this: it is a small modal for a
 * one-off HR-on-behalf entry (no evidence, no returning-user "which section did I start" need), and
 * turning it into a hub-and-spoke too would be an unrequested UX change to a flow this task was
 * never asked to touch.
 */
export function TaxAllowanceForm({
  caps = [],
  defaultValues,
  readOnly = false,
  submitting = false,
  submitLabel = 'ยื่นแบบแจ้ง',
  onSubmit,
  formId = 'tax-allowance-form',
  footer,
  sectioned = true,
  // Controlled view state (#tax-allowance-ia-hub-review): the PAGE owns `?view=` in the URL and
  // passes it straight through -- this component holds no navigation state of its own beyond the
  // single `useForm` instance below. `'hub'` | one of TAX_ALLOWANCE_GROUPS' keys | `'review'`.
  // Only consulted when `sectioned` is true; the `sectioned={false}` branch below never reads it.
  view = 'hub',
  onViewChange,
  // Fires with `formState.isDirty` on every change so the PAGE can guard a destructive action (a
  // year switch) that would otherwise silently discard unsaved edits underneath this form.
  onDirtyChange,
  // Evidence panel (only rendered when sectioned && evidenceMode is provided — TaxAllowanceReviewPage's
  // OnBehalfModal passes none of these, so it never renders one; see the component javadoc above).
  evidenceMode, // 'direct' | 'staging' | 'readonly' | undefined
  evidenceDeclarationId = null,
  stagedEvidenceBySection = {},
  onStageEvidence,
  onUnstageEvidence,
  showToast,
}) {
  const {
    register,
    handleSubmit,
    reset,
    control,
    formState: { errors, isDirty },
  } = useForm({
    resolver: zodResolver(SCHEMA),
    defaultValues,
  });

  // Only one heading is ever mounted at a time -- HUB/SECTION/REVIEW render mutually exclusively
  // below -- so all three `ViewHeading`s below safely share this single ref instead of needing one
  // each. React attaches the new view's heading node to it in the same commit that detaches the old
  // one, which happens before this passive effect runs, so `.current` is always the CURRENT view's
  // heading by the time it fires.
  const headingRef = useRef(null);

  useEffect(() => {
    // A new declaration/year loaded underneath this form -- reset to its values. Which VIEW the
    // employee lands on for that new declaration is the PAGE's call now (it owns `?view=` and
    // resets it alongside the year on every switch -- see TaxAllowancePage's `requestYearChange`),
    // not this component's -- there is no local step/view state left here to reset.
    reset(defaultValues);
  }, [defaultValues, reset]);

  useEffect(() => {
    onDirtyChange?.(isDirty);
  }, [isDirty, onDirtyChange]);

  const watchedValues = useWatch({ control });

  const capByCategory = useMemo(() => capMapFrom(caps), [caps]);
  const groupUsage = useMemo(() => computeGroupUsage(caps, watchedValues || {}), [caps, watchedValues]);
  const groupUsageById = useMemo(() => new Map(groupUsage.map((group) => [group.groupId, group])), [groupUsage]);

  useEffect(() => {
    headingRef.current?.focus();
  }, [view]);

  const activeGroup = useMemo(
    () => TAX_ALLOWANCE_GROUPS.find((group) => group.key === view) ?? null,
    [view],
  );

  function submit(values) {
    onSubmit?.(values);
  }

  function renderGroupFields(group) {
    const barGroup = group.groupCapId ? groupUsageById.get(group.groupCapId) : null;
    return (
      <FormGrid>
        {group.fields.map((field) => {
          const cap = field.capCategory ? capByCategory.get(field.capCategory) : null;
          const unavailable = isCapUnavailableThisYear(cap);
          const fieldError = errors[field.key]?.message;
          if (field.kind === 'money') {
            const textHint = unavailable
              ? 'ไม่สามารถหักลดหย่อนได้ตั้งแต่ปีภาษีนี้เป็นต้นไปตามกฎหมาย'
              : [fieldCapCaption(cap), field.hint].filter(Boolean).join(' · ') || null;
            return (
              <MoneyField
                key={field.key}
                id={`ta-${field.key}`}
                label={field.label}
                hint={hintWithLawRef(textHint, field.lawRef)}
                error={fieldError}
                disabled={readOnly || unavailable}
                register={register(field.key)}
              />
            );
          }
          if (field.kind === 'count') {
            return (
              <CountField
                key={field.key}
                id={`ta-${field.key}`}
                label={field.label}
                unit={field.unit}
                hint={hintWithLawRef(field.hint, field.lawRef)}
                error={fieldError}
                disabled={readOnly}
                register={register(field.key)}
              />
            );
          }
          return (
            // `flex flex-wrap`, not `inline-flex` (review fix, feat/tax-allowance-law-references):
            // with a single non-wrapping row, a long label (this field's is 35 characters) left no
            // room for the InfoTip + law-ref link, which then squeezed into a narrow wrapped column
            // of their own -- "ประมวลรัษฎากร" itself splitting across lines at 360px. Wrapping lets
            // the {InfoTip, link} pair drop to their own line under the label instead, same
            // information, no squeeze. They stay grouped in their own inline-flex span so the two
            // wrap together as one unit rather than independently.
            <label key={field.key} htmlFor={`ta-${field.key}`} className={`${formGridSpan2} flex flex-wrap items-center gap-x-2 gap-y-1`}>
              <input id={`ta-${field.key}`} type="checkbox" disabled={readOnly} {...register(field.key)} />
              <span className="min-w-0">{field.label}</span>
              <span className="inline-flex items-center gap-1.5">
                {field.hint ? <InfoTip label={field.label} text={field.hint} /> : null}
                <LawRefLink lawRef={field.lawRef} />
              </span>
            </label>
          );
        })}
        {barGroup ? <GroupUsageBar group={barGroup} /> : null}
      </FormGrid>
    );
  }

  const autoGrantedInfo = (
    <div className="rounded-md border border-border-subtle bg-surface-subtle p-3">
      <p className="m-0 mb-2 text-xs font-extrabold uppercase tracking-wide text-text-muted">ได้รับอัตโนมัติ ไม่ต้องยื่น</p>
      <div className="grid gap-1.5">
        {AUTO_GRANTED_ROWS.map((row) => {
          const cap = row.capCategory ? capByCategory.get(row.capCategory) : null;
          return (
            <div key={row.key} className="flex items-center justify-between gap-3 text-sm">
              <span className="inline-flex flex-wrap items-center gap-1.5 text-text-muted">
                {row.label}
                <LawRefLink lawRef={row.lawRef} />
              </span>
              <strong className="shrink-0 text-text">{cap ? formatMoney(cap.ownCap) : (row.note ?? '-')}</strong>
            </div>
          );
        })}
      </div>
    </div>
  );

  // Reference material for the whole ล.ย.01 flow -- the verified rd.go.th sources `lawRef` above
  // points at, listed ONCE each (`Object.values(LAW_SOURCES)` -- a plain object is de-duplicated by
  // construction, so this can never repeat a source the way iterating every field's own `lawRef`
  // would) regardless of how many fields cite the same one, plus the two sources
  // (`yearSummary`/`formPdf`) that are not any single field's citation but are still worth a reader
  // having on hand. HUB only, not SECTION/REVIEW -- this is background material, not something to
  // repeat on every screen. Deliberately quiet (muted colors, small type, last thing on the view,
  // after the primary "ตรวจทานและยื่น" action below): a reader who wants it can find it, but it never
  // competes with the actual task of filling in the form. `caveat` is folded into the same muted
  // description line as `what` (not a separate loud callout) -- honesty here means the reader can
  // see the limitation, not that it needs to shout; the full reasoning for each caveat lives in
  // taxAllowanceSchema.js's own comments for whoever next edits a label.
  const lawReferences = (
    <div className="grid gap-2.5 rounded-md border border-border-subtle bg-surface-subtle p-3">
      <p className="m-0 text-2xs font-extrabold uppercase tracking-wide text-text-muted">แหล่งอ้างอิงทางกฎหมาย</p>
      <div className="grid gap-3">
        {Object.values(LAW_SOURCES).map((source) => (
          <div key={source.url} className="grid gap-0.5">
            <a
              href={source.url}
              target="_blank"
              rel="noopener noreferrer"
              className="inline-flex w-fit items-center gap-1 text-xs font-bold text-primary hover:underline"
            >
              {source.label} — กรมสรรพากร
              <Icon name="externalLink" size={11} />
              <span className="sr-only"> (เปิดในแท็บใหม่)</span>
            </a>
            {source.vintage ? (
              <p className="m-0 text-2xs font-bold text-text-muted">{source.vintage}</p>
            ) : null}
            <p className="m-0 text-2xs text-text-muted">
              {source.what}
              {source.caveat ? ` — ${source.caveat}` : ''}
            </p>
          </div>
        ))}
      </div>
    </div>
  );

  const declarationFields = (
    <FormGrid>
      <FormField
        label="มีผลตั้งแต่งวดเดือน"
        htmlFor="ta-effective-month"
        hint="เว้นว่างไว้ = มีผลตั้งแต่เดือนมกราคม"
        error={errors.effectiveMonth?.message}
      >
        <select id="ta-effective-month" disabled={readOnly} {...register('effectiveMonth')}>
          <option value="">มกราคม (ค่าเริ่มต้น)</option>
          {MONTH_OPTIONS.map((month) => (
            <option key={month} value={month}>เดือน {month}</option>
          ))}
        </select>
      </FormField>
      <FormField label="เลขที่เอกสารอ้างอิง (ถ้ามี)" htmlFor="ta-document-reference">
        <input id="ta-document-reference" type="text" disabled={readOnly} placeholder="เช่น เลขที่ ล.ย.01 กระดาษ" {...register('documentReference')} />
      </FormField>
    </FormGrid>
  );

  const submitFooter = footer ?? (readOnly ? null : (
    <div className="flex justify-end">
      <Button type="submit" variant="primary" loading={submitting} disabled={submitting}>
        {submitLabel}
      </Button>
    </div>
  ));

  if (!sectioned) {
    return (
      <SafeForm id={formId} noValidate onSubmit={handleSubmit(submit)} className="grid gap-4">
        {autoGrantedInfo}
        {declarationFields}
        {TAX_ALLOWANCE_GROUPS.map((group) => (
          <CollapsibleSection key={group.key} title={group.title} defaultOpen>
            {renderGroupFields(group)}
          </CollapsibleSection>
        ))}
        {submitFooter}
      </SafeForm>
    );
  }

  const isReview = view === 'review';
  const isSection = !isReview && !!activeGroup;
  // Also the safety net for an unrecognised `view`: TaxAllowancePage validates `?view=` against the
  // five group keys plus `'review'` before it ever reaches this prop, so this branch should never
  // actually catch a garbage value in production -- but a component with an external prop contract
  // should not blank-render or throw if a future caller passes one anyway.
  const isHub = !isReview && !isSection;

  const declaredTotal = formatMoney(declaredAllowanceTotalFromValues(watchedValues));

  // CRITICAL (review fix, #tax-allowance-ia-hub-review; migrated to SafeForm's `canSubmit` under
  // #safe-form-primitive): one <form> wraps all three views below, and HTML's implicit-submission
  // rule fires a real 'submit' event on Enter in a text field whenever the form has no visible
  // submit button but has exactly ONE field that counts toward that rule -- true for HUB (only
  // `#ta-document-reference` is a bare text input; the month <select> and file inputs don't count)
  // and true for the `housing` SECTION (its one and only field). The other four sections were safe
  // only by ACCIDENT, because they happened to carry more than one such field -- not something to
  // rely on, which is the whole reason this gate is now `canSubmit` on the shared primitive instead
  // of a bespoke handler here. `canSubmit={isReview}` narrows submission to REVIEW; SafeForm's own
  // submitter guard then applies ON TOP of it, because `canSubmit` is a RESTRICTION and never a
  // permission (see SafeForm.jsx's header). Both must pass.
  //
  // That AND matters specifically here, and this form is why the primitive works that way. An
  // earlier version let `canSubmit === true` bypass the submitter check, justified as "REVIEW
  // always has a real submit button whenever `!readOnly`". Read `submitFooter` below: it is
  // `readOnly ? null : <Button type="submit">`, and `?view=review` is an advertised shareable deep
  // link that works read-only (`readOnly={!editing}` on TaxAllowancePage). So `canSubmit === true`
  // with NO submit button in the DOM is reachable today. It does not fire only because REVIEW
  // renders no input at all -- i.e. the form was safe by a DIFFERENT accident than the one the
  // justification named, which is the precise failure mode this whole primitive exists to delete.
  // Add one field to REVIEW and, under the old bypass, Enter would have filed the declaration.
  //
  // Before either gate existed, Enter in HUB or `housing` filed the ENTIRE
  // declaration with no button ever pressed, silently, in a real browser (confirmed in Chromium;
  // jsdom does not implement implicit submission at all, which is why the suite was green with the
  // bug live -- see `TaxAllowanceForm.test.jsx`'s `fireEvent.submit(form)` cases, which exercise the
  // same 'submit' event a real Enter-key trigger produces without depending on jsdom modelling the
  // browser's implicit-submission heuristics). The `sectioned={false}` branch above is untouched
  // apart from the same SafeForm swap -- it has no views/URL, so no `canSubmit` is passed there and
  // SafeForm's default submitter guard applies on its own (new protection, not a relocated one: that
  // branch never had an explicit gate of its own before).
  return (
    <SafeForm id={formId} noValidate onSubmit={handleSubmit(submit)} canSubmit={isReview} className="grid gap-4">
      {isHub ? (
        <div className="grid gap-4">
          <ViewHeading innerRef={headingRef} title="ภาพรวมการยื่นแบบแจ้ง" />
          <div className="grid gap-2">
            <p className="m-0 text-xs font-extrabold uppercase tracking-wide text-text-muted">ข้อมูลแบบแจ้ง</p>
            {declarationFields}
          </div>
          {autoGrantedInfo}
          <div className="grid gap-2">
            {TAX_ALLOWANCE_GROUPS.map((group) => {
              const hasValue = groupHasValue(group, watchedValues);
              return (
                <GroupChoice
                  key={group.key}
                  group={group}
                  hasValue={hasValue}
                  subtotal={groupDeclaredTotal(group, watchedValues)}
                  fileCount={(stagedEvidenceBySection?.[group.key] ?? []).length}
                  onSelect={onViewChange}
                />
              );
            })}
          </div>
          <div className="flex items-center justify-between gap-3 rounded-md border border-border-subtle bg-surface-subtle p-3">
            <span className="text-sm font-bold text-text">รวมค่าลดหย่อนที่ประกาศ</span>
            <strong className="font-mono text-base text-text">{declaredTotal}</strong>
          </div>
          {evidenceMode ? (
            <TaxAllowanceEvidencePanel
              mode={evidenceMode}
              declarationId={evidenceDeclarationId}
              sectionKey={null}
              showUncategorized
              staged={stagedEvidenceBySection?.[UNCATEGORIZED_EVIDENCE_KEY] ?? []}
              onStageFile={onStageEvidence ? (file) => onStageEvidence(null, file) : undefined}
              onUnstageFile={onUnstageEvidence ? (tempId) => onUnstageEvidence(null, tempId) : undefined}
              showToast={showToast}
              title="หลักฐานทั่วไป (ไม่ได้ระบุหมวด)"
              emptyLabel="ยังไม่มีไฟล์แนบทั่วไป — แนบไฟล์เฉพาะหมวดได้หลังเลือกหมวดด้านบน"
            />
          ) : null}
          {/* Hidden read-only, same as the OLD submitFooter's `readOnly ? null : …` rule -- this
              button's only job is to lead toward the submit path, and REVIEW's own submit control
              already disappears under `readOnly` (via `submitFooter` below), so leaving this visible
              would open a door to a screen with nothing behind it. Deep-linking straight to
              `?view=review` still works read-only (for browsing a decided declaration's recap) --
              this only removes the HUB-initiated entry point. */}
          {!readOnly ? (
            <div className="flex justify-end">
              <Button type="button" variant="primary" onClick={() => onViewChange?.('review')}>
                ตรวจทานและยื่น
              </Button>
            </div>
          ) : null}
          {lawReferences}
        </div>
      ) : null}

      {isSection ? (
        <div className="grid gap-4">
          <ViewHeading innerRef={headingRef} title={activeGroup.title} />
          {renderGroupFields(activeGroup)}
          {evidenceMode ? (
            <TaxAllowanceEvidencePanel
              mode={evidenceMode}
              declarationId={evidenceDeclarationId}
              sectionKey={activeGroup.key}
              showUncategorized={false}
              staged={stagedEvidenceBySection?.[activeGroup.key] ?? []}
              onStageFile={onStageEvidence ? (file) => onStageEvidence(activeGroup.key, file) : undefined}
              onUnstageFile={onUnstageEvidence ? (tempId) => onUnstageEvidence(activeGroup.key, tempId) : undefined}
              showToast={showToast}
              title={`หลักฐานแสดงสิทธิ — ${activeGroup.title}`}
            />
          ) : null}
          {/* No submit control here, ever -- this is #tax-allowance-ia-hub-review's whole point.
              The OLD step 2 put the real submit button right beside `ย้อนกลับ`, where it read as
              "save this section and continue" but actually submitted the ENTIRE declaration -- see
              the component javadoc above. `type="button"`, never `type="submit"`, so a click can
              never fire the form's onSubmit -- and the form-level `onSubmit` gate below additionally
              refuses to submit from this view at all, so even Enter-key implicit submission is inert
              here (review fix: it was NOT inert before, see that gate's own comment).
              `secondary`, not `primary`: this button's whole job is to look like plain navigation,
              not a completion action -- the one and only primary "commit" affordance in this entire
              flow lives on REVIEW's submit button below.
              Labelled "กลับไปหน้ารวม" ("back to the overview"), not "บันทึก..." ("save..."): nothing
              is actually saved anywhere by pressing this -- there is no draft endpoint, values live
              only in this component's in-memory form state and are gone on reload. "บันทึกและกลับ"
              promised persistence this button cannot deliver, which is the exact category of false
              promise the rest of this redesign exists to remove (review fix). REVIEW's own back
              button uses the equally honest "ย้อนกลับ", for the same reason. */}
          <div className="flex justify-end">
            <Button type="button" variant="secondary" onClick={() => onViewChange?.('hub')}>
              <Icon name="chevronLeft" size={14} />
              กลับไปหน้ารวม
            </Button>
          </div>
        </div>
      ) : null}

      {isReview ? (
        <div className="grid gap-4">
          <ViewHeading innerRef={headingRef} title="ตรวจทานก่อนยื่น" />
          {/* Declaration-level fields (มีผลตั้งแต่งวดเดือน / เลขที่เอกสารอ้างอิง) are part of the
              submitted body (`buildAllowanceSubmitBody`) exactly like every group's own fields, but
              live only on HUB -- without this block REVIEW recapped everything EXCEPT two of the
              values it is about to file (review fix). Same "ข้อมูลแบบแจ้ง" label HUB uses above its
              own copy of these two fields, for one consistent name across both views. */}
          <div className="rounded-md border border-border-subtle bg-surface-subtle p-3">
            <p className="m-0 mb-2 text-sm font-bold text-text">ข้อมูลแบบแจ้ง</p>
            <div className="grid gap-1">
              <div className="flex items-baseline justify-between gap-3 text-sm">
                <span className="text-text-muted">มีผลตั้งแต่งวดเดือน</span>
                <span className="font-mono font-bold text-text">
                  {watchedValues?.effectiveMonth ? `เดือน ${watchedValues.effectiveMonth}` : 'มกราคม (ค่าเริ่มต้น)'}
                </span>
              </div>
              <div className="flex items-baseline justify-between gap-3 text-sm">
                <span className="text-text-muted">เลขที่เอกสารอ้างอิง</span>
                <span className="font-mono font-bold text-text">{watchedValues?.documentReference?.trim() || '-'}</span>
              </div>
            </div>
          </div>
          <div className="grid gap-3">
            {TAX_ALLOWANCE_GROUPS.map((group) => {
              const lines = declaredFieldLines(group, watchedValues);
              return (
                <div key={group.key} className="rounded-md border border-border-subtle bg-surface-subtle p-3">
                  <p className="m-0 mb-2 text-sm font-bold text-text">{group.title}</p>
                  {lines.length > 0 ? (
                    <div className="grid gap-1">
                      {lines.map((line) => (
                        <div key={line.key} className="flex items-baseline justify-between gap-3 text-sm">
                          <span className="text-text-muted">{line.label}</span>
                          <span className="font-mono font-bold text-text">{line.value}</span>
                        </div>
                      ))}
                    </div>
                  ) : (
                    // Explicitly listed, not omitted -- a group with nothing in it is real
                    // information for a reviewer about to submit, not noise to hide (defect #2/#3).
                    <p className="m-0 text-xs text-text-muted">ไม่ได้ประกาศ</p>
                  )}
                </div>
              );
            })}
          </div>
          <div className="flex items-center justify-between gap-3 rounded-md border border-border bg-surface-subtle p-3">
            <span className="text-sm font-bold text-text">รวมค่าลดหย่อนที่ประกาศ</span>
            <strong className="font-mono text-base text-text">{declaredTotal}</strong>
          </div>
          {/* Evidence manifest -- STAGED (not-yet-uploaded) file counts, per section plus the hub's
              own general/uncategorized bucket (`UNCATEGORIZED_EVIDENCE_KEY` -- easy to miss, it is
              not one of TAX_ALLOWANCE_GROUPS' keys; omitting it used to silently read `0 ไฟล์` for
              anything staged through the hub's own panel, review fix). Rendered ONLY in `'staging'`
              mode -- this is deliberately not the already-stored attachment count each per-section
              `TaxAllowanceEvidencePanel` holds behind its own query (HUB/REVIEW never mount one of
              those per group, only SECTION does, so a stored count is not something "the form
              already has access to" without a new fetch this task is not reaching for), and outside
              `'staging'` -- `'direct'`/`'readonly'`, e.g. a PENDING or already-decided declaration
              reached by deep-linking straight to `?view=review` -- `staged` is empty while real,
              stored attachments may well exist, so showing it would read `0 ไฟล์` next to a section
              that genuinely has evidence: false on a URL this redesign now advertises as shareable
              (review fix). Hiding the manifest entirely there is the honest choice; a false zero is
              worse than no number. The heading says "เตรียมไว้" (prepared), never "ทั้งหมด" (total),
              for the same reason -- this is what is staged and about to be sent, not a final count. */}
          {evidenceMode === 'staging' ? (
            <div className="rounded-md border border-border-subtle bg-surface-subtle p-3">
              <p className="m-0 mb-2 text-xs font-extrabold uppercase tracking-wide text-text-muted">ไฟล์ที่เตรียมไว้ — ยังไม่ได้ส่ง</p>
              <div className="grid gap-1">
                {TAX_ALLOWANCE_GROUPS.map((group) => (
                  <div key={group.key} className="flex items-center justify-between gap-3 text-sm">
                    <span className="text-text-muted">{group.title}</span>
                    <span className="text-text">{(stagedEvidenceBySection?.[group.key] ?? []).length} ไฟล์</span>
                  </div>
                ))}
                <div className="flex items-center justify-between gap-3 text-sm">
                  <span className="text-text-muted">ทั่วไป (ไม่ได้ระบุหมวด)</span>
                  <span className="text-text">{(stagedEvidenceBySection?.[UNCATEGORIZED_EVIDENCE_KEY] ?? []).length} ไฟล์</span>
                </div>
              </div>
            </div>
          ) : null}
          <div className="flex justify-between gap-[10px]">
            <Button type="button" variant="secondary" onClick={() => onViewChange?.('hub')}>ย้อนกลับ</Button>
            {submitFooter}
          </div>
        </div>
      ) : null}
    </SafeForm>
  );
}
