# Play Games integration

The companion exposes Play Games and App Set services through the default `app.revanced.android.gms` package. Hosts derive the original publisher certificate and the selected companion certificate during packaging.

App Set validates the Binder caller on service connection and ID requests. Its app-scoped ID is persisted and excluded from diagnostics. Play Games reads the application's declared Games ID from manifest metadata, loads achievement definitions and player progress into the SDK's `DataHolder` contract, and returns score-submission results for daily, weekly and all-time spans. Score tags are optional.

Google account and consent flows use the companion's authentication services. Provider removal, disablement or replacement must be detected by the host before routing a new request. Authentication does not establish Play Integrity or game-backend acceptance. Game save import and final transfer confirmation belong to the game flow.

Build and signing instructions are in [README.md](README.md); request behavior and regression commands are in [LSPATCH_COMPAT.md](LSPATCH_COMPAT.md).
