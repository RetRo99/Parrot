#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Always target the local stack; the linked development project is never reset.
supabase start --workdir "$ROOT"
supabase db reset --local --yes --workdir "$ROOT"
