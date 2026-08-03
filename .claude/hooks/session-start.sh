#!/bin/bash
# Ensures the designer-skills (IA / frontend-design / etc.) and the
# frontend-design@claude-plugins-official plugin from CLAUDE.md's
# "Frontend design skills" rule are present in every fresh session,
# not just the container they were first installed in.
set -euo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

cd "$CLAUDE_PROJECT_DIR"

# .agents/skills/* is committed to git; .claude/skills/* symlinks into it are
# gitignored (dev-local) and need recreating in every fresh container.
if [ -d .agents/skills ]; then
  mkdir -p .claude/skills
  for dir in .agents/skills/*/; do
    name="$(basename "$dir")"
    target=".claude/skills/$name"
    if [ ! -e "$target" ]; then
      ln -sfn "../../.agents/skills/$name" "$target"
    fi
  done
fi

if command -v claude >/dev/null 2>&1; then
  claude plugin marketplace add anthropics/claude-plugins-official >/dev/null 2>&1 || true
  claude plugin install frontend-design@claude-plugins-official >/dev/null 2>&1 || true
fi
