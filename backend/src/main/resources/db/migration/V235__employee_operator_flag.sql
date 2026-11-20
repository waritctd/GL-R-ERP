ALTER TABLE hr.employee ADD COLUMN is_operator BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN hr.employee.is_operator IS 'System operator (developer who runs the portal). Display identity only, grants no permission; set by hand.';
