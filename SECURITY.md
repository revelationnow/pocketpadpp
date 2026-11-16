# Security

This page says plainly what has been checked and what has not, so you can decide for yourself.

## How the app was written

All of the app's own code was written by an AI model (Claude) under one person's direction, and it has
**not been reviewed or audited by an independent security professional**. The parts with the longest record
are not the app's own: the editing engine (Scintilla and Lexilla, as used in Notepad++) and the Lua
interpreter are included unmodified.

## What limits the damage a bug could do

- **No permissions.** The published app asks Android for none: no internet, no storage, no contacts, nothing.
  You can see this in the APK's manifest, or under the app's entry in Android's settings. Without the
  internet permission the app cannot open a network connection.
- **Only the files you pick.** Files are opened through Android's own file picker, which hands the app the
  files you choose and no others.
- **Android's app sandbox.** Like any app, it cannot read other apps' data.
- **Nothing runs in the background.** There are no services, receivers or providers; the one entry from
  outside is "Open with", and that entry refuses to open the app's own internal files.
- **No third-party libraries** at run time other than Scintilla, Lexilla and Lua, and no code is downloaded.
- **The app's data is left out of Android's backups.**

So a flaw in the app could, at worst, damage or expose to the app itself the documents you opened in it.
Getting anything out of the phone would take a further step that you would see, such as the app asking
your browser to open an address.

## What is tested

- About 860 automated checks on a desktop build of the same native code and logic, and about 40 scripted
  walkthroughs of the real app on an Android emulator, on every change (see [docs/TESTING.md](docs/TESTING.md)).
- The desktop checks are also run with the C++ compiled under the **address and undefined-behaviour
  sanitizers**, which stop at reads and writes outside an object, use after free, and similar errors.
- Each published APK carries a build attestation and a checksum (see "Checking a download" in the README).

## What is not tested

- No independent audit or penetration test.
- No fuzzing yet: the code has not been fed large numbers of deliberately malformed files. The checks above
  only exercise the inputs somebody thought of.
- The sanitizer run covers the desktop build, not the build for the phone's processor.
- Development builds are signed with a key that is public in this repository, so the signature says nothing
  about where an APK came from; use the attestation for that.

## Lua scripts

A script you run is trusted code: it can read and change every open document and the app's own files.
It cannot start other programs (`os.execute` and `io.popen` are removed) and has no network access, but treat
a script from someone else the way you would treat a program from them: read it first.

## Reporting a problem

Please use GitHub's private "Report a vulnerability" form on this repository's Security tab if it is offered
there, or open an issue saying only that you have a security report, without the details.
