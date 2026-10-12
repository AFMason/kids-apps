# Kids' Tablet Apps: Setup & Deploy Guide

Custom offline apps for Orlando's Samsung Galaxy tablet, built as web apps, wrapped into APKs with Capacitor, and installed over Wi‑Fi with ADB.

## Why this approach

- **Offline by design**: everything is bundled inside the APK, so apps work on planes and in hotels with no internet.
- **No native Android code**: each app is a single HTML/JS file.
- **Fast iteration**: `adb install -r` updates an app in place over Wi‑Fi.

## Files

| File | What it is |
|---|---|
| `robot-builder.html` | Robot building game for a 6-year-old. Single self-contained file, no external assets. Becomes `www/index.html` in its Capacitor project. |
| `sam-the-sheep.html` | Sleep trainer nightlight, clock and alarm: red at night, green in the morning. |
| `README.md` | This guide. |

Suggested layout as more apps are added:

```
kids-tablet-apps/
├── README.md
├── deploy.sh
├── robot-builder/          # one Capacitor project per app
│   ├── www/index.html      # ← robot-builder.html goes here
│   ├── capacitor.config.json
│   └── android/
└── <next-app>/
```

---

## 1. Prerequisites (computer)

- **Node.js** (LTS) and npm
- **Android Studio** (installs the Android SDK, platform-tools and a JDK)
- **ADB** on your PATH: it comes with Android SDK platform-tools. Check with `adb version`.

> **Windows:** use `gradlew.bat assembleDebug` (or `.\gradlew assembleDebug` in PowerShell) instead of `./gradlew`. Run `deploy.sh` from Git Bash or WSL. The ADB commands are identical.

## 2. Wrap a web app as an APK (Capacitor)

From inside the app folder, e.g. `robot-builder/`:

```bash
npm init -y
npm i @capacitor/core @capacitor/cli @capacitor/android

# App name + unique app ID (unique per app so they install side by side)
npx cap init "Robot Builder" au.mason.orlando.robotbuilder --web-dir www

mkdir -p www
cp ../robot-builder.html www/index.html

npx cap add android
npx cap sync android
```

Build the debug APK:

```bash
cd android
./gradlew assembleDebug
# Output: android/app/build/outputs/apk/debug/app-debug.apk
```

Optional tweaks in `android/app/src/main/AndroidManifest.xml`, on the main `<activity>`:

- Lock to landscape: `android:screenOrientation="landscape"`
- App icon: replace the files in `android/app/src/main/res/mipmap-*`, or use `npx @capacitor/assets generate` with an `assets/icon.png`.

After editing the HTML, run `npx cap sync android` again, then rebuild.

## 3. Prepare the tablet (one-time per install session)

### 3a. Turn off Samsung Auto Blocker

Auto Blocker blocks ADB connections and installs from unknown sources.

1. **Settings → Security and privacy → Auto Blocker** → toggle **off**. If you can't find it, search "Auto Blocker" in Settings.
2. If it won't switch off, check for **Maximum restrictions** (One UI 7) on the same screen and turn that off first.

### 3b. Enable Developer options + Wireless debugging

1. **Settings → About tablet → Software information** → tap **Build number** 7 times.
2. **Settings → Developer options → Wireless debugging** → on.
3. Tablet and computer must be on the **same Wi‑Fi network**.

### 3c. Pair and connect

On the tablet, tap **Pair device with pairing code**. It shows an IP:port and a 6-digit code.

```bash
adb pair <tablet-ip>:<pairing-port>      # enter the 6-digit code
adb connect <tablet-ip>:<connect-port>   # port from the main Wireless debugging screen (different from the pairing port)
adb devices                              # should list the tablet
```

Pairing is remembered. Next time you usually only need `adb connect`, but the connect port changes each time Wireless debugging is toggled.

## 4. Install / update an app

```bash
adb install -r robot-builder/android/app/build/outputs/apk/debug/app-debug.apk
```

`-r` replaces the existing install and keeps saved data, such as his robot gallery.

### Build-and-push script (`deploy.sh`)

```bash
#!/usr/bin/env bash
# Usage: ./deploy.sh <tablet-ip:port> [app-folder ...]
# Builds and installs each app folder (default: all folders with a capacitor.config.json)
set -euo pipefail
TARGET="$1"; shift || true
adb connect "$TARGET"

APPS=("$@")
if [ ${#APPS[@]} -eq 0 ]; then
  mapfile -t APPS < <(find . -maxdepth 2 -name capacitor.config.json -exec dirname {} \;)
fi

for app in "${APPS[@]}"; do
  echo "==> $app"
  (cd "$app" && npx cap sync android && cd android && ./gradlew -q assembleDebug)
  adb -s "$TARGET" install -r "$app/android/app/build/outputs/apk/debug/app-debug.apk"
done
echo "Done."
```

## 5. Lock it back down (before handing it over)

1. **Wireless debugging** → off.
2. **Auto Blocker** → back on.

Installed apps keep working with Auto Blocker on. It only blocks new installs and ADB, so batch all installs and updates into one session before the trip.

