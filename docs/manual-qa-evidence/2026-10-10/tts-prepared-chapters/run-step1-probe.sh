#!/bin/bash
# Run from the tts-investigation worktree. No app/Gradle/local.properties changes.
# Installs a tiny test-only instrumentation APK with the existing debug key.
# Never uninstalls or clears data. The test APK remains installed (device rule).
set -euo pipefail
SDK="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
TOOLS="$SDK/build-tools/36.0.0"
ADB="$SDK/platform-tools/adb"
BASE="docs/manual-qa-evidence/2026-10-10/tts-prepared-chapters"
TMP=$(mktemp -d /private/var/folders/d1/vmr4nt310dbbwp2q08jcgk000000gn/T/opencode/prepared-probe.XXXXXX)
mkdir "$TMP/classes" "$TMP/dex"
cat > "$TMP/AndroidManifest.xml" <<'XML'
<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="com.retro99.parrot.preparedprobe">
  <uses-sdk android:minSdkVersion="29" android:targetSdkVersion="36"/>
  <application android:hasCode="true" android:label="Parrot prepared audio probe"/>
  <instrumentation android:name="com.retro99.parrot.preparedprobe.PreparedAudioProbe" android:targetPackage="com.retro99.parrot"/>
</manifest>
XML
javac -source 8 -target 8 -classpath "$SDK/platforms/android-36/android.jar" -d "$TMP/classes" "$BASE/PreparedAudioProbe.java"
"$TOOLS/d8" --min-api 29 --lib "$SDK/platforms/android-36/android.jar" --output "$TMP/dex" "$TMP/classes/com/retro99/parrot/preparedprobe/PreparedAudioProbe.class"
"$TOOLS/aapt2" link -I "$SDK/platforms/android-36/android.jar" --manifest "$TMP/AndroidManifest.xml" -o "$TMP/probe.apk"
zip -q -j "$TMP/probe.apk" "$TMP/dex/classes.dex"
"$TOOLS/apksigner" sign --ks "$HOME/.android/debug.keystore" --ks-pass pass:android --key-pass pass:android "$TMP/probe.apk"
"$ADB" -s RFCWC0SSVDM install -r androidApp/build/outputs/apk/debug/androidApp-debug.apk
"$ADB" -s RFCWC0SSVDM install -r "$TMP/probe.apk"
"$ADB" -s RFCWC0SSVDM shell am instrument -w com.retro99.parrot.preparedprobe/com.retro99.parrot.preparedprobe.PreparedAudioProbe
echo "Temporary build: $TMP"
