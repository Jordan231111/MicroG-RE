# Host authentication contracts

Use stable LSPatch 1.2 release build 487 and the selected signed companion APK. Each game retains its own publisher identity, OAuth client configuration, account choice and consent.

Patch the unchanged publisher-signed base and include every required split. The host preparation helper verifies the selected companion package and APK signature, derives its certificate, and records that identity in host metadata. The module verifies the currently installed, enabled companion against that identity. The original game certificate comes from the unchanged signed `origin.apk`. Build receipts must identify the source, module, companion and final bundle digests.

The shared signing profile and local build paths are in [README.md](README.md). Install the original game plus the standalone module on MuMu 0. Use the final LSPatched bundles on MuMu 1 and RedMagic.

Google ID requests preserve server client ID, nonce, account selection, authorization filtering and auto-selection policy. Independent One Tap requests have separate pending-intent identities. The platform provider uses the final authenticated caller and full request data. Both legacy Identity service connections validate the supplied package against the real Binder caller before creating a request. Android 14+ hosts route compatible Google ID requests to the trusted companion while preserving caller-specified provider restrictions.

Hosted-domain filtering, linked-account token deposition, verified phone numbers and delegated credential origins are unsupported on the Google ID transport and return errors before requesting a token. Cancellation, unavailable credentials, malformed results and provider failures remain distinct. An incomplete account/token result cannot become a successful Google credential. Both transports return profile-picture URIs as Parcelable values.

Recreation restores the active child request. The consent sheet uses measured layout changes and scrollable content, with the cancel control accessible outside the scrolling body. Explicit account selection starts sign-in immediately; automatic selection retains its cancellation window.

Authentication diagnostics omit token, nonce, account and credential payload values. Synthetic regression checks run against the built debug APK without signing in or accessing an account:

```sh
tools/test_google_id_options.sh SERIAL DEBUG_COMPANION.apk ANDROID_PLATFORM.jar D8
python3 tools/test_auth_diagnostics.py SERIAL DEBUG_COMPANION.apk ANDROID_PLATFORM.jar D8
```

The request checks create and cancel pending intents without launching consent. The logging check blocks account-service access and requires observing the real diagnostic sink. Devices that suppress debug logs must enable `log.tag.GmsAuthManagerSvc=D` for this check and restore the previous value afterward.

Synthetic checks establish local protocol behavior. A visible account chooser establishes UI routing. Authenticated game-backend acceptance requires completing the corresponding game flow with the exact final production bundle and companion; neither of the first two checks establishes that result.

ELF LOAD-segment alignment and APK ZIP alignment are independent checks. The current Conscrypt and Cronet artifacts require page-size compatibility mode on 16 KB systems; see README for the supported native limits.
