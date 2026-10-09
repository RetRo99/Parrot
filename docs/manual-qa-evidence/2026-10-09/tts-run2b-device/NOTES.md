# Run 2b device check (re-run) — 2026-10-09

Device: Samsung SM-S921B, serial `RFCWC0SSVDM`, the only device addressed (`-s RFCWC0SSVDM`
on every adb call; two other devices were attached and never addressed). Build:
`androidApp-debug.apk` from this worktree at commit `9c97acf6`, installed with
`adb install -r` only. No uninstall, no data clear, no sign-out, no network change. No
product code and no test was changed in this run.

Purpose: check on a phone that run 2b's fixes for TTS-F06 and TTS-F07 work, using the
long-press route to the Listening sheet that run 2b's blocked attempt did not try.

One line per check, written as the run went.

## Log

- Build `:androidApp:assembleDebug` succeeded; APK installed on `RFCWC0SSVDM`.
