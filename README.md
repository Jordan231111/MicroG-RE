# MicroG RE

Supporting Google authentication and Play Games companion for the game modules. The default package is `app.revanced.android.gms`, with account type `app.revanced`. Stable LSPatch 1.2 build 487 is the supported host baseline.

Track [MorpheApp/MicroG-RE main](https://github.com/MorpheApp/MicroG-RE/tree/main) for upstream updates. Merge that branch without rewriting its history; keep fork changes limited to authentication compatibility, shared signing, delivery and demonstrated platform defects. Retain upstream APIs and service implementations where they already satisfy the host contract.

Build with JDK 17 or 21, the pinned Gradle wrapper and Android SDK:

```sh
./gradlew :play-services-core:assembleDefaultDebug :play-services-core:assembleDefaultRelease -Pabi=arm64-v8a
python3 -m unittest discover -s tests -v
```

Local distributable builds use `/Users/jordan/.android/signing/jordan-modules.json` and `/Users/jordan/.android/signing/jordan-modules.p12`. Alias: `jordan-modules`. The PKCS12 identity uses RSA 4096 / SHA256withRSA and expires on 2036-11-25. Certificate SHA-256: `5486b776291d98b9bc5a70586b9af89afce8674fd462889238c0ebd99786a1c1`. Private material stays outside Git. CI uses the same identity through `TEMPLATE_*` secrets and the `TEMPLATE_CERT_SHA256` repository variable. Signing rechecks the live key even when Gradle reuses its configuration cache.

The release workflow supports universal, ARM64 and ARMv7 APKs, with a visible or hidden launcher icon. `-Pabi=arm64-v8a` selects ARM64; `-PnoIcon=true` hides the launcher icon. APK manifest metadata records the source commit and whether the checkout was dirty.

See [host authentication contracts](LSPATCH_COMPAT.md) and [Play Games integration](ROGUE_COMPAT.md). On Android 14 and newer, enable **Sign in with Google via MicroG RE** in the system credential-provider settings. Self-Check links to that setting.

Native 16 KB support is incomplete: the bundled Conscrypt and Cronet libraries still require 4 KB ELF segments. Their upstream published artifacts do not provide an aligned replacement. The other ARM64 libraries have compatible LOAD segments. Android may run the companion in page-size compatibility mode; ZIP alignment alone cannot establish native compatibility.

This fork builds on the [microG project](https://github.com/microg/GmsCore) and the MicroG RE redesign. Source licenses and attribution remain with their files. The project is distributed under [Apache License 2.0](LICENSE).
