SET search_path = hr, public;

-- ---------------------------------------------------------------------
-- Register the warehouse ZKTeco SC700 (same family/board as the showroom
-- scanner, driven by the same env-driven agent, agents/attendance/showroom_agent.py,
-- with ATTENDANCE_SITE_CODE=WAREHOUSE / ATTENDANCE_DEVICE_CODE=WAREHOUSE_SC700).
-- This is registry metadata only: the ingestion pipeline already supports
-- multi-site/multi-device operation (hr.attendance_device, hr.attendance_punch
-- both key off device_code/site_code), so no schema change is needed beyond
-- this one row. See agents/attendance/WAREHOUSE_SCANNER_SETUP.md for the full
-- rollout runbook.
--
-- ip_address is left NULL: the warehouse scanner's LAN IP is not yet known
-- (mini PC + VPN routing is still being set up on-site). The column is purely
-- informational here -- the agent process reads its own ZK_HOST env var to
-- reach the device, it does not read this row -- so NULL is safe to ship and
-- must be filled in (via an HR-facing device update, once one exists, or a
-- follow-up migration) once the on-site IP is confirmed.
-- ---------------------------------------------------------------------

INSERT INTO hr.attendance_device (device_code, site_code, device_name, model, ip_address, tcp_port, is_active)
VALUES ('WAREHOUSE_SC700', 'WAREHOUSE', 'Warehouse ZKTeco SC700', 'ZKTeco SC700', NULL, 4370, TRUE)
ON CONFLICT (device_code) DO NOTHING;
