# Vendored skill — provenance

This directory is **third-party content**, vendored into the repo rather than authored here.

| | |
|---|---|
| Upstream | `currents-dev/playwright-best-practices-skill` (GitHub) |
| Version | 1.2 |
| Author | currents.dev |
| License | MIT (declared in `SKILL.md` frontmatter) |
| Vendored | 2026-08-08 |

## Local modification — one line, deliberate

Only `SKILL.md`'s `description:` was changed. Everything else is upstream and untouched.

The upstream description is 985 characters — it enumerates ~50 trigger scenarios. That string is
resident in **every** session's skill listing (~246 est. tokens), which is more than a third of
what all the other project skills cost combined. It was shortened to ~257 characters, keeping the
triggers that matter here.

**When re-syncing from upstream, re-apply that trim** or the listing cost comes back. The original
description, verbatim:

```
Use when writing Playwright tests, fixing flaky tests, debugging failures, implementing Page Object Model, configuring CI/CD, optimizing performance, mocking APIs, handling authentication or OAuth, testing accessibility (axe-core), file uploads/downloads, date/time mocking, WebSockets, geolocation, permissions, multi-tab/popup flows, mobile/responsive layouts, touch gestures, GraphQL, error handling, offline mode, multi-user collaboration, third-party services (payments, email verification), console error monitoring, global setup/teardown, test annotations (skip, fixme, slow), test tags (@smoke, @fast, @critical, filtering with --grep), project dependencies, security testing (XSS, CSRF, auth), performance budgets (Web Vitals, Lighthouse), iframes, component testing, canvas/WebGL, service workers/PWA, test coverage, i18n/localization, Electron apps, or browser extension testing. Covers E2E, component, API, visual, accessibility, security, Electron, and extension testing.
```
