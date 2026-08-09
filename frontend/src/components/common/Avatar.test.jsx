import React from 'react';
import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { Avatar } from './Avatar.jsx';

globalThis.React = React;

describe('Avatar', () => {
  it('uses the initials the backend supplied', () => {
    render(<Avatar employee={{ initials: 'AB', nameTh: 'สมชาย บริหารกิจ' }} />);
    expect(screen.getByTitle('สมชาย บริหารกิจ').textContent).toBe('AB');
  });

  it('derives initials from the Thai name when none were supplied', () => {
    render(<Avatar employee={{ nameTh: 'ประเสริฐ คลังสินค้า' }} />);
    expect(screen.getByTitle('ประเสริฐ คลังสินค้า').textContent).toBe('ปค');
  });

  // The defect this component shipped with, observed on a real Spring backend against the UAT
  // seed: every avatar in the app rendered a literal dash. EmployeeRepository#fullName returns "-"
  // for an employee with no English name, and #initials feeds that string straight into its
  // English branch — `hasText("-")` is true, so it returns "-" and never reaches its Thai
  // fallback. "-" is truthy, so the `||` here could not step in either.
  //
  // Asserting the CHARACTER, not just "not a dash": a guard that returned an empty string would
  // also stop being a dash while still telling the user nothing.
  it('falls back to the Thai name when the backend sends its "-" placeholder', () => {
    render(<Avatar employee={{ initials: '-', nameTh: 'วรรณา ทรัพยากรบุคคล' }} />);
    expect(screen.getByTitle('วรรณา ทรัพยากรบุคคล').textContent).toBe('วท');
  });

  it('falls back to the `name` prop when the employee record is absent entirely', () => {
    render(<Avatar name="ศักดิ์ชัย แก้วมณี" />);
    expect(screen.getByTitle('ศักดิ์ชัย แก้วมณี').textContent).toBe('ศแ');
  });

  // initialsFromName's own last resort. Worth pinning here because the placeholder guard above
  // routes MORE cases into it than before.
  it('renders GL rather than nothing when there is no name at all', () => {
    const { container } = render(<Avatar employee={{ initials: '-' }} />);
    expect(container.firstChild.textContent).toBe('GL');
  });
});
