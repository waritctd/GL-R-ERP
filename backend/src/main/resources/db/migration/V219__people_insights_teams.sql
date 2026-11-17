-- People insights (leave + check-in analytics): who counts as a "team" and who heads it, plus two per-employee
-- switches. Schema only: which teams exist and who heads them is company configuration, applied outside Flyway so
-- no staff names or codes ever sit in this public repository.

CREATE TABLE hr.insight_team (
    team_id    BIGSERIAL PRIMARY KEY,
    name_th    VARCHAR(80) NOT NULL,
    sort_order INT NOT NULL DEFAULT 0
);

COMMENT ON TABLE hr.insight_team IS
    'A comparable team on the leave/check-in insights page. Members are derived from the heads'' reporting lines, never stored.';

CREATE TABLE hr.insight_team_head (
    team_id     BIGINT NOT NULL REFERENCES hr.insight_team(team_id) ON DELETE CASCADE,
    employee_id BIGINT NOT NULL REFERENCES hr.employee(employee_id),
    PRIMARY KEY (team_id, employee_id)
);

COMMENT ON TABLE hr.insight_team_head IS
    'Heads of a team (one or several). A head sees only their own team''s insights; the head is not counted as a member.';

CREATE TABLE hr.insight_employee_setting (
    employee_id   BIGINT PRIMARY KEY REFERENCES hr.employee(employee_id),
    time_exempt   BOOLEAN NOT NULL DEFAULT FALSE,
    detached_line BOOLEAN NOT NULL DEFAULT FALSE
);

COMMENT ON COLUMN hr.insight_employee_setting.time_exempt IS
    'TRUE = check-in time is not judged for this employee (e.g. field sales); their leave still counts.';
COMMENT ON COLUMN hr.insight_employee_setting.detached_line IS
    'TRUE = this employee and everyone below them are left out of every team, though still in the whole-company view.';
