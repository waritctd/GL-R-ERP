// Sales/CRM ships as part of the product. It was hidden for the HR-core-only
// v0.1.0 release; that release is historical and the freeze is lifted.
//
// The flag is an off-switch, not an on-switch: sales is enabled unless
// VITE_ENABLE_SALES is explicitly "false". That direction matters — the
// production build sets no VITE_ vars at all (vercel.json has no env block and
// .env* is gitignored), so an `=== 'true'` check left sales disabled in
// production regardless of what the deploy intended.
export const SALES_ENABLED = import.meta.env.VITE_ENABLE_SALES !== 'false';

// UAT phase-1 preview flag — deliberately an ON-switch, the opposite
// direction from SALES_ENABLED above. SALES_ENABLED must default to "on"
// because production sets no VITE_ vars and an off-by-default flag would
// silently disable sales everywhere; this flag is the mirror-image risk — it
// is scoped to a single throwaway Vercel preview deployment (this branch)
// and must default OFF everywhere else (main, uat, every other preview) so
// that build only ever changes behaviour where it is explicitly opted into
// via vercel.json's `build.env`. An `!== 'false'` check here would leak the
// phase-1 landing-page behaviour into every build that doesn't set the var.
export const UAT_PHASE1 = import.meta.env.VITE_UAT_PHASE1 === 'true';
