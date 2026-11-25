# LSPatch login compatibility

The supported host baseline is LSPatch 1.2 release build 487 with this fork and the
AE module's service and Credential Manager routing. The fork
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

## Android 14 and newer

On Android 14+, the game's Google ID client uses the platform Credential Manager
and requests a system provider. On the RedMagic running Android 17 this selected
stock GMS, which canceled the request without displaying an account chooser.
The earlier Android 12 test used the One Tap Identity service.

The Google ID provider now declares the canonical Google credential capability
and retains the renamed MicroG capability for existing patched clients. The
sign-in proxy uses the final platform request's authenticated caller and complete
option data, handles both Google ID and Sign in with Google variants, and returns
the requested credential type. Credential values are excluded from its diagnostics.

The host's Identity adapter selects the verified companion as an allowed provider
for its Google ID options and clears their system-only requirement. It preserves
the request data, cancellation, callback and native account-selection flow.

Enable **Sign in with Google via MicroG RE** under additional credential providers
in Android settings. Self-Check contains an actionable provider-status row that
opens this page with the app's package URI. Android stores the selection for the
current user when the provider is enabled through the system control.

On 2026-11-25, the ARM64 debug/release builds and release vital lint passed. A
prior experimental host on the RedMagic NX819J, Android 17/API 37,
displayed the native account chooser, completed assisted consent and returned to
Another Eden's **Confirm Data Transfer** with the existing save. The final Transfer
was left pending. This test used the local LSPatch test signer. The live Global
bundle uses the Ashfur signer, and the RedMagic result does not validate a login
from that bundle. Repeat the phone flow against the stable release host before
using it as current end-to-end evidence.

During review, the debug APK passed 25 Google ID option, account selection and
diagnostic privacy checks on Android 14, 15 and 16 test devices and the Android 17
phone. These checks use synthetic inputs and require neither installation of the
test APK nor a Google account. They cover both option variants, the distinction
between a known account and an authorized account, and redaction of sign-in
account values. Open test cases include full account login on Android 13–16,
hosted-domain enforcement and linked-account token deposition.
To repeat the checks after building the debug companion, run:

```sh
tools/test_google_id_options.sh SERIAL DEBUG_COMPANION.apk ANDROID_PLATFORM.jar D8
```

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

These observations cover the Games and Identity paths and the landscape consent
screen on this device. The earlier Rogue compatibility changes
remain described in [ROGUE_COMPAT.md](ROGUE_COMPAT.md).
