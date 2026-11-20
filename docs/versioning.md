# Versioning

Every release to production gets its own version, written MAJOR.MINOR.PATCH (for example 2.0.1, 2.1.0).

## Which number goes up

- **PATCH** (2.0.0 to 2.0.1): a bug fix, a wording or layout correction, a performance or security fix, or an internal change that ships. There is nothing new for users to learn.
- **MINOR** (2.0.1 to 2.1.0): a new feature, screen, document, report or permission, or a changed flow that users must be told about. It resets PATCH to 0. For example, "tax invoice before delivery" would ship as v2.1.0.
- **MAJOR** (2.1.0 to 3.0.0): a change to how a team works that needs retraining, a change that moves or restructures data, or a new module going live. Only on Ploy's word. It resets MINOR and PATCH to 0.

## Rules

- A release with several changes takes the highest level among them, as one bump.
- A migration alone does not decide the level. What users see does.
- A frontend-only release still gets a version.
- Each session that hands a SHA to the deployer states "patch" or "minor". The deployer assigns the number.
- Several releases in one day each take the next number. No date-and-letter tags.

## One number everywhere

The same number is used for the image tag, the git tag on main, the release email, the มีอะไรใหม่ entry and the label in the portal menu.

## Where the number is set

A bump touches exactly these:

- `frontend/package.json` (`version`) and the two matching fields at the top of `frontend/package-lock.json`. The portal menu reads it at build time (`src/app/version.js`).
- `backend/pom.xml` (the project `<version>`, not the parent's). The jar is built as `glr-hr-backend.jar` (`<finalName>`), so the Dockerfile needs no edit; the API docs read the version from the jar.

The มีอะไรใหม่ module for a release is a new file named for its version, `frontend/src/features/whatsNew/releases/v2.0.0.js`, with screenshots in `frontend/public/whats-new-img/v2.0.0/`. Its id, date label and screenshot folder are written out as literals, so the notice keeps its identity when the build version moves on. Register it in `releases/index.js`. The folder is `whats-new-img`, not `whats-new`: a folder named like the route makes a static server (nginx `try_files $uri $uri/`) answer `/whats-new/<id>` with 403 instead of the page.
