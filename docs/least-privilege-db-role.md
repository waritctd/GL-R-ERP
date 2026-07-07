# Least-privilege database role (`hr_app`) — rollout runbook

**Issue:** [#25](https://github.com/waritctd/GL-R-ERP/issues/25) — the app currently connects as the
Supabase `postgres` superuser/owner, so the `hr` / `hr_restricted` schema split provides no real
protection. This runbook switches the **runtime** connection to a dedicated least-privilege role,
`hr_app`.

Script: [`backend/db/roles/hr_app_least_privilege.sql`](../backend/db/roles/hr_app_least_privilege.sql)

---

## What `hr_app` can and cannot do

| Schema | Privilege |
|--------|-----------|
| `hr` | SELECT / INSERT / UPDATE / DELETE + sequence usage |
| `sales` | SELECT / INSERT / UPDATE / DELETE + sequence usage |
| `hr_restricted` | **SELECT only** (the app only reads PII; it never writes it) |
| anything else | nothing |

Not granted: superuser, `CREATEDB`, `CREATEROLE`, `BYPASSRLS`, DDL/ownership, or any other schema.

---

## The one wrinkle: migrations need DDL, the runtime role must not have it

The app runs **Flyway on startup**, and migrations need `CREATE`/`ALTER` — privileges a
least-privilege runtime role deliberately lacks. So **migrations and runtime must use different
roles**:

- **Migrations** run as an **admin/owner** role (e.g. `postgres`).
- **Runtime** runs as **`hr_app`** with **`APP_FLYWAY_ENABLED=false`**.

This is why the rollout migrates the DB *first* (as admin), then flips the app to `hr_app` with
Flyway disabled.

---

## One-time rollout

### 1. Apply the role + grants (as admin)

Connect to the **target** database as an admin/owner role and run the script, passing a strong
password (kept out of git — use your secret manager):

```bash
psql "$ADMIN_DATABASE_URL" \
  -v ON_ERROR_STOP=1 \
  -v app_password="'$(openssl rand -base64 32)'" \
  -f backend/db/roles/hr_app_least_privilege.sql
```

> Run it **as the same admin role Flyway uses for migrations** — `ALTER DEFAULT PRIVILEGES` only
> covers objects created by the role that ran it, so this keeps future migration tables auto-granted
> to `hr_app`.

Record the generated password in your secret store; you'll need it in step 3.

### 2. Make sure the schema is fully migrated (as admin)

`hr_app` can't run migrations, so bring the DB to the latest version first. Either it's already
current, or run the app once with the **admin** credentials and `APP_FLYWAY_ENABLED=true`, then stop
it. Confirm:

```sql
SELECT version, description, success
  FROM hr.flyway_schema_history
 ORDER BY installed_rank DESC
 LIMIT 5;
```

### 3. Point the runtime at `hr_app` (Render)

Update the service's environment variables:

| Variable | New value |
|----------|-----------|
| `SPRING_DATASOURCE_USERNAME` | `hr_app` |
| `SPRING_DATASOURCE_PASSWORD` | the password from step 1 |
| `APP_FLYWAY_ENABLED` | `false` |

Keep `SPRING_DATASOURCE_URL` as-is (add `?sslmode=require` for Supabase if not already present).
Redeploy.

### 4. Verify

With the app running as `hr_app`, exercise the paths that touch each schema:

- **Login** + `GET /api/auth/me` → sessions read/write `hr.spring_session`.
- **`GET /api/employees`** and open an employee detail as HR → reads `hr` **and** `hr_restricted`
  (sensitive PII) — confirms the SELECT grant on the restricted schema.
- **Create a ticket** → writes `sales` and advances `sales.ticket_code_seq`.
- **Create/edit an employee** → writes `hr` and advances `hr.employee_code_seq`; check an
  `hr.audit_log` row appears.
- **Attendance punch** → writes `hr.attendance_punch`.

A quick DB-side check that the role is not a superuser:

```sql
SELECT rolsuper, rolcreatedb, rolcreaterole, rolbypassrls
  FROM pg_roles WHERE rolname = 'hr_app';   -- expect all false
```

---

## Future deploys that add migrations

Because runtime Flyway is disabled, when a release adds new `V##` migrations:

1. Run them once with **admin** credentials (a one-off boot with admin creds +
   `APP_FLYWAY_ENABLED=true`, or `flyway migrate` from CI/your machine) **before** the app pods that
   expect them go live.
2. Deploy the app as usual (still `hr_app`, Flyway disabled).

`ALTER DEFAULT PRIVILEGES` (set in step 1) means the new tables/sequences are already usable by
`hr_app` — no re-granting needed, as long as migrations keep running as that same admin role.

---

## Rollback

If anything misbehaves, revert the runtime to the admin connection:

| Variable | Value |
|----------|-------|
| `SPRING_DATASOURCE_USERNAME` | `postgres` |
| `SPRING_DATASOURCE_PASSWORD` | the admin password |
| `APP_FLYWAY_ENABLED` | `true` |

Redeploy. `hr_app` can be left in place (harmless) or dropped with
`DROP OWNED BY hr_app; DROP ROLE hr_app;` (as admin).

---

## Notes

- If HR ever needs to **write** special-category PII from the app, add
  `INSERT, UPDATE ON <table> IN SCHEMA hr_restricted` at that point — keep it scoped to the specific
  table rather than the whole schema.
- The grants are idempotent; re-running the script after adding schemas/tables is safe and is the
  intended way to re-sync privileges if you ever bypass the default-privileges path.
