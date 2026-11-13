# Pocketpad++

A free, open-source text and code editor for Android in the spirit of Notepad++,
built on the same editing engine (Scintilla) and lexers (Lexilla, including
Notepad++'s user-defined-language lexer).

Pocketpad++ is an independent project. It is not affiliated with or endorsed by
the Notepad++ project.

## Status

Early, but an editor: tabs, opening and saving files, Notepad++'s languages and default theme,
encodings and line endings, find and replace with regular expressions, bookmarks, folding, line
operations, and a session that brings back open tabs and unsaved text. Underneath is a Scintilla
platform layer written for Android, with touch selection, smooth scrolling and soft keyboard support.

- [docs/FEATURES.md](docs/FEATURES.md): every Notepad++ menu command and where it stands
  (generated from Notepad++'s own menu resource).
- [docs/TESTING.md](docs/TESTING.md): what to check on a device, and known gaps.

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
| `app/src/main/kotlin/.../core/` | Application logic without Android classes: encodings, Notepad++ language and theme import, search, line operations, sessions |
| `app/src/main/kotlin/.../app/`, `MainActivity.kt` | Tabs, file access, find bar, menus |
| `app/src/main/assets/npp/` | Notepad++'s `langs.model.xml` and `stylers.model.xml`, plus a language table generated from its sources |
| `hosttest/` | Tests that run the native layer and the application logic on a desktop JVM, with Java2D standing in for the Android canvas |
| `tools/` | Generators for Scintilla constants, the language table and the feature map |

The native layer talks to Android only through JNI interfaces (font, surface,
platform, host), which keeps it free of NDK dependencies and lets the same
code be tested off-device.

## Build

Android Studio (Ladybug or newer) opens the project directly. From a shell:

```sh
./gradlew assembleRelease      # needs the Android SDK, NDK 27.3 and CMake 3.31
hosttest/run.sh                # tests; needs a JDK, CMake, Ninja, a C++17 compiler and kotlinc
```

## Licence

AGPL-3.0, see [LICENSE](LICENSE). Scintilla, Lexilla and the Notepad++ data files keep
their own licences, see [third_party/README.md](third_party/README.md).
