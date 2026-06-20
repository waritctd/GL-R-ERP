SET search_path = hr, public;

ALTER TABLE IF EXISTS hr.profile_change_request
    DROP CONSTRAINT IF EXISTS profile_change_request_requested_by_user_id_fkey,
    DROP CONSTRAINT IF EXISTS profile_change_request_reviewed_by_user_id_fkey,
    DROP COLUMN IF EXISTS requested_by_user_id,
    DROP COLUMN IF EXISTS reviewed_by_user_id;

DROP TABLE IF EXISTS hr.user_role;
DROP TABLE IF EXISTS hr.role_permission;
DROP TABLE IF EXISTS hr.app_user;
DROP TABLE IF EXISTS hr.role;
DROP TABLE IF EXISTS hr.permission;
