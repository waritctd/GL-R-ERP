import React, { forwardRef } from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FormField, fieldErrorId } from './FormField.jsx';

globalThis.React = React;

// A stand-in for a custom form-control component (like FileUploadField) that
// forwards its id and any extra props onto the real input.
const CustomControl = forwardRef(function CustomControl({ id, ...rest }, ref) {
  return <input ref={ref} id={id} {...rest} />;
});

describe('FormField', () => {
  it('injects aria-invalid and aria-describedby onto the matching child when an error is present', () => {
    render(
      <FormField label="อีเมล" htmlFor="email" error="กรุณาระบุอีเมล">
        <input id="email" />
      </FormField>,
    );

    const input = screen.getByLabelText('อีเมล');
    expect(input.getAttribute('aria-invalid')).toBe('true');
    expect(input.getAttribute('aria-describedby')).toBe(fieldErrorId('email'));
    expect(screen.getByRole('alert').textContent).toBe('กรุณาระบุอีเมล');
  });

  it('gives a field with no error no stray aria attributes', () => {
    render(
      <FormField label="ชื่อเล่น" htmlFor="nick">
        <input id="nick" />
      </FormField>,
    );

    const input = screen.getByLabelText('ชื่อเล่น');
    expect(input.hasAttribute('aria-invalid')).toBe(false);
    expect(input.hasAttribute('aria-describedby')).toBe(false);
    expect(input.hasAttribute('aria-required')).toBe(false);
  });

  it('preserves a caller-supplied aria-invalid/aria-describedby instead of overwriting them', () => {
    render(
      <FormField label="เบอร์โทร" htmlFor="phone" error="กรุณาระบุเบอร์โทร">
        <input
          id="phone"
          aria-invalid={false}
          aria-describedby="phone-custom-hint"
        />
      </FormField>,
    );

    const input = screen.getByLabelText('เบอร์โทร');
    // Caller explicitly set aria-invalid={false}; FormField must not clobber it.
    expect(input.getAttribute('aria-invalid')).toBe('false');
    // Caller-supplied aria-describedby is appended to, not replaced.
    expect(input.getAttribute('aria-describedby')).toBe(`phone-custom-hint ${fieldErrorId('phone')}`);
  });

  it('appends the error id to an existing aria-describedby rather than replacing it', () => {
    render(
      <FormField label="ชื่อ" htmlFor="name" hint="กรอกชื่อเต็ม" error="กรุณาระบุชื่อ">
        <input id="name" />
      </FormField>,
    );

    const input = screen.getByLabelText('ชื่อ');
    expect(input.getAttribute('aria-describedby')).toBe('name-hint name-error');
  });

  it('yields aria-required when required is set, without affecting aria-invalid', () => {
    render(
      <FormField label="ชื่อ-นามสกุล" htmlFor="nameTh" required>
        <input id="nameTh" />
      </FormField>,
    );

    const input = screen.getByLabelText(/ชื่อ-นามสกุล/);
    expect(input.getAttribute('aria-required')).toBe('true');
    expect(input.hasAttribute('aria-invalid')).toBe(false);
    expect(screen.getByText('*')).toBeTruthy();
  });

  it('respects a caller-supplied aria-required instead of overwriting it', () => {
    render(
      <FormField label="รหัสผ่าน" htmlFor="pw" required>
        <input id="pw" aria-required={false} />
      </FormField>,
    );

    expect(screen.getByLabelText(/รหัสผ่าน/).getAttribute('aria-required')).toBe('false');
  });

  it('handles multiple children, only augmenting the one whose id matches the field', () => {
    render(
      <FormField label="ประเภท" htmlFor="type-select" error="กรุณาเลือกประเภท">
        <select id="type-select">
          <option value="a">A</option>
        </select>
        <small id="type-select-note">หมายเหตุ</small>
      </FormField>,
    );

    const select = screen.getByLabelText('ประเภท');
    expect(select.getAttribute('aria-invalid')).toBe('true');
    expect(select.getAttribute('aria-describedby')).toBe(fieldErrorId('type-select'));
    // The sibling <small> must be left completely untouched.
    const note = document.getElementById('type-select-note');
    expect(note.hasAttribute('aria-invalid')).toBe(false);
    expect(note.hasAttribute('aria-describedby')).toBe(false);
  });

  it('augments a custom component child that forwards id/props to its real control', () => {
    render(
      <FormField label="ไฟล์แนบ" htmlFor="attachment" error="กรุณาแนบไฟล์">
        <CustomControl id="attachment" />
      </FormField>,
    );

    const control = screen.getByLabelText('ไฟล์แนบ');
    expect(control.getAttribute('aria-invalid')).toBe('true');
    expect(control.getAttribute('aria-describedby')).toBe(fieldErrorId('attachment'));
  });

  it('does not throw and leaves children untouched when there is no id to match against', () => {
    expect(() => render(
      <FormField label="ไม่มี id" error="ข้อผิดพลาด">
        <input />
      </FormField>,
    )).not.toThrow();

    const input = screen.getByDisplayValue('');
    expect(input.hasAttribute('aria-invalid')).toBe(false);
  });

  it('renders a single child without crashing (non-array Children.map path)', () => {
    render(
      <FormField label="เดี่ยว" htmlFor="solo" error="ข้อผิดพลาด">
        <input id="solo" />
      </FormField>,
    );

    expect(screen.getByLabelText('เดี่ยว').getAttribute('aria-invalid')).toBe('true');
  });

  // Regression test for the label/required-marker stacking bug: the global
  // `label { display: grid; gap: 7px }` rule (styles.css) turns every
  // *direct* child of a <label> — including a bare text node — into its own
  // grid item. The original markup rendered the label text and the
  // `.field-required` marker as two direct siblings of <label>, which meant
  // grid treated them as two stacked items with a 7px gap, pushing the "*"
  // onto its own line below the label text instead of inline after it.
  //
  // jsdom does not run real layout, so this can't assert on
  // getBoundingClientRect (it will always report zeroes). Instead this
  // asserts the *structural* precondition that determines the CSS Grid
  // outcome: a <label> must have exactly one direct in-flow node (element or
  // non-whitespace text) for grid to have nothing to stack. That is a proxy
  // for "renders on one line", not a pixel measurement of it — but it is an
  // exact proxy for this specific bug, because it is precisely the condition
  // the `label { display: grid }` rule keys off. It would not catch a
  // *different* mechanism of visual stacking (e.g. width/overflow wrapping),
  // but it fails on exactly the markup shape that caused this regression and
  // passes only once the label text and marker share a single wrapper.
  it('keeps the label text and the required marker as a single grid item so they render on one line', () => {
    const { container } = render(
      <FormField label="ชื่อ-นามสกุล" htmlFor="nameRequired" required>
        <input id="nameRequired" />
      </FormField>,
    );

    const label = container.querySelector('label');
    const directInFlowNodes = Array.from(label.childNodes).filter((node) => {
      if (node.nodeType === Node.TEXT_NODE) return node.textContent.trim() !== '';
      return node.nodeType === Node.ELEMENT_NODE;
    });

    // Old (buggy) markup produced 2 here: the label text node and the
    // `.field-required` <span> as direct siblings of <label>, which is what
    // made CSS Grid stack them with `gap: 7px` between them.
    expect(directInFlowNodes).toHaveLength(1);

    // The single grid item must still contain both the label text and the
    // (still hidden-from-AT) required marker — i.e. this isn't passing by
    // having silently dropped the asterisk.
    const wrapper = directInFlowNodes[0];
    expect(wrapper.textContent).toBe('ชื่อ-นามสกุล *');
    const marker = wrapper.querySelector('.field-required');
    expect(marker).toBeTruthy();
    expect(marker.getAttribute('aria-hidden')).toBe('true');
  });
});
