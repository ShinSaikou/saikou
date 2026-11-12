# Security policy

## Supported versions

Only the latest release, and `main`, get security fixes. Saikou is at 0.1.5, an early public
release, so expect to update to the next release rather than receive a patch for an old one.

## Reporting a vulnerability

Report it privately through the repository's **Security** tab, **Report a vulnerability**. Do not
open a public issue or pull request for it.

Please include what an attacker can do, the steps or a proof of concept, the app version and the
Android version. You should get an answer within a week. Once a fix is released, the advisory is
published with credit to you, unless you would rather stay anonymous.

## Scope

In scope: the Saikou app and the code in this repository, including how it loads and isolates
extensions, stores tokens (the Google sign-in is kept in the Android Keystore), reads backups and
archives, and talks to Google Drive and MyAnimeList.

Out of scope: the extensions themselves and the sites they read, which are third-party code and
content. An extension runs inside the app with the app's permissions; installing one is a decision
to trust whoever published it. A way for an extension to reach beyond what the extension API and
those permissions allow is in scope.
