ALTER TABLE sales.ticket
    ADD COLUMN keyed_by BIGINT NULL REFERENCES hr.employee(employee_id);

COMMENT ON COLUMN sales.ticket.keyed_by IS 'The employee who typed the deal in (the actor on every new deal; equals created_by when the owner keyed it herself; created_by is the owner: sells it, earns the commission, is notified). Keyer also passes every owner access check. NULL on rows from before V202.';
