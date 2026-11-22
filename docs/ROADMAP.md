# PocketDock roadmap

## Product goal

Turn an unused Samsung Galaxy A72 into a useful, landscape-oriented companion that **executes** lightweight workloads on Android, while avoiding unnecessary Windows startup services.

## Milestone 1 — Runner proof of concept

- [x] Install an up-to-date Termux build on the Android phone (confirmed Runner launched).
- [x] Start `python runner/server.py` on the phone (user confirmed it works).
- [x] Read `http://127.0.0.1:8799/health` using the phone browser (user confirmed success; response text not independently inspected).
- [ ] Verify the runner still works when the Windows PC is off.
- [ ] Record Android version, battery impact, heat, and whether Android suspends the process.

**Done when:** an independent process runs on the phone and is stopped cleanly. Pushing code is not evidence it has run successfully.

## Milestone 2 — Native Android panel

- [x] Commit the first Kotlin + Jetpack Compose source and Actions workflow.
- [x] Verify that the 0.1 GitHub Actions build completes successfully (user installed successful APK).
- [x] Download and install the 0.1 debug APK on the Samsung.
- [x] With Termux Runner active, confirm 0.1 app displays ONLINE (user confirmed).
- [ ] Confirm OFFLINE appears when Termux Runner is stopped.


Kotlin + Jetpack Compose, landscape-first layout. Start with read-only runner status, tasks, and development status. Avoid rewriting all existing PhoneBox functions at once.

**Done when:** an APK can be built automatically, installed, and used on the phone.

## Milestone 3 — External workloads

- Add GitHub Actions CI for Android build and tests.
- Explore Codex Cloud for code changes and reviews.
- Move one lightweight, measured automation away from Windows.
- Keep the existing Daylight integration unchanged until the replacement is validated.

## Milestone 4 — Optional local PC bridge and storage

- Authenticated LAN-only endpoints with specific allowed actions.
- Revisit SFTP / drive `P:` for file access after stability checks.
- Prefer ordinary MTP transfer for large manual copies when simpler.
- Add versioned backups only after restore testing, never with the phone as the only backup copy.

## Guardrails

- No root requirement.
- No credentials, token dumps, or personal data in Git.
- No unauthenticated LAN endpoint for command execution.
- No Windows service or Android boot task without a clear need.
- Preserve existing PhoneBox and Notion/Daylight functions.
- Compare Windows RAM/CPU under similar conditions before claiming improvements.
- Do not mark hardware or APK tests complete until actually verified on the phone.

## Milestone 2.1 — Focus and Developer screens (0.2)

- [x] Implement native landscape Focus view with 25/45-minute timer.
- [x] Implement on-demand Android Calendar next-event view and runtime permission.
- [x] Implement optional read-only Notion tasks using Android Keystore for locally supplied credentials.
- [x] Implement public GitHub Actions and PR summaries, plus a Codex Cloud browser shortcut.
- [x] Extend the phone Runner with read-only status and fixed network check endpoints.
- [x] Add Python Runner smoke tests and add them to GitHub CI.
- [x] Verify Android 0.2 build passes (PR #1, GitHub Actions run 45070733917; Runner unit tests, Gradle build and artifact upload succeeded).
- [ ] Install 0.2 on the Samsung, verify Focus/Dev UI and Runner online.
- [ ] Authorize calendar permission and check the next real calendar event.
- [ ] Configure read-only Notion integration **on the phone only**, verify Daylight tasks load.
- [ ] Verify 0.2 Runner works with Windows powered off and after the phone is backgrounded.

### Guardrails

No Windows processes removed until their migration is proven safe and measured. Do not write private tokens to GitHub or a public Android build. PocketDock does not yet embed Codex nor manage private GitHub work.
