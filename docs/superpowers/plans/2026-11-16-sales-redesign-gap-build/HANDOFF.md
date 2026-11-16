# Handoff — sales redesign gap build (written 2026-11-16, late evening ICT)

Read this first on the new computer, then `ledger.md` in this folder (the running record of every decision), then the plan `../2026-11-16-sales-redesign-gap-build.md` and the audit `../2026-11-16-sales-redesign-gap-audit.md`.

## What this is
The owner approved a before/after review of the sales flow ("Sales Redesign Review", https://claude.ai/artifact/LJbt6Q1QzW2B2W9VhSjt4u). An audit on 2026-11-16 found large parts of it were never built. The owner's instruction: **fully build everything in that artifact**, using own judgement (decide and record; do not park on questions), showing the prototype (the artifact row) for each slice, with one implementer plus one adversarial reviewer per slice (the reviewer mutation-tests the tests), at most two agents at once.

## Owner rulings in force
- Sales screens say **"งาน", not "ดีล"**. Labels only: identifiers, routes, API fields, enum values, document codes and printed documents/emails are untouched.
- Use own judgement on design and data questions inside this build; record each ruling with its cost-if-wrong (see `ledger.md`). **Production deploys and sending email to staff still need one explicit go.**
- **Do NOT send the "มีอะไรใหม่" notice email** until the owner says so. The reset step in `email-reset-2026-11-16.sql` has NOT been run. Its preconditions are in the file.
- Never force-push. Commission, payroll and tax math are untouchable. Pipeline/backfill data containing customer names is private: do not commit it.

## State of production (on-prem; Render and Supabase are retired)
- Server: `ssh glradmin@glr-erp-prod` (Tailscale). Stack in `~/glr/ops/onprem` (docker compose: db, backend, web, cloudflared). Frontend deploys from `main` via Vercel; `/api` goes to `https://api.glr-hub.com`.
- Running: backend image `v2026-11-16d` (commit `64011083`), Flyway **V218**. `main` == `develop` as of that release. Self-service lock still on (hr, ceo, sales, sales_manager see the full portal).
- Last backup: `/mnt/glr-backup/glr-20261116-192952.dump`. Previous env: `.env.bak-20261116-pre16d`.
- Deploy procedure: `./backup.sh`; in `~/build-20261116` `git fetch origin <branch> && git checkout --detach FETCH_HEAD`; `docker build --platform linux/amd64 --build-arg ALLOW_MISSING_FONTS=false --label org.opencontainers.image.revision=<sha> --label org.opencontainers.image.version=<tag> -t ghcr.io/waritctd/glr-hr-backend:<tag> backend`; verify the jar (migrations, classes, fonts); set `GLR_IMAGE_TAG` in `.env`; `docker compose up -d backend`; check health and Flyway; then fast-forward `main`. Images exist only on the server (ghcr pull is unauthorized there).
- The Resend API key was replaced on 2026-11-16; no real email has been observed to send since. Sender `notifications@glr-portal.com` is not confirmed verified under the new key.

## Slices
| # | Slice | Branch | PR | State |
|---|---|---|---|---|
| 1 | `/tickets` → งานทั้งหมด board, `GET /api/sales/team-board` | `feat/all-deals-board` | #1317 | **Merged and live** |
| – | Quotation list: flat by number, search by total, approval date | `feat/quotation-flat-list` | #1314 | **Merged and live** |
| – | Release-notice email reset marker | `fix/release-note-email-reset` | #1313 | **Merged and live** (reset not run) |
| 4a | นัดเข้าพบ page + phone วันนี้ของฉัน | `feat/visits-page-and-today-phone` | #1319 | **Merged and live** (release 2026-11-17a) |
| 2 | Deal page redesign | `feat/deal-page-redesign` | #1320 | **Merged and live** (release 2026-11-17a) |
| 4c | Grouped finance worklist | `feat/finance-grouped-worklist` | #1321 | **Merged and live** (release 2026-11-17a) |
| 3a | Lifecycle dialogs (create, S2, pause, lost, cancel) + V219 | `feat/deal-dialogs` (stacked on `feat/deal-page-redesign`) | none yet | Built, pushed at `afe6acbd`. **Adversarial review was running when this was written; its report may not exist.** If `reviews/adversarial-task-3a.md` is absent, re-dispatch the review from `briefs/task-3a-brief.md` (the review prompt's attack list is summarised below) |
| 3b | Document dialogs (deposit, remaining invoice, IR, delivery) | `feat/deal-document-dialogs` | #1322 | **Merged and live** (release 2026-11-17a) |
| 6 | Server fields for drawn details that were left out | – | – | Not started |
| 5 | "ดีล" → "งาน" on the remaining sales screens | – | – | Not started (do last) |
| – | Refresh the review artifact (งาน wording, quotation list "after") | – | – | Not started |

### Slice 3a: what the review must settle before it can merge
1. `entryChannel` became optional on `POST /api/tickets` (stored UNSPECIFIED), dropping a 2026-09-30 "must state a channel" rule. Trace every consumer of the channel: stage route, pricing requests, reports and, above all, **commission**. If UNSPECIFIED changes any commission, pricing or stage computation, it does not merge as built.
2. The rep-typed estimate is stored in `imported_value` (the pipeline import's column): can it be counted in team totals as a real figure, or collide with a later import?
3. Pre-existing bug the implementer found: the guided-advance flow calls `PUT /tracking` with partial fields and wipes `designer_name`, `owner_name`, `buyer_name`. With `designerName` now captured at create, a routine S1→S2 move may erase it.
4. Migration `V219__deal_pause_return_on_and_cancel_reasons.sql`: additive, nullable column plus a widened check constraint. Confirm V219 is still free on develop before merging; it needs an owner-approved backend deploy.

### Slice 6 backlog (collected from the slices)
Board cards: quotation number, status chips (นัดรับ / ได้มัดจำ / ครบกำหนด), per-column move hints. Deal page: คาดปิด, planned visits in the timeline, tab counts. Call and visit dialogs: follow-up date (`nextFollowUpAt` on the activity request). Phone home: "ส่งตัวอย่าง" action. Finance rows: Express source chip, rep name, overdue day count. Remaining invoice: due date and terms. IR confirm: items and lead time. Delivery: delivery date, instalment and appointment.

### Follow-ups raised, not scheduled
- Deposit notice page computes figures in the browser and can be 1 satang off what the server issues (`DepositNoticePage.jsx` ~524-530 vs `DepositNoticeRepository` ~218-225). Owner decision: compute in satang or fetch a server preview.
- `quote_next_party` is not in `OWNER_ONLY_ACTIONS` (server already refuses non-owners).
- Sales menu entries do not check `SALES_ENABLED`.
- Closed-deal counts on งานทั้งหมด read at most the 500 newest tickets.
- The release-notice email reset is all-or-nothing and must wait for in-flight sends (documented in `ReleaseNoticeRepository`).

## Shipping what is ready
#1319, #1320, #1321 are frontend-only with no migration and could ship together. Slice 3a brings V219 and needs a backend deploy, so one combined deploy after 3a/3b is the cheaper path. Merge order for the stack: #1320 first (merge commit, not squash), then retarget and merge the two dialog branches.

## How to run things on a new machine
- Frontend: `cd frontend && npm ci`, then `npm run lint && npm test && npm run build`; targeted `npx vitest run <file>`.
- Backend integration tests need Postgres via `TEST_DB_URL` or Docker (see `CLAUDE.md`). On the original Windows PC a portable JDK 21 + Postgres 16 on port 54329 was used with `-Dtest.fork.count=1`; that setup does not travel, so on a machine with Docker just run `cd backend && ./mvnw -B -Dtest='ClassA,ClassB' test`.
- Mock app for looking at screens: `VITE_USE_MOCKS=true npm run dev` in `frontend` (mock clicking is never permission evidence).
- The saved copy of the review artifact's HTML was local to the old machine; read the artifact again by its URL. Template ids used in the briefs: `a-board`, `a-mywork`, `a-team`, `a-deal`, `a-deal-quote`, `a-deal-money`, `a-deal-ship`, `a-fin-land`, `a-fin-deal`, `m-create`, `m-stage`, `m-hold`, `m-lost`, `m-cancel`, `m-deposit-issue`, `m-remaining`, `m-ir`, `m-delivery`, `m-call`, `m-visit-plan`, `m-visit-log`, plus the `NAV` and `ROWS` scripts.
- Paths inside `briefs/`, `reports/` and `reviews/` that start with `C:\Users\user\…\scratchpad\` refer to the old machine; the same files are in this folder.

## Method that has been working
Brief as a file → implementer in its own worktree, test-first with a recorded red run → controller commits → adversarial reviewer in an isolated worktree with an explicit attack list and mutation testing → rulings → fix round by the same implementer → scoped re-review → push and PR with the review evidence in the body. Every slice so far needed at least one fix round; two of them had a real defect introduced BY a fix (deal-to-deal state leak; a rewritten guard test), so never skip the re-review.
