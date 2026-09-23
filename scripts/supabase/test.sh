#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Always target the local stack; tests must not touch the linked project.
supabase test db --local --workdir "$ROOT" supabase/tests
