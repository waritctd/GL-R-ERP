import React from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { FormField, fieldErrorId } from './FormField.jsx';

globalThis.React = React;

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
});
