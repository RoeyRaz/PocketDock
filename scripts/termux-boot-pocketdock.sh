#!/data/data/com.termux/files/usr/bin/sh
# Optional Termux:Boot script. Copy manually only after testing the Runner.
# Termux:Boot requires the separate Termux:Boot Android app.
cd "$HOME/PocketDock" || exit 1
mkdir -p "$HOME/.local/share/pocketdock"
exec python runner/server.py >> "$HOME/.local/share/pocketdock/runner.log" 2>&1
