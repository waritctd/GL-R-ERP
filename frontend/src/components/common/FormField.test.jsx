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
});
