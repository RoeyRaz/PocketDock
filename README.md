# PocketDock

**PhoneBox controls Windows. PocketDock makes the old Samsung useful as a separate little computer.**

PocketDock consists of a Termux-hosted Python Runner and a native, landscape-first Android APK. It performs its own HTTPS monitoring, keeps local results and optionally displays Android notifications when a monitored service changes state. It does not add another Windows background agent.

> **Status:** v0.3 is installed on Samsung and its GitHub API check returned healthy on a manual run. Version **0.4** passed Python tests and the Android cloud build in [Actions run 45075683460](https://github.com/RoeyRaz/PocketDock/actions/runs/45075683460), and was merged to main. Installation and background/battery tests on the actual Samsung are still pending.

## Responsibilities

| Piece | Job |
| --- | --- |
| Existing PhoneBox | Windows remote control, media, tasks, calendar and useful established features |
| PocketDock Runner | On-phone periodic checks, SQLite history, bounded automation and optional notifications |
| PocketDock Android APK | Configure and inspect Runner, manage a private file Inbox, view project/build status |
| GitHub Actions / Codex Cloud | Heavy code builds and development in cloud infrastructure |
| Existing Notchix | Native, lightweight Windows desktop interface, kept separate |

Do not rebuild PhoneBox's features in PocketDock just because they can be copied.

## What is implemented in v0.3 and v0.4

- HTTPS Watchdog with a 15-minute GitHub API uptime check and a separate **PocketDock CI** monitor. The CI monitor checks the latest *completed* GitHub Actions build every 30 minutes, tracks failures and recoveries, and does not treat in-progress or API-error results as success. Up to 12 checks, manual runs and disable/delete are supported.
- Local SQLite database retaining checks and up to 300 **state-change** events; no event flood on repeated successes.
- Device-generated random pairing token, authenticated localhost control endpoints and encrypted pairing token in Android Keystore.
- Optional Android notifications **only when state changes**, via separately installed Termux:API.
- Phone Runner metrics: process uptime and available Termux storage. Android Home also reads battery percentage and charging state on demand, with no new permission.
- Android APK: **שירותים**, Home, Focus, Dev, **קבצים**, Settings. The existing Focus and Dev screens remain optional; PhoneBox is the main interface for already-solved Windows features.
- Private, manual on-phone Inbox using Android's system file picker. Bounded at 150 MB per file, with manual export and deletion.
- GitHub Actions building debug APKs and running Python tests. Build work stays off Windows.

**Explicitly not implemented:** executing arbitrary shell commands, unattended Windows process migration, private Codex session control, automatic file sync, remote PC control, SFTP storage, backup, direct LAN access or remote network scanning. Those need separate authorization, threat modeling and measured benefit.

## Setup

**Read [the Samsung setup guide](docs/SETUP_ANDROID.md)** to pull new code in Termux, install the APK, pair it with the local Runner, configure optional notifications or boot behavior, and perform the physical-phone tests.

Short version on the Samsung in Termux:

```sh
cd ~/PocketDock
git pull --ff-only
python runner/server.py
```

Open another Termux session to display the pairing token locally:

```sh
cd ~/PocketDock
python runner/server.py --show-token
```

Paste that token into the **שירותים** tab, never into GitHub, Notion or chat.

**Watchdog API:** `http://127.0.0.1:8799/health` remains compatible with the old app; `/api/overview`, `/api/checks`, `/api/history` require the device's pairing token.

When upgrading from v0.3, the Runner adds the CI monitor once without deleting existing checks, pairing tokens or history. Any check you deliberately delete stays deleted. The separate CI monitor works only after Termux pulls and restarts the updated Runner. The APK update alone does not change a running Python process.

## Safety and validation

The Runner binds only to `127.0.0.1`. Public HTTPS destinations are probed without following redirects. Direct IP/private destinations and arbitrary command execution are not supported. No root needed. Autostart and notifications are optional and off by default.

The main evidence still needed is the phone-only independence test with Windows powered off, stability under Samsung battery restrictions and measured RAM/CPU reduction after a *specific* existing task has been safely moved. The project does not claim that those tests have already passed.

[Roadmap](docs/ROADMAP.md) · [GitHub Actions](https://github.com/RoeyRaz/PocketDock/actions) · [PhoneBox](https://github.com/ersinkoc/phonebox)

Maintained by RoeyRaz. Licensing and public release still need decisions.
