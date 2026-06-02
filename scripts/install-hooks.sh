#!/bin/sh
# Install git hooks for RocketPlan Android.
# Run this once after cloning, and after pulling changes that update hooks.

set -e

SCRIPTS_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$SCRIPTS_DIR/.." && pwd)"
GIT_HOOKS_DIR="$REPO_ROOT/.git/hooks"

echo "Installing git hooks from $SCRIPTS_DIR/hooks/ ..."
mkdir -p "$GIT_HOOKS_DIR"

if [ -f "$SCRIPTS_DIR/hooks/pre-commit" ]; then
    cp "$SCRIPTS_DIR/hooks/pre-commit" "$GIT_HOOKS_DIR/pre-commit"
    chmod +x "$GIT_HOOKS_DIR/pre-commit"
    echo "  ✓ pre-commit hook installed"
else
    echo "  ✗ pre-commit hook not found at $SCRIPTS_DIR/hooks/pre-commit"
fi

echo "Done."
