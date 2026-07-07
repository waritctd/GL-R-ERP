import { QueryClient } from '@tanstack/react-query';

// Shared TanStack Query client for the app. Conservative defaults suited to
// an internal HR portal: a short freshness window, a single retry, and no
// refetch-on-focus (avoids surprise refetches when the user tabs back).
export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      staleTime: 30_000,
      retry: 1,
      refetchOnWindowFocus: false,
    },
  },
});
