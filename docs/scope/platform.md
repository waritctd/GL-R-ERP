# Scope: Platform

Shared ground every other area stands on. Index: [index.md](index.md).

### 1. Sign in and sessions · existing · GA
Email and password sign in with a cookie session, forgot password, the temporary password that HR issues, and a limit on failed attempts. code in `hr/auth`, `features/auth`

### 2. Roles, permissions and view as · existing · GA
Nine roles, access policies per area, grant flags on a person (operator, read all sales, all sales documents), the self service lock on production builds, and read only view as. The Java service is the authority. code in `hr/auth`, `frontend/src/app/permissions.js`

### 3. Notifications and mail · existing
The bell in the app plus email through a chosen provider, including release notices and the month end attendance mail. code in `hr/notification`, `hr/mail`

### 4. Attachments and file storage · existing
Uploads kept on disk under the uploads folder, with access checked against the record they belong to. code in `hr/attachment`

### 5. Activity log and audit · existing
A log of who did what, app events, and the audit trail an admin can read. code in `hr/activity`, `hr/audit`, `features/activityLog`

### 6. Feedback and What's New · existing
Staff send feedback from the portal and get mail when it is accepted or released. Each release has a What's New page with screenshots. code in `hr/feedback`, `features/whatsNew`

### 7. Document rendering · existing
Fills the real company `.xls` forms and turns them into PDF with the licensed Thai fonts. Used by quotations, deposit notices, invoices and payroll. code in `hr/common`, `backend/src/main/resources/templates`

### 8. Mock mode and demo data · existing
The whole API can run in the browser for quick checks, and a demo profile seeds one sign in per role for the real backend. code in `frontend/src/api/mockApi.js`, `backend/src/main/resources/db/migration-demo`

### 9. Deploy and operations · existing
Vercel builds the site from `main`. The backend image, database, backups and the maintenance page are run from the office server by the deployer. code in `ops/onprem`, `scripts`, `.github/workflows`
