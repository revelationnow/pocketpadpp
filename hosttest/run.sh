#!/usr/bin/env bash
# Builds the native Scintilla layer for the desktop and runs the off-device tests.
# Needs a JDK (17+), CMake, Ninja and a C++17 compiler. Images are written to hosttest/out/.
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$(dirname "$here")"
flags="-O1"
link=""
if [ -n "${SANITIZE:-}" ]; then
    # SANITIZE=1 builds the native layer (Scintilla, Lexilla, Lua and the Android platform code) with the
    # address and undefined-behaviour sanitizers, so that a read or write outside an object, a use after free
    # or an overflowing signed number stops the run with a report instead of passing unnoticed.
    flags="-O1 -g -fsanitize=address,undefined -fno-sanitize-recover=undefined -fno-omit-frame-pointer"
    link="-fsanitize=address,undefined"
    asan="$(${CXX:-c++} -print-file-name=libasan.so)"
    ubsan="$(${CXX:-c++} -print-file-name=libubsan.so)"
    [ -f "$asan" ] && [ -f "$ubsan" ] || { echo "SANITIZE=1 needs GCC's libasan and libubsan" >&2; exit 1; }
    # The sanitizer runtime has to be loaded before the JVM, which also has signal handlers of its own.
    # Leaks are not looked for: the JVM itself would be reported.
    export LD_PRELOAD="$asan $ubsan"
    export ASAN_OPTIONS="handle_segv=0:detect_leaks=0:halt_on_error=1"
    export UBSAN_OPTIONS="print_stacktrace=1:halt_on_error=1"
fi
build="${HOSTTEST_BUILD:-$here/build${SANITIZE:+-sanitize}}"
mkdir -p "$build/classes" "$here/out"

LD_PRELOAD= cmake -S "$root/app/src/main/cpp" -B "$build" -G Ninja -DCMAKE_BUILD_TYPE=None \
    -DCMAKE_CXX_FLAGS="$flags" -DCMAKE_SHARED_LINKER_FLAGS="$link" >/dev/null
LD_PRELOAD= ninja -C "$build" nppscintilla

LD_PRELOAD= javac -Xlint:all -d "$build/classes" \
    "$root"/app/src/main/java/io/github/revelationnow/pocketpadpp/scintilla/*.java \
    "$here"/java/host/*.java

# CHECK_JNI=1 adds the JVM's JNI checker (slow, and noisy about the JDK's own font code).
java -Djava.awt.headless=true -Djava.library.path="$build" ${CHECK_JNI:+-Xcheck:jni} \
    -cp "$build/classes" host.Harness "$here/out"

# Application logic tests (Kotlin, no Android classes) against the same native editor.
if command -v kotlinc >/dev/null 2>&1; then
    LD_PRELOAD= kotlinc -nowarn -jvm-target 17 -cp "$build/classes" -d "$build/core-tests.jar" -include-runtime \
        "$root"/app/src/main/kotlin/io/github/revelationnow/pocketpadpp/core/*.kt "$here"/kotlin/*.kt
    java -Djava.awt.headless=true -Djava.library.path="$build" \
        -cp "$build/core-tests.jar:$build/classes" host.CoreTestsKt "$root/app/src/main/assets/npp" "$root/hosttest/data/functionList"
else
    echo "kotlinc not found: skipping the Kotlin core tests" >&2
fi
