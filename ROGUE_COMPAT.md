# Play Games integration

The companion exposes Play Games and App Set services through the default `app.revanced.android.gms` package. Hosts derive the original publisher certificate and the selected companion certificate during packaging.

App Set validates the Binder caller on service connection and ID requests. Its app-scoped ID is persisted and excluded from diagnostics. Play Games reads the application's declared Games ID from manifest metadata, loads achievement definitions and player progress into the SDK's `DataHolder` contract, and returns score-submission results for daily, weekly and all-time spans. Score tags are optional.

Google account and consent flows use the companion's authentication services. Provider removal, disablement or replacement must be detected by the host before routing a new request. Authentication does not establish Play Integrity or game-backend acceptance. Game save import and final transfer confirmation belong to the game flow.

Player loading supports the legacy and refresh-capable Binder contracts. A cached current-player read stays in memory. Forced refresh fetches the authenticated player from Google, updates the connection cache, and returns fresh data only after a successful response. Network failure can return the cached current player with the SDK's stale flag; malformed responses and other failures return errors. Lifecycle cancellation returns an interrupted result. Player responses bypass Volley's URL-only disk cache because the response also depends on the account and application. Optional profile fields may be absent.

Run the account-free Binder, lifecycle, parsing and request-cache checks against a debug APK:

```sh
tools/test_games_players.sh SERIAL DEBUG_COMPANION.apk ANDROID_PLATFORM.jar D8
```

These synthetic checks do not establish authenticated server acceptance. Verify forced and cached loads separately through the game's shipped SDK with the production companion.

Build and signing instructions are in [README.md](README.md); request behavior and regression commands are in [LSPATCH_COMPAT.md](LSPATCH_COMPAT.md).
