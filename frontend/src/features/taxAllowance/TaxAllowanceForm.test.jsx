import React, { useState } from 'react';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { TaxAllowanceForm } from './TaxAllowanceForm.jsx';
import { defaultAllowanceValues, LAW_SOURCES, TAX_ALLOWANCE_GROUPS } from './taxAllowanceSchema.js';

globalThis.React = React;

// Hub-and-spoke navigation (#tax-allowance-ia-hub-review): TaxAllowanceForm defaults to
// `sectioned=true`, three views -- HUB (section rows + declaration-level fields + a running total),
// SECTION (one group's own fields), REVIEW (the recap + the ONLY submit control in the whole flow)
// -- selected by the CONTROLLED `view` prop, not internal state. The PAGE owns `?view=` in
// production; here, `ControlledTaxAllowanceForm` below plays that role with a plain `useState` so
// these tests can navigate the same way a real caller would, without pulling in react-router.
//
// `evidenceMode` is left undefined in every case here on purpose -- these tests are about view
// structure and value persistence, not the evidence panel (covered separately in
// TaxAllowanceEvidencePanel.test.jsx and TaxAllowancePage.test.jsx); leaving it undefined means the
// embedded evidence panel never mounts, so no react-query provider or API mocking is needed here.

function ControlledTaxAllowanceForm({ initialView = 'hub', ...props }) {
  const [view, setView] = useState(initialView);
  return <TaxAllowanceForm view={view} onViewChange={setView} {...props} />;
}

function renderForm(props = {}) {
  const onSubmit = vi.fn();
  const utils = render(
    <ControlledTaxAllowanceForm
      caps={[]}
      defaultValues={defaultAllowanceValues(null)}
      onSubmit={onSubmit}
      {...props}
    />,
  );
  return { onSubmit, ...utils };
}

