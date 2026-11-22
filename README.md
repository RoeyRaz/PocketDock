# PocketDock

An Android-first companion for a Windows PC. The goal is to run useful lightweight jobs **on the phone**, not simply mirror desktop data.

> **Status:** Early prototype. No APK or production server exists yet.

## What we are building

- **Runner:** lightweight scripts and local services executed on an old Samsung Android phone.
- **Panel:** a landscape Android dashboard for tasks, tools, computer control, and file access.
- **Dev:** a view of GitHub Actions and Codex Cloud work so heavy development tasks can stay off the Windows PC.
- **Vault:** optional SFTP-based file storage, with manual USB file transfer when useful.

PhoneBox and the existing Notion/Daylight setup remain in place until a replacement is genuinely useful and tested.

## Milestone 1: run a service entirely on the phone

This starter uses Python's standard library only. No root, cloud account, Android Studio, or Windows background service is needed.

1. Install Termux from a maintained official distribution channel (for example F-Droid).
2. In Termux, run:

   ```sh
   pkg update
   pkg install python git
   git clone https://github.com/RoeyRaz/PocketDock.git
   cd PocketDock
   python runner/server.py
   ```
3. On **the phone itself**, open `http://127.0.0.1:8799/health`.

You should receive a JSON response containing `"status": "ok"`. This confirms the service is executing on Android, rather than on Windows.

Stop it using **Ctrl+C** in Termux. It is intentionally **not** configured to run at boot.

### Security

The prototype listens on `127.0.0.1` only. It is not reachable from other devices or the internet. Do not expose it through port forwarding or change its bind address before we add pairing, authentication, and a command allowlist.

Never commit passwords, API keys, private Notion data, Android backups, or personal configuration files.

## Next milestones

1. Verify the Runner works with the Windows PC powered off.
2. Measure phone heat, battery impact, and Windows memory usage before and after migrating any existing task.
3. Build a small Kotlin + Jetpack Compose landscape APK with status and task panels.
4. Add a narrowly scoped authenticated PC bridge, only for features requiring Windows.
5. Set up GitHub Actions to build and test the APK in the cloud.

See [docs/ROADMAP.md](docs/ROADMAP.md) for scope and success criteria.

**Maintainer:** RoeyRaz. License and public release plan to be decided.
