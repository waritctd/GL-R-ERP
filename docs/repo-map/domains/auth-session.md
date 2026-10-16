---
title: Auth & Session
layer: Platform
schemas: [hr]
tags: [repo-map, domain/auth-session]
synced: develop@ef9b17dc
---
# Auth & Session
> Login, session (JDBC-backed), CSRF, password reset, and the security filter chain.

**Layer:** Platform  ·  **DB schemas:** `hr`

## Backend
### `th.co.glr.hr.auth` — [auth/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/auth)
- `backend/src/main/java/th/co/glr/hr/auth/ApplicationRoles.java`
- `backend/src/main/java/th/co/glr/hr/auth/AuthController.java`
- `backend/src/main/java/th/co/glr/hr/auth/AuthResponse.java`
- `backend/src/main/java/th/co/glr/hr/auth/AuthService.java`
- `backend/src/main/java/th/co/glr/hr/auth/CachedBodyHttpServletRequest.java`
- `backend/src/main/java/th/co/glr/hr/auth/ChangePasswordRequest.java`
- `backend/src/main/java/th/co/glr/hr/auth/DealEntryAccess.java`
- `backend/src/main/java/th/co/glr/hr/auth/DivisionAccessPolicy.java`
- `backend/src/main/java/th/co/glr/hr/auth/EmployeeAuthRepository.java`
- `backend/src/main/java/th/co/glr/hr/auth/EmployeeLoginRecord.java`
- `backend/src/main/java/th/co/glr/hr/auth/ForgotPasswordRequest.java`
- `backend/src/main/java/th/co/glr/hr/auth/LoginAttemptTracker.java`
- `backend/src/main/java/th/co/glr/hr/auth/LoginRateLimitFilter.java`
- `backend/src/main/java/th/co/glr/hr/auth/LoginRequest.java`
- `backend/src/main/java/th/co/glr/hr/auth/MessageResponse.java`
- `backend/src/main/java/th/co/glr/hr/auth/MustChangePasswordFilter.java`
- `backend/src/main/java/th/co/glr/hr/auth/PasswordBackfillRunner.java`
- `backend/src/main/java/th/co/glr/hr/auth/PasswordResetService.java`
- `backend/src/main/java/th/co/glr/hr/auth/PasswordResetTokenCodec.java`
- `backend/src/main/java/th/co/glr/hr/auth/PasswordResetTokenRepository.java`
- `backend/src/main/java/th/co/glr/hr/auth/ResetPasswordRequest.java`
- `backend/src/main/java/th/co/glr/hr/auth/SessionAccountResolver.java`
- `backend/src/main/java/th/co/glr/hr/auth/SessionContext.java`
- `backend/src/main/java/th/co/glr/hr/auth/SessionSecurityFilter.java`
- `backend/src/main/java/th/co/glr/hr/auth/TemporaryPasswordGenerator.java`
- `backend/src/main/java/th/co/glr/hr/auth/UserPrincipal.java`
### `th.co.glr.hr.config` — [config/](https://github.com/waritctd/GL-R-ERP/blob/develop/backend/src/main/java/th/co/glr/hr/config)
- `backend/src/main/java/th/co/glr/hr/config/AppProperties.java`
- `backend/src/main/java/th/co/glr/hr/config/CorrelationIdFilter.java`
- `backend/src/main/java/th/co/glr/hr/config/CorsConfig.java`
- `backend/src/main/java/th/co/glr/hr/config/CsrfCookieFilter.java`
- `backend/src/main/java/th/co/glr/hr/config/FlywayEagerInitConfig.java`
- `backend/src/main/java/th/co/glr/hr/config/JacksonConfig.java`
- `backend/src/main/java/th/co/glr/hr/config/OpenApiConfig.java`
- `backend/src/main/java/th/co/glr/hr/config/PasswordConfig.java`
- `backend/src/main/java/th/co/glr/hr/config/ProductionReadinessConfig.java`
- `backend/src/main/java/th/co/glr/hr/config/SchedulingConfig.java`
- `backend/src/main/java/th/co/glr/hr/config/SecurityConfig.java`
- `backend/src/main/java/th/co/glr/hr/config/SecurityHeadersFilter.java`

## Frontend
### `features/auth` — [auth/](https://github.com/waritctd/GL-R-ERP/blob/develop/frontend/src/features/auth)
- `frontend/src/features/auth/ChangePasswordModal.jsx`
- `frontend/src/features/auth/ForgotPasswordPage.jsx`
- `frontend/src/features/auth/LoginPage.jsx`
- `frontend/src/features/auth/ResetPasswordPage.jsx`

## DB
- Schemas: `hr`
- Key migrations: `V19 spring_session`

## Related
[[employee|Employee & Self-service]]

## Gotchas
- SecurityConfig is default-deny; role checks live in CONTROLLERS, not the filter chain.
- CSRF is app-rolled (CsrfCookieFilter), NOT Spring Security — do not 're-enable' Spring CSRF.
- Real AuthService refuses {role}-only login with 403; demo accounts are email+password (Demo@2026).

> Authoritative details + history live in `CLAUDE.md` / `AGENTS.md` at the repo root.
