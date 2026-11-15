#!/usr/bin/env bash
# The repo's checks. Exit 0 only when the branch is fit to review.
# Mirrors CI (kotlin-ci-shared): `mise run lint` + `mise run test`, plus a full build.
set -euo pipefail

# The gate runs inside a fresh worktree whose mise.toml mise has never seen;
# trust this checkout's own config so the java/ktlint shims resolve.
export MISE_TRUSTED_CONFIG_PATHS="$PWD/mise.toml"
export MISE_YES=1

ktlint '**/*.kt' '**/*.kts' '!**/build/**' --reporter=plain
./gradlew build --no-daemon --console=plain
