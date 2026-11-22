# PocketDock v0.3 on a Samsung Android phone

PocketDock is **not** a replacement for PhoneBox. PhoneBox remains the Windows-control UI.
The APK is the control panel for work executed *on the phone* in Termux.

## 1. Update the Runner

On the Samsung, in the existing Termux session, press **Ctrl+C** to stop the old Runner:

```sh
cd ~/PocketDock
git pull --ff-only
python runner/server.py
```

The health endpoint is unchanged: `http://127.0.0.1:8799/health`.

If the repository was previously downloaded somewhere else, use that folder instead. Do not delete your existing Termux data.

## 2. Pair the APK with the Runner

Open a second Termux session and run:

```sh
cd ~/PocketDock
python runner/server.py --show-token
```

Copy the displayed token into the **שירותים** tab of the PocketDock APK. It is a secret: do not send it in a message, add it to Notion, or commit it to GitHub.

The token is random and generated on the Samsung. It is stored as `~/.local/share/pocketdock/pairing-token` with file permissions 0600. The app encrypts its local copy using Android Keystore. Localhost control endpoints require Bearer authentication. Only the legacy health/status endpoints can be read without pairing.

If you clear the app data, the pairing can be restored using the same token. If you delete the Runner data directory, a new token is generated.

## 3. Install the debug APK

Open the **green** run from the [GitHub Actions workflow](https://github.com/RoeyRaz/PocketDock/actions/workflows/android-debug.yml). Download `pocketdock-debug-apk`, extract the APK and install it on the Samsung over the previous debug version.

Open **שירותים**. You should see an enabled GitHub API check. Press **בדוק** to perform an HTTPS check on the Samsung. New state changes appear in **היסטוריית שינויים**. You may add more **public HTTPS** endpoints, with a 5-minute minimum interval.

**Limits:** up to 12 checks, 5-second network timeout, and up to 300 transition events retained in SQLite. The default check repeats every 15 minutes while the Runner process remains running. URLs pointing at private network addresses or ports other than 443 are blocked. This is intentionally *not* a LAN port scanner.

## 4. Optional alerts

Alerts are **off by default**. To receive a local Android notification only when a check changes state:

1. Install the official Termux:API Android app from the same distribution source as Termux (usually F-Droid).
2. In Termux run `pkg install termux-api`.
3. Grant notification permission to Termux:API in Android settings.
4. In PocketDock's **שירותים** tab turn on notifications.

No email, SMS, push subscription, location tracking, contacts, or device identifiers are sent. If Termux:API is missing, the Runner still works but does not display notifications.

## 5. Optional launch after a phone restart

First complete the independence, battery and stability tests. Autostart is **not enabled by default**, because it affects battery use and depends on Android background restrictions.

With the Termux:Boot Android add-on installed, open its Android app once, then in Termux run:

```sh
mkdir -p ~/.termux/boot
cp ~/PocketDock/scripts/termux-boot-pocketdock.sh ~/.termux/boot/20-pocketdock
chmod 700 ~/.termux/boot/20-pocketdock
```

The boot script starts the Runner once. The Runner itself prevents a second copy using a local process lock. Whether it survives screen locks depends on Samsung battery settings and needs testing.

To disable autostart:

```sh
rm ~/.termux/boot/20-pocketdock
```

To stop an active Runner: return to its Termux session and press Ctrl+C.

## 6. Files and development

The **קבצים** tab provides a private on-phone Inbox for manually imported files. Files can be exported back into Android's system file picker. It is **not** a sync drive, backup system, or SFTP server. Do not keep your only copy there. Limit: 150 MB per imported file.

The existing **פיתוח** tab displays public GitHub Actions and PR status, and opens Codex Cloud in the browser. It does **not** have access to private Codex sessions.

The existing **פוקוס** tab can still be used, but its duplication of PhoneBox is not a priority. Daylight tokens must be configured on the device only, never committed or sent to the assistant.

## 7. Real tests before switching off Windows tasks

- [ ] Runner reports ONLINE when the Windows PC is **off**.
- [ ] Runner check history persists after stopping and restarting the Termux process.
- [ ] After a phone screen lock, checks continue and battery impact is acceptable.
- [ ] Optional notifications fire exactly once for a state transition, not on every successful poll.
- [ ] No crash or heating after longer use.
- [ ] Identify one genuine PC background task that does not depend on the PC.
- [ ] Measure Windows RAM and CPU before and after its safe migration, and measure Samsung battery costs.

Keep PhoneBox, existing Daylight automation, Windows tasks, and Notchix unchanged until a specific migration passes verification. This avoids destroying a working setup merely to claim the phone is doing something.
