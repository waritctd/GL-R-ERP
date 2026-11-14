# On-prem production

Since 14 Nov 2026 production runs on our own server. The website is on Vercel; everything behind
`/api` runs in Docker on a Linux VM on the office's Dell T360.

```
browser ──> https://glr-hub.com (Vercel: the website)
                 │  /api/* is rewritten to
                 ▼
            https://api.glr-hub.com ──> Cloudflare Tunnel ──> VM: nginx (web) ──> backend ──> Postgres (db)
```

| Piece | Where | Notes |
|---|---|---|
| Website | Vercel project `glr-hub`, deploys `main` | Root Directory is `frontend/`, so **`frontend/vercel.json` is the config Vercel uses**. Keep the root `vercel.json` the same. |
| API address | `api.glr-hub.com` | Cloudflare Tunnel `glr-hub`, one route: `api.glr-hub.com → http://web:80` |
| Server | Hyper-V VM `GLR-ERP-PROD` on the Windows host, Ubuntu 24.04 | User `glradmin`, repo at `~/glr`, stack in `~/glr/ops/onprem` (compose project `onprem`) |
| Containers | `db` Postgres 17 (database `hris`), `backend` (`ghcr.io/waritctd/glr-hr-backend:$GLR_IMAGE_TAG`), `web` nginx, `tunnel` cloudflared | `web` answers `/api/` only; every other path is a 404 |
| Settings | `~/glr/ops/onprem/.env` on the VM | Never in git. Template: `.env.example` |
| Backups | Nightly 02:30 (Bangkok) to a share on the Windows host, mounted at `/mnt/glr-backup` | Database dump + uploaded files, 30 days kept |
| Old address | `glr-portal.com` | Redirects (307) to `glr-hub.com`, from both `vercel.json` files |
| Old hosting | Render service `gl-r-erp` **suspended**, Supabase project kept as-is | Rollback only. Never un-suspend Render while the server is live: both would run jobs and send emails. |

All commands below run on the VM from `~/glr/ops/onprem` unless stated.

## Getting in

- **The VM**: join the team's Tailscale network, then `ssh glradmin@glr-erp-prod`. SSH accepts keys
  only. To add a person, append their public key (one `ssh-ed25519 …` line) to
  `~/.ssh/authorized_keys` with `>>`, never `>`. To remove them, delete the line.
- **`sudo`** needs `glradmin`'s password; ask the owner.
- **Locked out of SSH**: on the Windows host, Hyper-V Manager → `GLR-ERP-PROD` → Connect, and log in
  on the console. Type by hand: pasting through remote-desktop tools garbles symbols.
- **The Windows host** (Hyper-V, checkpoints, the attendance agent) is not on Tailscale. Open its
  desktop through the usual remote-desktop route. Hyper-V commands only work there: if
  `Get-VM` finds nothing, run `hostname` and check you are on the host, not your own PC.

## Releasing a new version (backend image + frontend)

The backend ships as a Docker image that contains the licensed Thai fonts used in quotation and
payroll PDFs. The fonts are not in git, so the image is built on the machine that has them in
`backend/fonts/`, never by CI. The website is built by Vercel from `main`.

**Order: backend first, then frontend.** The new website may call API endpoints that only the new
backend has. The old website keeps working against the new backend for the few minutes in between,
as long as the release does not remove or rename an endpoint the old one uses.

### 1. Pick a tag

`vYYYY-MM-DD`, for example `v2026-11-20`. A second release on the same day adds a letter:
`v2026-11-20b`. Never reuse or overwrite a tag: it is the record of what runs.

### 2. Build and push the image (on the Mac with the fonts)

Check out the exact commit you are releasing (the head of the release PR into `main`), then:

```bash
docker login ghcr.io -u <github-user>          # once; a classic PAT with write:packages
export GLR_IMAGE_REPO=ghcr.io/waritctd/glr-hr-backend
./scripts/build-push-backend-image.sh v2026-11-20
```

