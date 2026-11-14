import { useEffect, useState } from 'react';

function getMatches(query) {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false;
  }
  return window.matchMedia(query).matches;
}

// Generic media-query hook, extracted from the mobile-only check below so a second breakpoint-driven JS
// decision -- the payroll detail panel's >=1440px side-by-side vs <1440px overlay presentation -- has
// somewhere to live besides a second, slightly-different copy of this same matchMedia listener.
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
