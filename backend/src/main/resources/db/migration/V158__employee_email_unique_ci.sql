-- One employee per email address, compared the way logging in compares it.
--
-- WHY. hr.employee.email is the login identity. EmployeeAuthRepository#findByEmail resolves it with
--
--     WHERE e.email IS NOT NULL AND btrim(e.email) <> ''
--       AND (LOWER(btrim(e.email)) = LOWER(:email) OR LOWER(substring(e.email from '...')) = LOWER(:email))
--     ORDER BY e.is_active DESC, e.employee_id
--     LIMIT 1
--
-- so the match is already case- and whitespace-insensitive. What was missing is the other half:
-- nothing stopped TWO rows existing whose addresses differ only in case. V11.1 and V47 both create
-- idx_employee_email_lower over exactly this expression, but as a PLAIN index -- it makes the lookup
-- fast, it never made it unique. With two such rows both satisfy the predicate and the LIMIT 1
-- silently picks whichever sorts first, so the password that works depends on row order rather than
-- on who is typing. That reads to the person affected as "my email only works sometimes", and
-- nothing in the application surfaces it.
--
-- The application side now normalises on write (EmployeeRepository#normalizeEmail covers create,
-- update and updateEmail -- the last being how ProfileRequestService applies an approved change), so
-- new rows are canonical. This index is the half that cannot be forgotten: a constraint holds
-- against direct SQL, a future repository method, and a seed migration alike.
--
-- SAFE TO APPLY -- checked against the real databases rather than assumed, 2026-08-31:
--   * prod (tdyzcqzxmhtxpbouewud): 0 case-collisions; 208 rows, 100 with an address, 108 NULL, 0 blank
--   * UAT  (wuypxdznuhhluwzncafh): 0 case-collisions, 0 blank
-- This matters because CREATE UNIQUE INDEX over duplicate data FAILS, and Flyway runs at boot -- a
-- dirty table would take the whole backend down on deploy, not just skip the migration.
--
-- PARTIAL, mirroring findByEmail's own guard. Rows the login predicate already excludes are exactly
-- the rows uniqueness must not constrain:
--   * NULL -- 108 of them in prod today. Postgres treats NULLs as distinct in a unique index anyway,
--     so this is belt-and-braces for that case rather than load-bearing.
--   * '' (blank) -- NOT distinct. Postgres considers two empty strings equal, so without the
--     predicate the SECOND employee saved with a blank email would be rejected outright. None exist
--     in prod or UAT right now, which is precisely why this is easy to get wrong and never notice:
--     the failure would first appear the day someone clears an address, in an unrelated code path.
CREATE UNIQUE INDEX IF NOT EXISTS uq_employee_email_lower_ci
    ON hr.employee (LOWER(btrim(email)))
 WHERE email IS NOT NULL AND btrim(email) <> '';

-- Now redundant: same expression, strictly weaker guarantee. The new unique index serves every
-- lookup the old one served -- findByEmail's WHERE implies this index's predicate, so the planner
-- can still use it -- while also enforcing what the old one only indexed. Dropped AFTER the create
-- so that if the create ever fails on some environment, Flyway's transaction rolls both back and
-- that database is left with the index it already had rather than with none.
DROP INDEX IF EXISTS hr.idx_employee_email_lower;

COMMENT ON INDEX hr.uq_employee_email_lower_ci IS
    'One employee per address, matching EmployeeAuthRepository#findByEmail''s case- and whitespace-insensitive comparison. Partial so NULL and blank emails stay unconstrained, as the login predicate excludes them.';