describe('TaxAllowanceForm — hub-and-spoke navigation (sectioned, default)', () => {
  it('the hub lists all five sections as choices, with none of their fields mounted yet', () => {
    renderForm();

    for (const group of TAX_ALLOWANCE_GROUPS) {
      expect(screen.getByText(group.title)).not.toBeNull();
    }
    // Field-level controls from every section stay unmounted until a section is opened -- "only one
    // view's markup is ever mounted" (the task's own correctness constraint), scoped further here to
    // "only one SECTION's markup is ever mounted" once inside a section.
    expect(screen.queryByLabelText('คู่สมรส (ไม่มีเงินได้)')).toBeNull();
    expect(screen.queryByLabelText('ประกันชีวิต')).toBeNull();
  });

  it('the declaration-level fields (effective month, document reference) stay on the hub, not inside any section', () => {
    renderForm();
    expect(screen.getByLabelText('มีผลตั้งแต่งวดเดือน')).not.toBeNull();
    expect(screen.getByLabelText(/เลขที่เอกสารอ้างอิง/)).not.toBeNull();
  });

  it('selecting a section shows ONLY that section\'s fields', () => {
    renderForm();
    fireEvent.click(screen.getByText('ครอบครัว'));

    expect(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)')).not.toBeNull();
    // A different section's own field must not be mounted just because we're in a section view.
    expect(screen.queryByLabelText('ประกันชีวิต')).toBeNull();
  });

  it('values survive navigating between views: fill section A, move to B, fill it, go back to A — A\'s value is still there', () => {
    renderForm();

    fireEvent.click(screen.getByText('ครอบครัว'));
    const spouseInput = screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)');
    fireEvent.change(spouseInput, { target: { value: '60000' } });
    expect(spouseInput.value).toBe('60000');

    fireEvent.click(screen.getByRole('button', { name: /กลับไปหน้ารวม/ }));
    fireEvent.click(screen.getByText('ประกัน'));
    const lifeInsuranceInput = screen.getByLabelText('ประกันชีวิต');
    fireEvent.change(lifeInsuranceInput, { target: { value: '10000' } });
    expect(lifeInsuranceInput.value).toBe('10000');

    fireEvent.click(screen.getByRole('button', { name: /กลับไปหน้ารวม/ }));
    fireEvent.click(screen.getByText('ครอบครัว'));

    // This is the one that would fail if the form (or its `react-hook-form` instance) were
    // remounted/reset between views instead of staying mounted underneath the conditional JSX.
    expect(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)').value).toBe('60000');
  });

  it('hub section rows show a declared subtotal once a section has a value, and ไม่ได้ประกาศ for one that does not', () => {
    renderForm();

    fireEvent.click(screen.getByText('ครอบครัว'));
    fireEvent.change(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)'), { target: { value: '60000' } });
    fireEvent.click(screen.getByRole('button', { name: /กลับไปหน้ารวม/ }));

    // ครอบครัว now shows its declared subtotal instead of a bare "filled in" flag -- appears twice:
    // once as the row's own secondary line, once in the running total below it (nothing else was
    // declared, so the two are equal).
    expect(screen.getAllByText('฿60,000.00')).toHaveLength(2);
    // The other four, untouched, read as explicitly not declared -- not a false "unfinished" guess.
    expect(screen.getAllByText('ไม่ได้ประกาศ').length).toBe(TAX_ALLOWANCE_GROUPS.length - 1);
  });

  it('a hub row with a count/checkbox value but no money reads as having data, not a misleading ฿0.00 (review fix)', () => {
    renderForm();

    fireEvent.click(screen.getByText('ครอบครัว'));
    // จำนวนบุตร is a COUNT field -- groupHasValue treats it as "declared", but groupDeclaredTotal
    // (money only) stays 0 since no บุตร baht amount was ever typed.
    fireEvent.change(screen.getByLabelText('จำนวนบุตร (คน)'), { target: { value: '2' } });
    fireEvent.click(screen.getByRole('button', { name: /กลับไปหน้ารวม/ }));

    const familyRow = screen.getByRole('button', { name: /ครอบครัว/ });
    expect(within(familyRow).queryByText('฿0.00')).toBeNull();
    expect(within(familyRow).getByText(/มีข้อมูล/)).not.toBeNull();
  });

  it('a SECTION view has no submit control, and its own primary button does not call onSubmit', () => {
    const { onSubmit } = renderForm();

    fireEvent.click(screen.getByText('ครอบครัว'));
    fireEvent.change(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)'), { target: { value: '60000' } });

    // This is the defect the whole change exists to fix: the old step 2 put the real submit button
    // right beside its own "back" button, where it read as "save this section and continue" but
    // actually submitted the entire declaration.
    expect(screen.queryByRole('button', { name: 'ยื่นแบบแจ้ง' })).toBeNull();
    const backButton = screen.getByRole('button', { name: /กลับไปหน้ารวม/ });
    // Native DOM property, not a jest-dom matcher (not configured in this project's vitest setup).
    expect(backButton.type).toBe('button');

    fireEvent.click(backButton);
    expect(onSubmit).not.toHaveBeenCalled();
  });

  it('onSubmit fires only from REVIEW, carrying the full combined declaration from every section visited', async () => {
    const { onSubmit } = renderForm();

    fireEvent.click(screen.getByText('ครอบครัว'));
    fireEvent.change(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)'), { target: { value: '60000' } });
    fireEvent.click(screen.getByRole('button', { name: /กลับไปหน้ารวม/ }));

    fireEvent.click(screen.getByText('ประกัน'));
    fireEvent.change(screen.getByLabelText('ประกันชีวิต'), { target: { value: '10000' } });
    fireEvent.click(screen.getByRole('button', { name: /กลับไปหน้ารวม/ }));

    fireEvent.click(screen.getByRole('button', { name: 'ตรวจทานและยื่น' }));
    fireEvent.click(screen.getByRole('button', { name: 'ยื่นแบบแจ้ง' }));

    await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    const values = onSubmit.mock.calls[0][0];
    expect(Number(values.spouseAllowance)).toBe(60000);
    expect(Number(values.lifeInsuranceAllowance)).toBe(10000);
  });

  // CRITICAL review fix: the click-driven tests above only prove there is no VISIBLE submit control
  // outside REVIEW -- they say nothing about HTML's implicit-submission rule, which fires a real
  // 'submit' event on Enter in a text field whenever the form has no submit button but exactly ONE
  // field that counts toward the rule. That was true of HUB (only `#ta-document-reference` is a bare
  // text input) and of the `housing` SECTION (its one and only field) -- Enter in either filed the
  // ENTIRE declaration with no button ever pressed, in a real browser, before the form-level
  // `onSubmit` gate this section tests. jsdom does not implement implicit submission (pressing "Enter"
  // via fireEvent does nothing special here) -- e2e/implicit-submission.spec.js is the layer that
  // reproduces the real thing.
  //
  // `submitWithSubmitter` below, not `fireEvent.submit(form)`: this form is now `<SafeForm
  // canSubmit={isReview} ...>` (#safe-form-primitive), and `canSubmit` is a RESTRICTION layered on
  // top of SafeForm's own submitter guard, not a replacement for it -- both checks always apply.
  // `fireEvent.submit(form)` dispatches a plain synthetic Event with no `.submitter` property at
  // all under jsdom, so it would be blocked by the submitter guard on EVERY view including REVIEW,
  // which would make "does NOT call onSubmit" pass for the wrong reason on HUB/housing (no
  // submitter present at all, not because `canSubmit` rejected it -- this repo's own documented
  // vacuous-test shape) and make "calls onSubmit" on REVIEW fail outright. A manually constructed
  // `SubmitEvent` with an explicit `submitter` is picked up by React's onSubmit exactly like a real
  // click (verified), so every case below now exercises `canSubmit` specifically.
  function submitWithSubmitter(form) {
    form.dispatchEvent(new SubmitEvent('submit', { bubbles: true, cancelable: true, submitter: document.createElement('button') }));
  }

  describe('form-level submit gate blocks Enter-key implicit submission outside REVIEW', () => {
    it('a real submit event on HUB does not call onSubmit', () => {
      const { onSubmit, container } = renderForm();
      submitWithSubmitter(container.querySelector('form'));
      expect(onSubmit).not.toHaveBeenCalled();
    });

    it('a real submit event on the one-field `housing` SECTION does not call onSubmit', () => {
      // housing is the specific group the review flagged: it holds exactly one field
      // (homeLoanInterestAllowance), so it satisfies HTML's implicit-submission rule same as HUB does.
      const { onSubmit, container } = renderForm({ initialView: 'housing' });
      submitWithSubmitter(container.querySelector('form'));
      expect(onSubmit).not.toHaveBeenCalled();
    });

    it('a real submit event on REVIEW calls onSubmit', async () => {
      const { onSubmit, container } = renderForm({ initialView: 'review' });
      submitWithSubmitter(container.querySelector('form'));
      await waitFor(() => expect(onSubmit).toHaveBeenCalledTimes(1));
    });
  });

  it('review recaps each declared field by its value, marks untouched groups ไม่ได้ประกาศ, and totals the money fields', () => {
    renderForm();

    fireEvent.click(screen.getByText('ครอบครัว'));
    fireEvent.change(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)'), { target: { value: '60000' } });
    fireEvent.click(screen.getByRole('button', { name: /กลับไปหน้ารวม/ }));
    fireEvent.click(screen.getByRole('button', { name: 'ตรวจทานและยื่น' }));

    // The declared field's own label shows up in the recap...
    expect(screen.getByText('คู่สมรส (ไม่มีเงินได้)')).not.toBeNull();
    // ...its formatted value appears twice: once as the recap line, once as the running total below
    // it (nothing else was declared, so the two are equal).
    expect(screen.getAllByText('฿60,000.00')).toHaveLength(2);
    // The other four groups, untouched, are listed explicitly rather than hidden.
    expect(screen.getAllByText('ไม่ได้ประกาศ').length).toBe(TAX_ALLOWANCE_GROUPS.length - 1);
  });

  it('review also recaps the declaration-level fields (effective month, document reference) -- not just the five groups (review fix)', () => {
    renderForm();

    fireEvent.change(screen.getByLabelText('มีผลตั้งแต่งวดเดือน'), { target: { value: '3' } });
    fireEvent.change(screen.getByLabelText(/เลขที่เอกสารอ้างอิง/), { target: { value: 'REF-42' } });
    fireEvent.click(screen.getByRole('button', { name: 'ตรวจทานและยื่น' }));

    expect(screen.getByText('เดือน 3')).not.toBeNull();
    expect(screen.getByText('REF-42')).not.toBeNull();
  });

  it('review shows the default effective-month/document-reference copy when neither was set', () => {
    renderForm();
    fireEvent.click(screen.getByRole('button', { name: 'ตรวจทานและยื่น' }));

    expect(screen.getByText('มกราคม (ค่าเริ่มต้น)')).not.toBeNull();
    expect(screen.getByText('-')).not.toBeNull();
  });

  describe('readOnly', () => {
    it('disables every field, hides the hub\'s entry point into review, and renders no submit control', () => {
      renderForm({ readOnly: true });

      // No entry point toward submission from the hub.
      expect(screen.queryByRole('button', { name: 'ตรวจทานและยื่น' })).toBeNull();

      fireEvent.click(screen.getByText('ครอบครัว'));
      expect(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)').disabled).toBe(true);
      expect(screen.queryByRole('button', { name: 'ยื่นแบบแจ้ง' })).toBeNull();
    });

    it('renders no submit control on review either, when reached directly (e.g. via ?view=review)', () => {
      renderForm({ readOnly: true, initialView: 'review' });

      expect(screen.queryByRole('button', { name: 'ยื่นแบบแจ้ง' })).toBeNull();
      // ย้อนกลับ still works read-only -- it is pure navigation, not a mutation.
      expect(screen.getByRole('button', { name: 'ย้อนกลับ' })).not.toBeNull();
    });

    // Review-round regression (#safe-form-primitive, F1): `canSubmit={isReview}` is true here --
    // `readOnly` does not affect `isReview` at all -- yet the test just above proves there is no
    // submit button anywhere in this exact state. An earlier version of SafeForm let `canSubmit`
    // BYPASS its submitter guard, so a submitterless submit event reaching this state (impossible
    // today only because REVIEW currently renders zero input fields while readOnly -- not because
    // anything actually stops the event) would have gone straight through to `onSubmit`. This is
    // the scenario, made concrete: REVIEW reached read-only via `?view=review` (a URL
    // TaxAllowancePage advertises as shareable), the way it would look the moment REVIEW gains any
    // input field in read-only mode (an acknowledgement note is the obvious next ask). It must
    // still be blocked.
    it('still blocks a submitterless submit on REVIEW even though canSubmit is true here (readOnly)', () => {
      const { onSubmit, container } = renderForm({ readOnly: true, initialView: 'review' });

      fireEvent.submit(container.querySelector('form'));

      expect(onSubmit).not.toHaveBeenCalled();
    });
  });
});

