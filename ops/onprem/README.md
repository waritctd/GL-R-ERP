# On-prem production stack

One Ubuntu 24.04 VM (user `glradmin`, repo at `~/glr`, Docker + compose v2) runs everything:

```
Cloudflare Tunnel (tunnel) ──> nginx (web: SPA + /api proxy) ──> backend ──> postgres (db)
```

Nothing is published on the LAN. `web` binds `127.0.0.1:8081` for local debugging only.
All commands below run from `~/glr/ops/onprem` unless stated. The compose project name is the
directory name, `onprem`, so the volumes are `onprem_pgdata` and `onprem_uploads`.

## First-time setup

1. Clone the production branch: `git clone -b main <repo-url> ~/glr`.
2. `cp .env.example .env && chmod 600 .env`, then fill it in. `GLR_IMAGE_TAG`, `DB_PASSWORD` and
   `TUNNEL_TOKEN` are required; compose refuses to start without them.
3. Get the backend image (private registry):
   `docker login ghcr.io -u <github-user> --password-stdin` (paste a PAT with `read:packages`),
   then `docker compose pull backend`. Offline alternative: `docker load < glr-hr-backend.tar`.
4. Build the frontend (same-origin `/api`, so no `VITE_API_BASE_URL`):
   ```
   cd ~/glr/frontend
   docker run --rm -u $(id -u):$(id -g) -e HOME=/tmp -v "$PWD:/app" -w /app node:22 sh -c "npm ci && npm run build"
   ```
5. `docker compose up -d`, then check `docker compose ps` and
   `docker compose exec web wget -qO- http://backend:8080/actuator/health` (expects `UP`).

## Upgrading

- Backend: set the new `GLR_IMAGE_TAG` in `.env`, `docker compose pull backend`,
  `docker compose up -d backend`. Flyway migrates on start. Take a backup first (`./backup.sh`).
- Frontend: `cd ~/glr && git pull`, rebuild (step 4 above), `docker compose restart web`.

## Backups

Nightly `pg_dump -Fc` to an SMB share on the Hyper-V host, mounted at `/mnt/glr-backup`.

Mount the share (once):

```
sudo apt install -y cifs-utils
sudo mkdir -p /mnt/glr-backup
sudo install -m 600 /dev/null /root/.smb-glr-backup
sudo nano /root/.smb-glr-backup      # username=<windows-user>  password=<...>  domain=<host-name>
```

Add to `/etc/fstab` (one line), then `sudo mount -a && mountpoint /mnt/glr-backup`:

```
//<host>/<share> /mnt/glr-backup cifs credentials=/root/.smb-glr-backup,uid=glradmin,gid=glradmin,file_mode=0600,dir_mode=0700,nofail,_netdev 0 0
```

`nofail` keeps the VM booting if the host is down; `backup.sh` then refuses to run (it requires
`/mnt/glr-backup` to be a mountpoint), so dumps never silently land on the VM's own disk.

Schedule it in `glradmin`'s crontab (`crontab -e`; `glradmin` must be in the `docker` group):

```
30 2 * * * flock -n /tmp/glr-backup.lock timeout 2h /home/glradmin/glr/ops/onprem/backup.sh >> /home/glradmin/glr-backup.log 2>&1
```

Nothing alerts on a failed run: check `/home/glradmin/glr-backup.log` and the share for last
night's files after any change, and periodically.

Each run writes `glr-YYYYmmdd-HHMMSS.dump` (verified with `pg_restore -l`) and
`glr-uploads-YYYYmmdd-HHMMSS.tgz` (the `onprem_uploads` volume: attachments stored on disk). Only
when both succeed does it prune `glr-*.dump` / `glr-uploads-*.tgz` older than `KEEP_DAYS` (30) and
`.*.partial` leftovers older than a day. Its tests: `bash ops/onprem/test_backup.sh`.

Restore test (monthly, and after any change to the backup setup):

```
f=$(ls -t /mnt/glr-backup/glr-*.dump | head -1)
docker compose exec -T db createdb -U postgres restore_test
docker compose exec -T db pg_restore -U postgres -d restore_test --no-owner --no-privileges --exit-on-error --single-transaction < "$f"
docker compose exec -T db psql -U postgres -d restore_test -At < counts.sql | head
docker compose exec -T db dropdb -U postgres restore_test
```

