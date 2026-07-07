import { useEffect, useState } from 'react';

const MOBILE_QUERY = '(max-width: 720px)';

function getIsMobile() {
  if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
    return false;
  }
  return window.matchMedia(MOBILE_QUERY).matches;
}

export function useIsMobile() {
  const [isMobile, setIsMobile] = useState(getIsMobile);

  useEffect(() => {
    if (typeof window === 'undefined' || typeof window.matchMedia !== 'function') {
      return undefined;
    }

    const mediaQueryList = window.matchMedia(MOBILE_QUERY);
    const handleChange = (event) => setIsMobile(event.matches);

    setIsMobile(mediaQueryList.matches);
    mediaQueryList.addEventListener('change', handleChange);

    return () => mediaQueryList.removeEventListener('change', handleChange);
  }, []);

  return isMobile;
}