describe('TaxAllowanceForm — sectioned=false (TaxAllowanceReviewPage\'s on-behalf modal)', () => {
  it('renders every group expanded at once, unchanged from before this task', () => {
    renderForm({ sectioned: false });

    // Every section's own field is mounted simultaneously -- the ORIGINAL layout, deliberately kept
    // for this caller (see TaxAllowanceReviewPage.jsx's own comment on why).
    expect(screen.getByLabelText('คู่สมรส (ไม่มีเงินได้)')).not.toBeNull();
    expect(screen.getByLabelText('ประกันชีวิต')).not.toBeNull();
    // No step/view chrome of any kind.
    expect(screen.queryByText(/ขั้นตอนที่/)).toBeNull();
  });

  it('never renders the references section — a small one-off on-behalf entry, not the hub (unrequested UX change otherwise)', () => {
    renderForm({ sectioned: false });
    expect(screen.queryByText('แหล่งอ้างอิงทางกฎหมาย')).toBeNull();
  });
});

// feat/tax-allowance-law-references: each field/row that carries a `lawRef` (taxAllowanceSchema.js)
// renders it as a REAL, externally-opening link, and the hub carries one references section listing
// every distinct LAW_SOURCES entry exactly once. See taxAllowanceSchema.test.js for the data-layer
// guarantee ("every field has a lawRef pointing at a verified URL") this only exercises the render
// side of.
describe('TaxAllowanceForm — law references (feat/tax-allowance-law-references)', () => {
  it('a field with a lawRef renders it as a real link, not text trapped inside the inert InfoTip bubble', () => {
    renderForm();
    fireEvent.click(screen.getByText('ครอบครัว'));

    // Several ครอบครัว fields legitimately share มาตรา 47 (task spec) -- assert on all of them, not
    // just the first, and that every one is a genuine, independently clickable anchor.
    const links = screen.getAllByRole('link', { name: /ประมวลรัษฎากร มาตรา 47/ });
    expect(links.length).toBeGreaterThan(1);
    for (const link of links) {
      expect(link.getAttribute('href')).toBe('https://www.rd.go.th/5937.html');
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
      // `.info-tip-bubble` is `pointer-events: none` by design (styles.css) -- a link nested inside
      // one could never actually be clicked by a mouse. This is the regression the component's own
      // comment (TaxAllowanceForm.jsx's `LawRefLink`) explains; assert it stays true.
      expect(link.closest('.info-tip-bubble')).toBeNull();
    }
  });

  it('the checkbox field (disabilityCardHolder) renders its own law reference link alongside its InfoTip', () => {
    renderForm();
    fireEvent.click(screen.getByText('ครอบครัว'));

    const checkboxLabel = screen.getByText('ผู้พิการที่อุปการะมีบัตรประจำตัวคนพิการ').closest('label');
    expect(checkboxLabel).not.toBeNull();
    const link = within(checkboxLabel).getByRole('link', { name: /ประมวลรัษฎากร มาตรา 47/ });
    expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    expect(link.getAttribute('target')).toBe('_blank');
  });

  it('the auto-granted rows (ส่วนตัว, ประกันสังคม) on the hub carry their own law reference link', () => {
    renderForm();
    const links = screen.getAllByRole('link', { name: /ประมวลรัษฎากร มาตรา 47/ });
    expect(links.length).toBeGreaterThanOrEqual(2);
  });

  it('the hub renders one references section listing every distinct LAW_SOURCES entry exactly once, each opening externally', () => {
    renderForm();
    expect(screen.getByText('แหล่งอ้างอิงทางกฎหมาย')).not.toBeNull();

    const allLinks = screen.getAllByRole('link');
    for (const source of Object.values(LAW_SOURCES)) {
      // Only the references-section occurrence carries the "— กรมสรรพากร" attribution suffix in its
      // visible text; bare inline per-field occurrences (e.g. section47, cited by several fields)
      // render the label alone, so filtering on the suffix isolates the references-section entry
      // specifically even for a source also cited elsewhere on the same page.
      const inReferences = allLinks.filter(
        (link) => link.textContent.includes(source.label) && link.textContent.includes('กรมสรรพากร'),
      );
      expect(inReferences, `"${source.label}" should appear exactly once in the references section`).toHaveLength(1);
      const [link] = inReferences;
      expect(link.getAttribute('href')).toBe(source.url);
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
    }
  });

  it('a source with a vintage (year/version) shows it as visible text, not only inside the link', () => {
    renderForm();
    // "A reader must be able to see a source is year-stamped without clicking it" (task spec) --
    // both must be visible as plain text, not just carried in the href or an aria-label.
    expect(screen.getByText(LAW_SOURCES.yearSummary.vintage)).not.toBeNull();
    expect(screen.getByText(LAW_SOURCES.formPdf.vintage)).not.toBeNull();
  });

  it('the references section is not shown on a SECTION or REVIEW view — hub-only, background material', () => {
    renderForm({ initialView: 'housing' });
    expect(screen.queryByText('แหล่งอ้างอิงทางกฎหมาย')).toBeNull();

    renderForm({ initialView: 'review' });
    expect(screen.queryByText('แหล่งอ้างอิงทางกฎหมาย')).toBeNull();
  });
});