The table list and counts should look like production's at the dump time.

## Cutover (Render + Supabase + Vercel → this VM)

Never run Render and on-prem against live data at the same time: whichever is not serving must be
suspended or stopped before the other takes writes.

Before the day:

- **Attachments on Render**: Render's filesystem is ephemeral, so attachments the backend stored on
  disk there (commission, deposit, factory-quote and special-money files under `APP_UPLOADS_DIR`)
  are expected to be gone already; rows that reference them will 404 after cutover too. Confirm
  on the Render shell what, if anything, is still there and copy it into the `onprem_uploads`
  volume before suspending.
- **Vercel env**: check the Vercel project's environment variables for any `VITE_*` value the
  production build relied on; the on-prem build only sees what is in the repo.
- **Old address**: plan a Cloudflare redirect rule `glr-portal.com/*` → `https://glr-hub.com/$1`
  (301) for bookmarks and email links, to switch on at step 8.

On the day:

1. **Checkpoint**: take a Hyper-V checkpoint of the VM.
2. **Freeze**: announce downtime, then suspend the `gl-r-erp` service on Render (Dashboard →
   service → Suspend). From here nothing writes to Supabase.
3. **Final dump from Supabase** (on the VM). Use the session pooler URI from the Supabase dashboard;
   it is read without echo and passed by name, so it never appears in history or `ps`:
   ```
   mkdir -p ~/cutover && cd ~/glr/ops/onprem
   read -rs SUPABASE_URI && export SUPABASE_URI
   docker run --rm -e SUPABASE_URI -v ~/cutover:/out postgres:17 sh -c \
     'pg_dump "$SUPABASE_URI" -Fc -n hr -n hr_restricted -n sales -n customers -n price_catalog -f /out/supabase-final.dump'
   docker run --rm -i -e SUPABASE_URI postgres:17 sh -c 'psql "$SUPABASE_URI" -At' < counts.sql > ~/cutover/counts-supabase.txt
   unset SUPABASE_URI
   ```
4. **Clean restore** (the on-prem DB must be empty, not migrated by an earlier start).
   > ⚠ `docker volume rm onprem_pgdata` DELETES THE ON-PREM DATABASE. Only before go-live; never
   > after users have written data here.
   ```
   docker compose down
   docker volume rm onprem_pgdata
   docker compose up -d db
   docker compose exec -T db pg_restore -U postgres -d hris --no-owner --no-privileges \
     --exit-on-error --single-transaction < ~/cutover/supabase-final.dump
   ```
   Any error rolls the whole restore back; fix the cause and repeat this step.
5. **Compare**:
   ```
   docker compose exec -T db psql -U postgres -d hris -At < counts.sql > ~/cutover/counts-onprem.txt
   diff ~/cutover/counts-supabase.txt ~/cutover/counts-onprem.txt && echo MATCH
   ```
   On any difference: stop (`docker compose down`), un-suspend Render, and investigate.
6. **Start**: `docker compose up -d`; watch `docker compose logs -f backend` until Flyway and
   startup finish.
7. **Smoke test** via `http://127.0.0.1:8081` (SSH tunnel from a laptop): log in, open a deal, a
   quotation PDF (Thai fonts render), leave list, payroll page, upload and download an attachment.
8. **Route traffic**: in Cloudflare Zero Trust, move the trial hostname to `trial.glr-hub.com`,
   then give the production tunnel the public hostname `glr-hub.com` → `http://web:80`. Re-run the
   smoke test on `https://glr-hub.com`, then enable the `glr-portal.com` redirect.
9. **Attendance agent**: point its server URL at `https://glr-hub.com` (same
   `APP_ATTENDANCE_AGENT_TOKEN`) and confirm the next punches arrive. Also confirm whether the
   agent back-fills punches made during the outage; if not, tell HR which window is missing.
10. **Announce**: new address `https://glr-hub.com`; everyone must log in again.

Rollback (before on-prem has taken real writes): remove the `glr-hub.com` route or
`docker compose stop backend tunnel`, un-suspend Render, and tell users to keep using the old
address. After on-prem has taken writes, rolling back loses them unless they are dumped from here
and restored to Supabase first; decide that explicitly, never by running both.
