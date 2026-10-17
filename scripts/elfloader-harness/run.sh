#!/usr/bin/env bash
# Build + run the native elfloader drain-loop harness (host only, Linux/ptrace).
# Validates the waitpid(-1) drain selection and the single-spawn gate without a
# device. See docs/OPENCODE_ON_ANDROID_LESSONS.md §3.8.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
src="$here/../../agent-core/src/androidMain/c"
bin="$(mktemp -d)/harness"
cc -O1 -I"$here" -I"$src" -o "$bin" "$here/harness.c" -lpthread -ldl
exec "$bin" "$@"
