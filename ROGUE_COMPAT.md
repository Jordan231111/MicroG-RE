# Rogue with the Dead compatibility changes

This branch starts from MicroG-RE 7.3.3 and keeps the `app.revanced.android.gms` package and `app.revanced` account type. It addresses Play Games calls observed in Rogue with the Dead 3.18.2 and a repeatability bug in App Set ID. It does not add Play Integrity, attestation, or game cloud-save handling.

## Changes

- App Set verifies the real Binder caller on both service connection and ID request, then returns a persisted app-scoped ID. The ID is never logged.
- Play Games loads achievement definitions and player progress into a `DataHolder` compatible with the game's bundled Play Games SDK 21.0.0. The game sent `loadAchievementsV2(false)` during sign-in.
- Score submission uses the Games REST endpoint and returns the SDK's required three-row `ScoreSubmissionData` result for daily, weekly, and all-time spans. Score tags are optional; the previous code tried to parse a leaderboard ID concatenated with a score as a UUID.
- `getAppId()` reads the game's declared Play Games application ID from its manifest metadata.

## Build and test

Use this repository's pinned Gradle wrapper and installed Android SDK. On the test Mac:

```sh
./gradlew :play-services-core:assembleDefaultDebug -Pabi=arm64-v8a --offline
./gradlew :play-services-core:assembleDefaultRelease -Pabi=arm64-v8a
```

The release build runs its `lintVital` tasks. It needs the project's pinned Android lint artifact in Gradle's cache; the first release build may fetch that artifact. The release APK is unsigned. Align and sign it with a private key kept outside this repository, then verify its package, signer, and 16 KiB alignment before installation. The upstream signing key is unavailable, so a locally signed build cannot update an upstream-signed installation in place.

The debug and release builds passed on 2026-11-24. On MuMu instance 1, the signed release returned the same App Set ID across repeated SDK requests and a game restart; the probe reported only equality and scope, not the ID. After sign-in, `onAchievementsLoaded` returned status 0 with 39 rows. The Google account chooser reached the game's linkable-data confirmation. The confirmation was canceled because accepting it would overwrite the emulator save. Live score submission remains to be tested.
