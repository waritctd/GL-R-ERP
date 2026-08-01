import { useEffect, useState } from 'react';

function getMatches(query) {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false;
  }
  return window.matchMedia(query).matches;
}

// Generic media-query hook, extracted from the mobile-only check below (2026-07-31, B1 tablet-panel
// fix) so a second breakpoint-driven JS decision -- the payroll detail panel's >=1440px side-by-side
// vs <1440px overlay presentation (PayrollPage.jsx) -- has somewhere to live besides a second,
// slightly-different copy of this same matchMedia listener.
//
// Item 1 fix (2026-07-31): that boundary used to be 1280px, reusing Tailwind's own built-in `xl:`
// breakpoint on the theory that was already the panel's measured content-fit floor -- it was not
// (see index.css's `payroll-wide` custom-variant comment for the real measurement and why 1440px is
// used instead). 1440px is NOT one of Tailwind's own named breakpoints, so unlike the `xl:` case this
// number DOES need a custom variant declared once, in index.css, rather than trusting a Tailwind
// built-in and a bare query string here to silently agree forever -- see `@custom-variant
// payroll-wide` there, which PayrollPage.jsx's grid track and the query string passed into this hook
// both key off.
export function useMediaQuery(query) {
  const [matches, setMatches] = useState(() => getMatches(query));

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return undefined;
    }

    const mediaQueryList = window.matchMedia(query);
    const handleChange = (event) => setMatches(event.matches);

    setMatches(mediaQueryList.matches);
    mediaQueryList.addEventListener('change', handleChange);

    return () => mediaQueryList.removeEventListener('change', handleChange);
  }, [query]);

  return matches;
}

const MOBILE_QUERY = '(max-width: 720px)';

export function useIsMobile() {
  return useMediaQuery(MOBILE_QUERY);
}
