# PocketDock

Android-first landscape companion for a Windows PC. Useful light work runs on the **Samsung phone** instead of spawning additional Windows services.

**Current state:** The 0.1 APK was installed on the Samsung and showed `RUNNER ONLINE` (confirmed by the user). Version **0.2** adds real Focus and Developer screens, but its new build has **not yet been validated on the phone**. The computer-off independence test is also pending.

## What version 0.2 implements

- **Home:** live local Runner status, uptime, available Termux storage and a manual network check executed on the phone.
- **Focus:** a local foreground 25/45-minute timer, the next event from the Android calendar (requires permission), and **read-only Notion Daylight** tasks after an optional one-time connection.
- **Dev:** latest workflow status and open pull requests from a **public GitHub repository**. Repository is editable in the UI. A button opens Codex Cloud in the browser.
- **Settings:** optionally store a scoped Notion integration secret **only on the phone**, encrypted using Android Keystore. The token is never compiled into the APK or committed to GitHub.

No permanent Windows process, no Android boot receiver, no root and no remote shell.

## Install the updated Runner

In the existing Termux folder on the **phone**, stop the old Runner with Ctrl+C, then:

```sh
cd ~/PocketDock
git pull --ff-only
python runner/server.py
```

Open `http://127.0.0.1:8799/health` to verify the basic endpoint. The new endpoints are:

- `/status`: process uptime and storage available to Termux.
- `/checks/github`: a single outbound GitHub connectivity check initiated from the phone.

The HTTP server binds to loopback `127.0.0.1` only. **Do not expose it to your local network or the public internet.** It has no ability to execute shell commands.

## Download and install the updated APK

Run the [Android debug APK workflow](https://github.com/RoeyRaz/PocketDock/actions/workflows/android-debug.yml) manually, or use the output from an approved PR build. The successful job uploads a `pocketdock-debug-apk` artifact containing `app-debug.apk`. Install it over the older debug APK. **Do not install an artifact if its build failed.**

The Android UI and Runner communicate over localhost. The build is run in GitHub Actions, not Android Studio on your Windows machine.

## Optional: connect Daylight read-only

1. Create a Notion **internal integration** for your own workspace, with **Read content** capability only. Do not grant write access.
2. In Notion, share the Daylight **task database** with that integration. Copy its **data source ID** (not the page title or URL).
3. In PocketDock open **הגדרות**. Enter the data source ID and the internal integration token. Tap **שמור**.
4. Open **פוקוס** and refresh. The app requests active tasks (excluding the Shop list when present).

The token stays on the Android device and is encrypted at rest via Android Keystore. No token is passed through GitHub Actions, placed in source code, or sent to this chat. Treat the token like a password. You can revoke it in Notion or tap **נתק** in the app. Requires internet access from the phone and a working Notion API integration. Errors are shown in the UI.

Without this configuration, Focus still provides the local timer and Android calendar view. The regular PhoneBox and existing Daylight sync remain untouched.

## Calendar permissions

Focus requests Android's `READ_CALENDAR` permission when you tap **אשר גישה ליומן**. The next visible calendar event on the phone is shown. If the relevant Google Calendar account isn't synced to the Samsung, it won't appear even if it exists on your Windows computer.

## Important limitations

- The Focus timer runs in the app, not in a persistent background service. No background alerts yet.
- Dev uses GitHub's public API without a token. Private repositories require a separate authenticated connection, which is not implemented.
- Codex opens in the browser. PocketDock **does not** yet have access to private Codex session status.
- Daylight is **read-only**. No creating or marking tasks complete.
- Status refreshes are on-demand to avoid unnecessary network and battery usage.
- No existing Windows tasks have been migrated or disabled. Offloading must be measured before changing the user's working setup.

See [docs/ROADMAP.md](docs/ROADMAP.md). This public repository should never contain private user data, API tokens, or backups.
