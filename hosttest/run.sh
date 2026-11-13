#!/usr/bin/env bash
# Builds the native Scintilla layer for the desktop and runs the off-device tests.
# Needs a JDK (17+), CMake, Ninja and a C++17 compiler. Images are written to hosttest/out/.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
build="$here/build"
mkdir -p "$build/classes" "$here/out"

cmake -S "$root/app/src/main/cpp" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=None -DCMAKE_CXX_FLAGS="-O1" >/dev/null
ninja -C "$build" nppscintilla

javac -Xlint:all -d "$build/classes" \
    "$root"/app/src/main/java/io/github/revelationnow/pocketpadpp/scintilla/*.java \
    "$here"/java/host/*.java

java -Djava.awt.headless=true -Djava.library.path="$build" -Xcheck:jni \
    -cp "$build/classes" host.Harness "$here/out"
