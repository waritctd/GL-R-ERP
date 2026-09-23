# GL-R-ERP — Self-host Migration Runbook

Move production from **Render (backend) + Vercel (frontend) + Supabase (DB)** to a
**self-hosted Ubuntu server** reached over a **Cloudflare Tunnel** (no inbound ports).

**Decisions locked (2026-09-19):** LUKS = keyfile auto-unlock · reverse proxy = Caddy ·
`VITE_SELF_SERVICE_ONLY=false` (full portal).

**Files in this folder:** `docker-compose.yml`, `Caddyfile`, `.env.example`,
`scripts/luks-setup.sh`, `scripts/backup.sh`, `scripts/migrate-from-supabase.sh`.

> 🔐 The DB password shared in chat is exposed — generate a fresh one for `.env`. Secrets
> live only in `.env` (chmod 600, gitignored) and the Cloudflare/Render dashboards. Never in git.

---

## System-specific gotchas (the 5 that cause a bad go-live)
1. **Fonts:** backend MUST run the pre-built ghcr image (carries licensed Thai fonts). Build
   from source on the server → every PDF regresses to substitute fonts (#666).
2. **Flyway:** restore must carry `hr.flyway_schema_history` across; keep
   `APP_FLYWAY_VALIDATE_ON_MIGRATE=false` and locations = `classpath:db/migration` (bare prod).
   Never add `migration-demo` to real prod (seeds demo logins). Diff the migration **set**, not max.
3. **5 schemas:** dump/restore `hr, hr_restricted, sales, customers, price_catalog` — not just hr/sales.
4. **Bare `prod` hard-fails on boot** if `APP_UPLOADS_DIR`, or (when mail provider=resend/smtp)
   `APP_MAIL_FROM` / `APP_MAIL_APP_BASE_URL` / provider key, are blank.
5. **Cloudflare Access** must bypass the public endpoints: `POST /api/auth/login`,
   `POST /api/attendance/punch`, `GET /actuator/health` — or login and the punch agent break.

Other facts: backend port **8080**; session is **JDBC-persisted in Postgres** (`hr.spring_session`) —
there is **no session-secret env** to set; session cookie `GLR_HR_SESSION` stays `Secure` behind
Cloudflare HTTPS; the ghcr image is **amd64** (server must be amd64).

---

## Phase 0 — Plan & verify
- [ ] Supabase Postgres **major version** → set the same in `docker-compose.yml` (`postgres:NN`).
- [ ] Note DB size, uploads size, installed extensions (`\dx`), confirm the 5 app schemas exist.
- [ ] Confirm server is **amd64** and has enough disk for DB + uploads + local backups.
- [ ] GitHub PAT with `read:packages` ready (the ghcr backend image is private).
- [ ] Cloudflare account + a domain on it; decide the hostname (e.g. `erp.example.com`).

## Phase 1 — Data disk + hardening  (`scripts/luks-setup.sh`)
- [ ] LUKS-format the 300GB disk, keyfile auto-unlock, mount `/srv/glr`, create
      `postgres/ uploads/ backup/ www/`. **Reboot and confirm it auto-mounts.**
- [ ] `apt update && upgrade`; enable `unattended-upgrades`.
- [ ] UFW: default-deny inbound, allow **only** SSH (from a known IP). Do **not** open 80/443.
- [ ] SSH: install your public key → test key login → then `PasswordAuthentication no`,
      `PermitRootLogin no`. 🔐 Never pass an SSH password as a CLI argument.
- [ ] Create a non-root sudo user; check `sudo -l`.
- [ ] **Add swap — the VM is 4GB, which is tight.** The backend image runs Chromium + LibreOffice
      for PDF/Excel export; a spike can OOM-kill a container. Add 2–4GB swap
      (`fallocate -l 4G /swapfile && chmod 600 /swapfile && mkswap /swapfile && swapon /swapfile`,
      then persist in `/etc/fstab`). Compose sets per-service `mem_limit`s (backend 2.5G / postgres
      640M / caddy+cloudflared 128M each) sized for this box; if PDF/Excel use is heavy, raise the VM
      to 6–8GB RAM and bump the limits + `JAVA_OPTS` rather than leaning on swap.

## Phase 2 — Docker
- [ ] Install Docker Engine (official repo) + Compose v2 plugin.
- [ ] Point Docker `data-root` at `/srv/glr` (or rely on the bind mounts in compose) so data
      lands on the encrypted disk. Enable live-restore. `docker run hello-world`.

## Phase 3 — Application stack
- [ ] `echo <PAT> | docker login ghcr.io -u <github-user> --password-stdin` (private image).
- [ ] `cp .env.example .env && chmod 600 .env` → fill every `__SET_ME__` (Phase 4).
- [ ] `docker compose pull && docker compose up -d postgres` → wait `healthy`.

## Phase 4 — Environment & secrets  (`.env`)
- [ ] Generate a strong `SPRING_DATASOURCE_PASSWORD` (`openssl rand -base64 24`).
- [ ] Set REQUIRED: `APP_UPLOADS_DIR=/srv/glr/uploads`, mail (`APP_MAIL_PROVIDER=smtp` +
      `APP_MAIL_FROM` + `APP_MAIL_APP_BASE_URL` + SMTP host/user/pass; SMTP works on-prem).
- [ ] `APP_CORS_ALLOWED_ORIGINS=https://<hostname>`, keep `SERVER_SESSION_COOKIE_SECURE=true`.
- [ ] Set `APP_ATTENDANCE_AGENT_TOKEN` if the punch agent is used; BOT/payroll vars as needed.
- [ ] Confirm `.env` is gitignored (`git check-ignore deploy/self-host/.env`).

## Phase 5 — Smoke test on an EMPTY DB
- [ ] Build the frontend with the portal unlocked and mocks off, ship `dist` → `/srv/glr/www`:
      `cd frontend && VITE_USE_MOCKS=false VITE_SELF_SERVICE_ONLY=false npm run build`
      then copy `dist/*` to the server's `/srv/glr/www`.
- [ ] `docker compose up -d` (backend + caddy + cloudflared later). Backend boots, Flyway
      creates all schemas fresh.
- [ ] **Login note:** bare `prod` has NO seed users. For this empty-DB check only, temporarily set
      `SPRING_PROFILES_ACTIVE=prod,demo` (+ `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,classpath:db/migration-demo`)
      to get demo logins, then revert. Real login is validated after Phase 8.
- [ ] Verify: front↔back↔db talk, login, upload/download to `/srv/glr/uploads`, **PDF renders in
      real Thai fonts** (open the PDF, check embedded fonts = AngsanaNew/Tahoma), Excel export.

## Phase 6 — Cloudflare Tunnel + Access
- [ ] Create a tunnel in Zero Trust → copy its token into `.env` (`CLOUDFLARE_TUNNEL_TOKEN`).
- [ ] Dashboard ingress: `https://<hostname>` → service `http://caddy:80`. Add the DNS record.
- [ ] `docker compose up -d cloudflared`; reach the app from the internet (no router change).
- [ ] Cloudflare **Access** policy: allow only authorized users. **Add bypass** for
      `/api/auth/login`, `/api/attendance/punch`, `/actuator/health` (service token or bypass rule)
      or login and the punch agent break.

## Phase 7 — Backup + restore drill  (`scripts/backup.sh`)
- [ ] Configure an rclone remote (or rsync target) to a NAS/storage **separate from the VM**.
- [ ] Schedule `backup.sh` (systemd timer/cron): pg_dump (5 schemas) + uploads → off-box.
- [ ] **Restore drill:** restore the latest dump into a throwaway DB, boot the app against it,
      open real data. A backup you have not restored does not count.

## Phase 8 — Migration rehearsal from Supabase  (`scripts/migrate-from-supabase.sh`)
- [ ] `export SUPABASE_DUMP_URL=postgres://...` (shell env only), run the script → dumps the 5
      schemas (incl. `flyway_schema_history`) and restores into the container.
- [ ] Verify the Flyway **set** printed (expect V11 drift + demo versions V21/V32/V46/V91.1/V139).
- [ ] Boot backend against the restored data → Flyway logs "up to date", applies **nothing** new.
- [ ] Spot-check row counts, sequences, uploads.

## Phase 9 — Full functional UAT
- [ ] Role-by-role: HR, attendance, payroll, sales/CRM, import (per-factory tracker), PDF/Excel.
- [ ] Verify **permissions/roles** and CSRF/session on the new single-origin domain.

## Phase 10 — Cutover / Go-live
- [ ] Announce maintenance; **freeze the old system** (Render/Supabase read-only or write-closed).
- [ ] Final `migrate-from-supabase.sh` (delta since rehearsal) + final uploads sync.
- [ ] Switch DNS/hostname to the new tunnel. Monitor `docker compose logs -f`, Caddy access log,
      Postgres slow queries, resource use.

## Phase 11 — Post go-live
- [ ] Keep Supabase/Render as fallback ≥1–2 weeks (through a payroll + attendance cycle).
- [ ] Confirm backups actually run and restore; watch metrics.
- [ ] Once stable: decommission the old stack, revoke old secrets, remove old credentials.
