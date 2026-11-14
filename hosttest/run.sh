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

# CHECK_JNI=1 adds the JVM's JNI checker (slow, and noisy about the JDK's own font code).
java -Djava.awt.headless=true -Djava.library.path="$build" ${CHECK_JNI:+-Xcheck:jni} \
    -cp "$build/classes" host.Harness "$here/out"

# Application logic tests (Kotlin, no Android classes) against the same native editor.
if command -v kotlinc >/dev/null 2>&1; then
    kotlinc -nowarn -jvm-target 17 -cp "$build/classes" -d "$build/core-tests.jar" -include-runtime \
        "$root"/app/src/main/kotlin/io/github/revelationnow/pocketpadpp/core/*.kt "$here"/kotlin/*.kt
    java -Djava.awt.headless=true -Djava.library.path="$build" \
        -cp "$build/core-tests.jar:$build/classes" host.CoreTestsKt "$root/app/src/main/assets/npp" "$root/hosttest/data/functionList"
else
    echo "kotlinc not found: skipping the Kotlin core tests" >&2
fi
