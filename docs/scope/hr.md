# Scope: HR

Self service for every employee and the tools HR uses to run it. Index: [index.md](index.md).

### 10. Employee directory and profiles · existing
HR keeps the employee list and each record. Employees see their own profile and ask for changes, which HR approves. code in `hr/employee`, `hr/profile`, `features/employees`, `features/profile`, `features/profileRequests`

### 11. Requests and approvals · existing
One inbox for what a person asked for and one for what waits on their approval, with routing to the right manager, HR or the CEO. code in `hr/approval`, `features/requests`

### 12. Leave · existing
Leave requests with balances, the policy settings, advance notice counted in the employee's working days, sick leave certificates and vacation carry forward. code in `hr/leave`, `features/leave`

### 13. Overtime · existing
Overtime requests and approval, feeding payroll. code in `hr/overtime`, `features/overtime`

### 14. Attendance · existing
Punches arrive from the office scanners through a small agent. The system works out each day, lets staff ask for a correction, holds the work calendar and holidays, and mails a month end report. Late and early minutes are for reporting only. code in `hr/attendance`, `agents/attendance`, `features/attendance`, `features/attendanceCalendar`, `features/attendanceCorrection`

### 15. People insights · existing
One page of leave and check in figures for HR and the CEO. code in `hr/peopleinsights`, `features/insights`
