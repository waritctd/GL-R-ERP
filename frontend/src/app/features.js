// Frozen sales/CRM stack is hidden for the v0.1.0 HR-core release. The code stays in the
// repo (frozen, not deleted); flip VITE_ENABLE_SALES=true to re-enable nav + routes.
export const SALES_ENABLED = import.meta.env.VITE_ENABLE_SALES === 'true';