## 6. Kid-proofing

- **Samsung Kids**: sideloaded apps don't appear automatically. In Samsung Kids → parental controls → apps, add each custom app to the allowed list.
- **Family Link**: if his Google account is supervised, Developer options may be blocked. Do the ADB setup from a parent account or before supervision applies.

---

## Robot Builder: app notes

**How it plays**

- **Building**: six big tabs (Head, Face, Body, Arms, Legs, Top) → tap a part → tap a colour to paint it. There are 5–6 parts per category and 10 colours.
- **Action buttons**: 🎲 surprise robot · 💃 dance party with a tune · 📸 save to gallery · 🖼️ saved robots.
- **Extras**: tap the robot to beep-boop, and tap the name to re-roll it (e.g. "Turbo Sprocket 3000").
- **Animations**: eyes blink, propellers spin and hover-jets flicker.
- **Gallery delete** needs two taps, to prevent accidents.

**Technical**

- Pure HTML/CSS/JS with inline SVG robot parts. There are no external assets, fonts or network calls.
- Sounds are synthesised with the Web Audio API, so there are no audio files.
- State lives in `localStorage`: `robot-current` holds the current robot and `robot-gallery` holds up to 40 saved robots. All access is wrapped in try/catch.
- Works in landscape (side-by-side) and portrait (stacked).
- Tap targets are large (≥ 58 px swatches, 84 px buttons). Text selection, the long-press context menu and pinch-zoom are disabled.

**Adding parts**: append to the relevant array in the `P` object in the script. Each part is `{id, d: colour => '<svg markup>'}` drawn in a 400×480 coordinate space:

| Category | Region (approx.) | Notes |
|---|---|---|
| top | y 40–120 | Drawn behind the head, so bases can tuck under it |
| head | y 100–206, centred x 200 | |
| face | centred (200, 155) | Drawn on top of the head |
| body | y 194–330 | Includes the neck |
| arms | left arm only, shoulder at (122, 230) | Mirrored automatically for the right arm. The shoulder must be the top-right of the arm's bounding box so the dance rotation pivots correctly. |
| legs | y 320–450 | Drawn first, behind the body |

Thumbnail crop boxes for each category are in `VB`.

## Sam the Sheep: app notes

`sam-the-sheep.html` is a nightlight, clock and alarm, and a "stay in bed until green" trainer. Capacitor ID suggestion: `au.mason.orlando.samthesheep`.

- **Red** (Sam asleep, with floating Zzz and slow breathing) runs from 7pm to 7am local time. **Green** (Sam awake and bouncing, tap him to hear a baa) covers the rest of the day.
- **Grown-up settings**: hold the top-right corner for 3 seconds. You can set the sleep and wake times, the alarm (on/off, time, baa tune or chime), night brightness, 12/24-hour time, and whether the clock shows at night. There are also test-alarm and day/night preview buttons.
- **Alarm**: starts quietly and gets louder, repeats every 5 s, and stops by itself after 5 min. Tap Sam to stop it.
- **Staying on**: the app uses the Wake Lock API while the Battery API reports charging. If it's unplugged, the screen is allowed to sleep. Android WebView may ignore Wake Lock, so also add this to `MainActivity.java` in `onCreate`: `getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);`. Also turn on **Developer options → Stay awake** (screen stays on while charging).
- Settings are stored in `localStorage` under `sam-settings`.

## Snake Maths: app notes

`snake-maths.html` is an adaptive maths practice game. Capacitor ID suggestion: `au.mason.orlando.snakemaths`.

- A question shows at the top and the answers are 3–4 big apples. Each right answer feeds the snake, which grows one segment longer (up to 60).
- **Difficulty**: 6 first-try correct answers in a row moves up a level, and 3 misses out of the last 4 steps back down. Each level gives the snake a new name and colours.
- **Levels (15)**: add to 10 with dots → take away from 10 with dots → add and take away to 20 → missing numbers and 2D shapes (sides and corners) → 2-digit add and take away → 2/5/10 times tables and area by counting squares → 2-digit with carrying and borrowing → perimeter and 3/4 times tables → 3D shapes (faces, edges, corners) and sharing → area = length × width and L-shapes on a grid → volume by counting cubes → volume = l × w × h → 6–9 times tables, L-shape area and division → mixed → two-step sums and missing sides from a given area or perimeter.
- The pictures (shapes, grids, cube stacks) are inline SVG drawn by the question generators.
- Capacitor project: `snake-maths/` (Gradle uses the JDK 21 path in `android/gradle.properties`).
- 🔊 reads the question aloud (speechSynthesis; needs a TTS voice on the tablet).
- **Grown-up settings**: hold the top-right corner for 3 seconds to set the level or reset.
- State is stored in `localStorage` under `snake-maths`. Add levels by appending to `LEVELS`.

## Ideas for next apps

- Matching/memory game with robot parts
- Simple drawing/colouring pad (save to gallery)
- Counting or letter-tracing game
- Offline story/flashcard viewer for the trip