The script builds for `linux/amd64` (the server's CPU, also on Apple-silicon Macs), fails if a font
face is missing or substituted, stamps the image with the git commit, and pushes it. Check the
stamp matches what you meant to release:

```bash
docker image inspect ghcr.io/waritctd/glr-hr-backend:v2026-11-20 \
  --format '{{.Architecture}} {{index .Config.Labels "org.opencontainers.image.revision"}}'
```

### 3. Get the image onto the server

Either way works; the second is what we used on cutover night.

- **Pull on the server** (needs a one-time `docker login ghcr.io` on the VM with a PAT that has
  `read:packages`):
  ```bash
  docker pull ghcr.io/waritctd/glr-hr-backend:v2026-11-20
  ```
- **Copy from the Mac over SSH** (no registry login on the server; about 1.8 GB, a few minutes over
  Tailscale):
  ```bash
  docker save ghcr.io/waritctd/glr-hr-backend:v2026-11-20 | gzip -1 \
    | ssh glradmin@glr-erp-prod 'gunzip | docker load'
  ```
  On an Apple-silicon Mac, an image pulled from ghcr needs `docker pull --platform linux/amd64 …`
  first, or the pull fails with "no matching manifest for linux/arm64".

Then check it on the server with the same `docker image inspect` line as in step 2: `amd64` and
the right commit.

### 4. Deploy the backend (on the server)

Do it outside working hours: **restarting the backend logs everyone out**, because sessions live in
the backend's memory.

```bash
cd ~/glr/ops/onprem
./backup.sh                                                   # fresh backup first, always
sed -i 's|^GLR_IMAGE_TAG=.*|GLR_IMAGE_TAG=v2026-11-20|' .env
docker compose up -d backend
docker compose logs -f backend                                # wait for "Started HrBackendApplication", then Ctrl+C
```

In the log, Flyway either says `Schema "hr" is up to date` or `Successfully applied N migrations`.
Note which: it decides how a rollback works (below). Then check:

```bash
docker compose exec web wget -qO- http://backend:8080/actuator/health            # {"status":"UP"}
curl -s -o /dev/null -w "%{http_code}\n" https://api.glr-hub.com/api/auth/me      # 401
```

### 5. Release the website

Merge the release PR into `main`. Vercel builds and deploys `glr-hub.com` in about a minute. Hard
refresh (Cmd/Ctrl+Shift+R), log in, and click through the changed screens. Open one quotation PDF
to confirm the Thai fonts.

### 6. Clean up

Keep the previous image for a quick rollback; remove older ones after a week:

```bash
docker image ls ghcr.io/waritctd/glr-hr-backend
docker image rm ghcr.io/waritctd/glr-hr-backend:<old-tag>
```

`GLR_IMAGE_TAG` in the server's `.env` is the record of what runs. `render.yaml` is no longer used
for production.

### Rolling back a release

- **No new migration ran** (`Schema … is up to date` in step 4): put the previous tag back in
  `.env` and `docker compose up -d backend`. Revert the release PR to roll the website back.
- **Migrations ran** (`Successfully applied N migrations`): the old image may not work with the
  newer schema. Either fix forward with a new image, or restore the backup from step 4 (see
  [Restoring from a backup](#restoring-from-a-backup)). A restore loses everything written since
  that backup, so decide it explicitly.

## Changing settings

The backend reads `.env` only when its container is created:

```bash
nano .env
docker compose up -d backend          # recreates the backend; logs everyone out
```

- Type secrets with `read -rs` rather than pasting them into an editor. A paste can carry an
  invisible carriage return, and once a token got pasted twice in a row.
  ```bash
  read -rs V        # type the line by hand, then paste the value and press Enter
  sed -i "s|^APP_MAIL_RESEND_API_KEY=.*|APP_MAIL_RESEND_API_KEY=$V|" .env; unset V
  ```
- `APP_CORS_ALLOWED_ORIGINS` must be `https://glr-hub.com,https://www.glr-hub.com`. The backend
  rejects a login whose page address is not on that list with `403 Invalid CORS request`.
- The `prod` profile refuses to start while a required setting is missing; the backend log names
  it. Missing optional ones (the BOT FX and holiday tokens) only switch those features off, with a
  warning at start.
- `BACKEND_HEAP` is the Java memory limit (default 2g; the server currently uses `1g` because the
  VM has about 3 GB).

## Updating the stack files from git

The server's clone is shallow, so update it with fetch + reset, not `git pull`:

```bash
cd ~/glr
git fetch --depth 1 origin main && git reset --hard FETCH_HEAD
cd ops/onprem
docker compose up -d                  # recreates only services whose config changed
```

For an nginx-only change you can reload instead of recreating:
`docker compose exec web nginx -t && docker compose exec web nginx -s reload`.
Don't hand-edit tracked files on the server. If you had to, `git checkout -- <file>` before the
next update.

## Website (Vercel)

- **Two `vercel.json` files.** Vercel builds from `frontend/`, so `frontend/vercel.json` decides the
  `/api` rewrite, the old-domain redirect and the security headers. Change both files together, or
  the root one drifts. On cutover night the `frontend/` one still pointed at Render, and every
  login failed with 503.
- **Maintenance page**: a `frontend/.env.production` containing `VITE_MAINTENANCE=true`, merged to
  `main`, swaps the whole site for a "being migrated" page. It is git-ignored, so add it with
  `git add -f`. Delete it and merge to bring the site back.
- **PR previews** are behind Vercel's login. To log in on one, temporarily add the preview's
  address to `APP_CORS_ALLOWED_ORIGINS` (see [Changing settings](#changing-settings)), test, then
  remove it again.
- **Domains** (Vercel → project → Settings → Domains): `glr-hub.com` connected to Production;
  `www.glr-hub.com` redirects (308) to it; `glr-portal.com` and `www.glr-portal.com` stay attached
  for the redirect.

## Cloudflare

| Record | Points to | Proxy |
|---|---|---|
| `glr-hub.com` | CNAME to the value Vercel shows under Domains | DNS only (grey) |
| `www.glr-hub.com` | the same CNAME | DNS only (grey) |
| `api.glr-hub.com` | Tunnel `glr-hub` | Proxied (orange) |

- The tunnel's token is `TUNNEL_TOKEN` in `.env`. The tunnel has one route:
  `api.glr-hub.com → http://web:80`.
- **Bot Fight Mode is off and there is no login rate-limit rule.** API requests arrive from
  Vercel's servers, so both would block normal users. Logins are still protected by the backend's
  own lockout: 5 failures per account and 20 per IP in 15 minutes, keyed on the visitor's real IP
  from `X-Forwarded-For`, which Vercel sets and nginx passes through.

## Backups

Installed on 14 Nov 2026 in `glradmin`'s crontab (`crontab -l`):

```
30 2 * * * flock -n /tmp/glr-backup.lock timeout 2h /home/glradmin/glr/ops/onprem/backup.sh >> /home/glradmin/glr-backup.log 2>&1
```

Each run writes `glr-YYYYmmdd-HHMMSS.dump` (the database, checked with `pg_restore -l`) and
`glr-uploads-YYYYmmdd-HHMMSS.tgz` (files stored on disk: commission, deposit, factory-quote and
special-money attachments) to `/mnt/glr-backup`. Only when both succeed does it delete files older
than 30 days. It refuses to run if the share is not mounted, so dumps never fill the VM's own disk.

**Nothing alerts on a failed run.** Check `tail ~/glr-backup.log` and `ls -lt /mnt/glr-backup | head`
weekly and after any change. Run one by hand any time with `./backup.sh`; its tests are
`bash test_backup.sh`.

The share is a folder on the Windows host, shared to a dedicated local Windows user. The mount
lives in `/etc/fstab`, with the credentials in `/root/.smb-glr-backup` (root-only):

```
//<host>/<share> /mnt/glr-backup cifs credentials=/root/.smb-glr-backup,uid=glradmin,gid=glradmin,file_mode=0600,dir_mode=0700,nofail,_netdev 0 0
```

**Restore test**, monthly, into a scratch database:

```bash
f=$(ls -t /mnt/glr-backup/glr-2*.dump | head -1)
docker compose exec -T db createdb -U postgres restore_test
docker compose exec -T db pg_restore -U postgres -d restore_test --no-owner --no-privileges --exit-on-error --single-transaction < "$f"
docker compose exec -T db psql -U postgres -d restore_test -At < counts.sql | head
docker compose exec -T db dropdb -U postgres restore_test
```

## Restoring from a backup

⚠ This replaces the live database. Everything written after the backup is lost.

```bash
cd ~/glr/ops/onprem
f=/mnt/glr-backup/glr-<timestamp>.dump
docker compose stop backend
docker compose exec -T db dropdb -U postgres hris
docker compose exec -T db createdb -U postgres hris
docker compose exec -T db pg_restore -U postgres -d hris --no-owner --no-privileges --exit-on-error --single-transaction < "$f"
docker run --rm -v onprem_uploads:/u -v /mnt/glr-backup:/b:ro alpine:3.20 \
  sh -c 'cd /u && tar xzf /b/glr-uploads-<timestamp>.tgz'
docker compose start backend
```

`--single-transaction` means any error leaves the database empty rather than half-restored. A
`schema … already exists` error means you restored into a database that was not empty.

## When something looks wrong

| Symptom | Likely cause | Check / fix |
|---|---|---|
| Site shows the "being migrated" page | `frontend/.env.production` is on `main` | Delete it, merge |
| Login fails; browser console shows 503 on `/api/auth/login` | Vercel's `/api` rewrite points at something that is down | `frontend/vercel.json` must point at `https://api.glr-hub.com` |
| `403 Invalid CORS request` | The page's address is not in `APP_CORS_ALLOWED_ORIGINS` | Fix `.env`, `docker compose up -d backend` |
| Cloudflare error page on `api.glr-hub.com` | Tunnel or a container is down | `docker compose ps`, `docker compose logs tunnel` |
| Backend restarts in a loop | A required setting is missing, or the database is down | `docker compose logs backend` |
| Everyone was logged out | The backend restarted | Expected after any backend restart |
| PDFs show boxes instead of Thai | Image built without the fonts | Rebuild with the script; never build the image another way |
| VM slow, backend killed | Out of memory. The VM shares 16 GB with the attendance system, SQL Server and ESET on the host | `free -h`; raise the VM's minimum memory in Hyper-V |
| `Get-VM` finds no virtual machine | You are not on the Windows host | Run `hostname`; open the host's desktop |

Logs: `docker compose logs -f backend` (or `web`, `tunnel`, `db`). nginx's access log shows each
request's forwarded IPs as `"visitor, vercel"`.

## Attendance agent

The agent that sends fingerprint and face punches runs on the Windows host and reads the API
address from `ATTENDANCE_API_URL`. It must be
`https://api.glr-hub.com/api/attendance/punch` (before the cutover it was the Render address).
After changing it, restart the agent's service and check that the next punch appears in the app.
The import scripts in `agents/attendance/` still default to the Render address; pass
`--api-base-url https://api.glr-hub.com`.

## Rebuilding the server from scratch

1. Ubuntu 24.04 VM with Docker and the compose plugin; `glradmin` in the `docker` group.
2. `git clone --depth 1 -b main https://github.com/waritctd/GL-R-ERP.git ~/glr`
3. `cp ops/onprem/.env.example ops/onprem/.env && chmod 600 ops/onprem/.env` and fill it in.
   `GLR_IMAGE_TAG`, `DB_PASSWORD` and `TUNNEL_TOKEN` are required.
4. Get the image onto the server (see [step 3](#3-get-the-image-onto-the-server) above).
5. Mount the backup share and install the cron line (see [Backups](#backups)).
6. `docker compose up -d --wait db`, restore the latest backup (see
   [Restoring from a backup](#restoring-from-a-backup)), then `docker compose up -d`.

## Cutover record (14 Nov 2026)

- **Maintenance:** the maintenance page went live with PR 1296.
- **Final copy of the data:** Render was suspended, then the final Supabase dump was taken (06:12,
  61 MB). It is kept in `~/cutover/` on the VM and on the backup share.
- **Restore:** the strict restore into the empty `hris` database took 91 s. Row counts for all 162
  tables matched Supabase; the schema was at V208. The backend ran image `v2026-11-12d`.
- **Go-live:** PR 1297 switched the website to `api.glr-hub.com` and removed the maintenance page;
  PR 1298 made the old home page redirect too. Later that morning commit `98b47b0c` put the maintenance page back on `main`;
  it stays until `frontend/.env.production` is deleted (see Website (Vercel)).
- **Rollback:** Render and Supabase were kept untouched. Rolling back now would lose everything
  written on the server since go-live, unless it is dumped here and restored into Supabase first.
- **Clean-up:** delete `~/cutover/` (it holds full personal data) once the cutover is signed off.
