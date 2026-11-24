# LSPatch login compatibility

Another Eden's Play Games and Google Identity login work with this fork and the
AE module's existing selected-service routing on LSPatch 1.2 build 492. The fork
keeps its shared `app.revanced.android.gms` package and account type. Each app
retains its own Google consent and publisher identity.

## Assisted sign-in layout fix

The consent screen could finish loading after the bottom sheet had already
started settling to the loading screen's height. Its measured expanded position
then differed from its visible position, leaving Continue below the display.
The screen had no scrollable outer content area. This was reproduced on MuMu 1
in landscape after restarting both the game and MicroG.

Content now uses its real parent during inflation so Android preserves the XML
layout parameters. Content roots wrap their measured height. A nested scroll
container keeps oversized content accessible, and a content-height listener
updates the expanded sheet target after asynchronous content replacement.
The header and cancel control remain outside the scrolling body.

The fix uses measured layout changes. It adds no screen dimensions, device or game
checks, fixed offsets, polling, or delayed layout retries. Authentication, token
requests, consent decisions and service actions retain their existing behavior.

## Host integration

Patch the unchanged publisher-signed game base, embed the AE module, and use
signature-bypass level 2. Keep the module's narrow Games/Identity routing;
LSPatch's built-in `--gms-replacement` is a different routing implementation.

Run the module's `PrepareMicrog.java` helper after patching and before final
alignment/signing. Pass this trusted signed companion APK as its fourth argument.
The helper verifies the companion package and APK signature, then records the
signer fingerprint in the host. The module verifies the installed companion
against that configured identity, including Android's signing-key lineage.
No fork certificate or game OAuth client identity is compiled into the module.

The outer host and splits retain their chosen signing key. The publisher-signed
source remains unchanged inside `origin.apk`; its public certificate supplies
the OAuth identity. Sign companion updates with the same private key, kept
outside Git.

## Verification

On 2026-11-24, default ARM64 debug and release builds passed:

```sh
./gradlew :play-services-core:assembleDefaultDebug :play-services-core:assembleDefaultRelease -Pabi=arm64-v8a
```

The release build included its vital lint checks. The signed companion passed APK
signature and 16 KiB ZIP-alignment verification and updated the existing local
fork in place, preserving its account.

With Another Eden Global 3.17.70 / 710 on MuMu instance 1, Android 12 ARM64:

- Restarting both apps with the Frida server stopped completed Play Games sign-in.
- Google Identity opened the consent sheet with Continue visible and clickable.
- Continuing reached the game's **Confirm Data Transfer** screen.
- The final Transfer action was not accepted, preserving the existing save.

These observations cover the tested Games/Identity paths and landscape consent
screen. They do not establish support for every Google Play service, SDK version,
device, or future backend requirement. The earlier Rogue compatibility changes
remain described in [ROGUE_COMPAT.md](ROGUE_COMPAT.md).
