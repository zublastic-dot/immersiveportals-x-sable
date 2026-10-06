#!/usr/bin/env bash
# Build the Linux JNI payload. Toolchains and build caches are supplied by the
# caller; this script never installs tools or changes a shell profile.
set -euo pipefail

if [[ $# -ne 1 ]]; then
    printf 'Usage: %s OUTPUT_DIRECTORY\n' "$0" >&2
    exit 2
fi

native_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
mkdir -p -- "$1"
output_dir="$(cd -- "$1" && pwd)"
case "$(uname -s):$(uname -m)" in
    Linux:x86_64) target=x86_64-unknown-linux-gnu; arch=x86_64 ;;
    Linux:aarch64) target=aarch64-unknown-linux-gnu; arch=aarch64 ;;
    *) printf 'Unsupported build host: %s %s\n' "$(uname -s)" "$(uname -m)" >&2; exit 2 ;;
esac

# Retain the checked-in pinned toolchain and lockfile. Keep generated output out
# of the checkout by default, while allowing an explicit shared build cache.
export CARGO_TARGET_DIR="${CARGO_TARGET_DIR:-$output_dir/target}"
cd -- "$native_dir"
cargo build --locked --release -p sable_rapier --target "$target"
payload="$output_dir/libsable_rapier_${arch}_linux.so"
cp -- "$CARGO_TARGET_DIR/$target/release/libsable_rapier.so" "$payload"
sha256sum -- "$payload"
# Record required symbol versions so release validation can compare the built
# glibc requirements with the actual deployment host before packaging.
readelf --version-info -- "$payload" > "$payload.versions.txt"
readelf --dynamic -- "$payload" > "$payload.dynamic.txt"
printf 'Built %s; validate its required libraries and GLIBC versions on the target host.\n' "$payload"
