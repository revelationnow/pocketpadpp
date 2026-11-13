# Pocketpad++

A free, open-source text and code editor for Android in the spirit of Notepad++,
built on the same editing engine (Scintilla) and lexers (Lexilla, including
Notepad++'s user-defined-language lexer).

Pocketpad++ is an independent project. It is not affiliated with or endorsed by
the Notepad++ project.

## Status

Early spike. The question being answered is whether Scintilla can feel right on
a phone: typing with a soft keyboard, touch selection and smooth scrolling.
There is one screen with a sample file; there are no tabs, file handling or
settings yet. See [docs/SPIKE.md](docs/SPIKE.md) for what to test and what is
known to be missing.

## Try it

Every push builds an APK. The newest one is attached to the
[spike-latest pre-release](../../releases/tag/spike-latest); open that page on
your phone, download `pocketpadpp-spike.apk` and install it (Android 8.0 or
newer, 64-bit). Builds are signed with the public development key in this
repository, so they update each other but are not store releases.

## How it is put together

| Path | What |
|---|---|
| `third_party/` | Scintilla 5.6.9 and Lexilla 5.5.5, unmodified, as shipped in Notepad++ |
| `app/src/main/cpp/` | Scintilla platform layer for Android (C++), including soft keyboard text handling |
| `app/src/main/java/.../scintilla/` | The JNI boundary: four small Java interfaces, native entry points, generated constants |
| `app/src/main/kotlin/.../editor/` | `ScintillaView`: drawing, touch scrolling, selection handles, input connection |
| `app/src/main/kotlin/.../MainActivity.kt` | Spike screen |
| `hosttest/` | Tests that run the native layer on a desktop JVM with Java2D standing in for the Android canvas |

The native layer talks to Android only through JNI interfaces (font, surface,
platform, host), which keeps it free of NDK dependencies and lets the same
code be tested off-device.

## Build

Android Studio (Ladybug or newer) opens the project directly. From a shell:

```sh
./gradlew assembleRelease      # needs the Android SDK, NDK 27.3 and CMake 3.31
hosttest/run.sh                # native tests; needs a JDK, CMake, Ninja and a C++17 compiler
```

## Licence

AGPL-3.0, see [LICENSE](LICENSE). Scintilla and Lexilla keep their own
licences, see [third_party/README.md](third_party/README.md).
