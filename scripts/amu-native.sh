#!/bin/sh
# Sealed native kexe for the whole-component operator entry.
# Exact invocation from kotoba-amu. Not kotoba -M, not clojure -M, not short aarch64.
#
# bin/amu check kotoba/launcher.kotoba --jvm-free
# bin/amu compile kotoba/launcher.kotoba --target aarch64-macos --jvm-free --output launcher.kexe
# bin/amu verify launcher.kexe
#
# Entry is kotoba/launcher.kotoba — not resources/kotoba/shell/app/tauri_equivalent.kotoba
# and not a host OS binary. Output is a sealed kexe, not an OS binary.
# Linux kexe-verify is HOLD. This script does not fake :ok on Linux.
set -eu

entry="${1:-kotoba/launcher.kotoba}"
name=$(basename "$entry" .kotoba)

if [ "$(uname -s)" != "Darwin" ] || [ "$(uname -m)" != "arm64" ]; then
  echo "Linux kexe-verify is HOLD. Native release identity is --target aarch64-macos." >&2
  echo "Would run (on aarch64-macos, with bin/amu from kotoba-lang/amu):" >&2
  echo "  bin/amu check $entry --jvm-free" >&2
  echo "  bin/amu compile $entry --target aarch64-macos --jvm-free --output ${name}.kexe" >&2
  echo "  bin/amu verify ${name}.kexe" >&2
  exit 78
fi

if [ ! -x bin/amu ]; then
  echo "bin/amu is not present. Could not run:" >&2
  echo "  bin/amu check $entry --jvm-free" >&2
  echo "  bin/amu compile $entry --target aarch64-macos --jvm-free --output ${name}.kexe" >&2
  echo "  bin/amu verify ${name}.kexe" >&2
  exit 127
fi

bin/amu check "$entry" --jvm-free
bin/amu compile "$entry" --target aarch64-macos --jvm-free --output "${name}.kexe"
bin/amu verify "${name}.kexe"
