# F1TV UHD Patcher

Automated pipeline that patches the F1TV Android TV app to enable UHD/4K playback on any device. (only tested on my own Nvidia Shield TV Pro)

## How it works

1. **Checks** APKPure every 3 hours for new F1TV Android TV releases
2. **Downloads** the app bundle — Google Play primary (arm64 native via NVIDIA Shield profile), APKPure and APKMirror as fallbacks
3. **Patches** the smali to unlock 4K on any device (UHD/device whitelist bypass, the 1.5× display-size cap, the HDR-capability gate that unlocks the 2160p tier, and decoder/render tweaks — see [Applied patches](#applied-patches))
4. **Signs** all APKs with a consistent keystore
5. **Publishes** the patched bundle as a GitHub Release
6. **Notifies** via Pushover when a new patch is ready (or if it fails)

> **How 4K gets unlocked (and why stock skips the Shield).** F1TV picks a stream manifest from
> the `x-f1-device-info` header. The Android app sends `device=android_tv`, which returns the
> Widevine `HDR-UHD-CMAF-WV` manifest where **2160p exists only as HDR; SDR is hard-capped at
> 1620p server-side**. ClearVR only requests the 2160p tier if the content's HDR type is in
> *both* what the EGL renderer supports *and* what the display reports
> (`Display.getHdrCapabilities()`). F1's UHD is **HLG**; a panel that reports only HDR10 (like
> most setups behind an NVIDIA Shield) makes ClearVR reject HLG upstream and fall back to
> 1620p SDR. The pipeline forces the EGL HDR advertise **and** spoofs the display-capability
> check, so the core serves the 2160p tiles. How those HLG tiles are then rendered is decided
> **per device at runtime** from the GPU's real EGL colorspace support: a native HLG surface
> where the GPU has it (Homatics, Mi Box, most HLG TVs — full HDR, untouched colours), an
> HLG→PQ reroute where only the PQ colorspace exists (NVIDIA Shield — accurate 4K), and the
> core's direct decoder→view path where the GPU has neither (MediaTek / Amlogic TVs — the TV's
> own video pipeline handles HLG). **Needs the arm64 build** — see
> [Verify 4K is working](#verify-4k-is-working).

## Installing on your Android TV

### Prerequisites: Enable Developer Options & ADB

1. On your Android TV, go to **Settings > Device Preferences > About**
2. Scroll to **Build** and click it **7 times** to enable Developer Options
3. Go back to **Settings > Device Preferences > Developer Options**
4. Enable **USB debugging** (and **ADB over network** if installing wirelessly)
5. Note the **IP address** shown under Settings > Network & Internet, or Device Preferences > About > Status

### Option 1: ADB from a computer (recommended and tested)

Install ADB on your computer ([download platform-tools](https://developer.android.com/tools/releases/platform-tools)) and add it to your PATH.

**Via USB:**
```bash
# Connect your Android TV via USB cable, then:
adb devices  # Confirm it shows up — approve the prompt on your TV
```

**Via WiFi:**
```bash
adb connect 192.168.1.100:5555  # Replace with your TV's IP
# Approve the connection prompt on your TV
```

Then download `f1tv-uhd-patched.apkm` from the [latest release](../../releases/latest) and install:

```bash
# Uninstall the original F1TV first (required — different signing key)
adb uninstall com.formulaone.production

# Use the install script (auto-extracts and auto-detects device config)
./scripts/install.sh f1tv-uhd-patched.apkm
# With ADB over WiFi:
./scripts/install.sh f1tv-uhd-patched.apkm 192.168.1.100:5555
```

Or install manually:

```bash
# Unzip the bundle
mkdir f1tv && cd f1tv && unzip ../f1tv-uhd-patched.apkm

# Install (most Android TVs — NVIDIA Shield, Chromecast, etc. — are arm64).
# Use the arm64_v8a split; the 32-bit armeabi_v7a native libs can't sustain 4K.
adb install-multiple base.apk \
  config.arm64_v8a.apk \
  config.en.apk \
  config.xhdpi.apk
```

> **Install the `arm64_v8a` split, not `armeabi_v7a`.** The ClearVR decoder/renderer ships per
> ABI. On an arm64 device (NVIDIA Shield included) the 32-bit split forces ClearVR to run in
> 32-bit, which commonly can't allocate the 4K secure HEVC decoder — you get `TM4014` /
> "acquireVdecResource not enough" errors. `install.sh` picks arm64 automatically when it's in
> the bundle; a bundle with only `armeabi_v7a` (e.g. the APKPure fallback source) can't do
> reliable 4K on the Shield.

The install script accepts `.apkm`, `.xapk` files, or a directory of extracted APKs.

### Option 2: Send & install directly on the TV (not tested yet)

No computer needed after the initial download.

1. Download `f1tv-uhd-patched.apkm` from the [latest release](../../releases/latest) on your phone
2. Install [Split APKs Installer (SAI)](https://play.google.com/store/apps/details?id=com.mtv.sai&hl=en) on your Android TV (available on Play Store)
3. Transfer the `.apkm` file to your TV via:
   - **USB drive** — copy the file to a USB stick, plug it into the TV
   - **Send Files to TV** — install [this app](https://play.google.com/store/apps/details?id=com.yablio.sendfilestotv) on both your phone and TV, send the file over WiFi
   - **Google Drive / cloud** — upload to Drive, open it from the TV's file manager
4. Open SAI on the TV, select the `.apkm` file, and install

### Option 3: Wireless ADB apps (not tested yet)

If you don't have a computer but want a one-tap solution:

1. Install [Bugjaeger](https://play.google.com/store/apps/details?id=eu.sisik.hackendebug) on your Android phone
2. Enable ADB over network on your TV (see prerequisites above)
3. Connect Bugjaeger to your TV via its IP address
4. Use Bugjaeger to install the individual APK files

### Common split APKs

The bundle contains split APKs for different device configurations. You need `base.apk` plus the correct splits. Split names vary by source (`config.*` from APKPure, `split_config.*` from APKMirror, `com.formulaone.production.config.*` from Google Play):

| Split (any prefix) | When to include |
|---|---|
| `*.arm64_v8a.apk` | Most modern Android TVs (NVIDIA Shield, Chromecast, etc.) |
| `*.armeabi_v7a.apk` | Older 32-bit devices |
| `*.x86.apk` | Some emulators |
| `*.en.apk` | English — replace `en` with your language code |
| `*.xhdpi.apk` | Standard TV density — almost always needed |

> **Note:** You must uninstall the original F1TV app before installing the patched version (different signing key). This means you'll need to log in again.

## Setup your own pipeline

### 1. Fork and enable Actions

Fork this repo and enable GitHub Actions in the Actions tab.

### 2. Custom apkeep fork

The pipeline uses a custom build of [apkeep](https://github.com/EFForg/apkeep) with an NVIDIA Shield TV device profile added to [rs-google-play](https://github.com/EFForg/rs-google-play). This allows downloading the arm64 native variant directly from Google Play.

To set up your own:

1. Fork [EFForg/rs-google-play](https://github.com/EFForg/rs-google-play), add your device profile to `gpapi/device.properties`, delete `gpapi/src/device_properties.bin`, commit & push
2. Fork [EFForg/apkeep](https://github.com/EFForg/apkeep), change `Cargo.toml` to point `gpapi` at your rs-google-play fork, commit & push
3. Tag a release (`git tag v0.18.0-shield && git push origin v0.18.0-shield`) — the included workflow builds the binary automatically
4. Update `APKEEP_CUSTOM_TAG` in `patch.yml` to match your tag

A device profile dump script is included at `scripts/dump_device_props.sh` — connect your Android TV via ADB and run it to generate the profile.

### 3. Secrets

In **Settings > Secrets > Actions**, add:

| Secret | Purpose | Required |
|---|---|---|
| `GOOGLE_EMAIL` | Google account email for Play Store downloads | For Google Play |
| `GOOGLE_AAS_TOKEN` | Google AAS token ([how to obtain](https://github.com/EFForg/apkeep/blob/master/USAGE-google-play.md)) | For Google Play |
| `KEYSTORE_B64` | Base64-encoded signing keystore (persistent key across builds) | Recommended |
| `KEYSTORE_PASS` | Keystore password | Recommended |
| `KEYSTORE_ALIAS` | Key alias | Recommended |
| `PUSHOVER_APP_TOKEN` | Pushover app token for notifications | Optional |
| `PUSHOVER_USER_KEY` | Pushover user key for notifications | Optional |

Without Google Play credentials, the pipeline falls back to APKPure (armeabi-v7a only) and APKMirror automatically.

**Generate a persistent keystore:**

```bash
keytool -genkeypair -keystore f1tv.keystore -storepass yourpass \
  -keypass yourpass -alias f1tvpatch -keyalg RSA -keysize 2048 \
  -validity 10000 -dname "CN=F1TV UHD Patch"

# Encode and add as KEYSTORE_B64 secret
base64 -w0 f1tv.keystore
```

Without a persistent keystore, a new key is generated each build — you'll need to uninstall before each update.

### 4. Manual trigger

If the automatic download fails, you can trigger the workflow manually:

- **With direct URL**: Go to Actions > F1TV UHD Patch > Run workflow, paste an `.apkm` URL
- **Force rebuild**: Check the "Force rebuild" option to re-patch an existing version

## Project structure

```
.github/workflows/patch.yml   # CI pipeline (check, download, patch, release)
scripts/
  check_version.py             # APKMirror RSS check (merges several feeds; survives app renames)
  download_apkpure.sh          # APKPure download (primary, and fallback when APKMirror fails)
  set_login.sh                 # Store your F1 account on the TV for automatic re-login (reads .env)
  download_apkm.py             # Playwright-based APKMirror downloader (fallback)
  patch.sh                     # Smali patching, signing, bundling
  install.sh                   # ADB install helper (accepts .apkm, .xapk, or directory)
  stream_stats.sh              # Live decoder/HDR stats over ADB (verify 4K)
  capture_logs.sh              # Collect player/decoder logs for an issue report
  dump_device_props.sh         # Dump Android TV device profile for rs-google-play
device_profiles/
  nvidia_shield_tv.properties  # NVIDIA Shield TV profile for Google Play downloads
```

## Requirements (local use)

Only needed if running scripts locally outside CI:

- Python 3.10+, Playwright (`pip install playwright && playwright install chromium`)
- Java, apktool, zipalign, apksigner
- ADB (for install.sh)

## Verify 4K is working

After installing (on an **arm64** bundle, connected to an **HDR-capable TV**), start a session and
watch the live decoder stats over ADB:

```bash
./scripts/stream_stats.sh 192.168.1.100:5555   # your TV's IP, or omit if on USB
```

You want to see, once the stream ramps up:

- **Decoder Resolution: `3840x2160`** — full 4K. If it plateaus at `2880x1620`, the 2160p tier isn't
  being served (see the checklist below).
- **Video Codec: HEVC** and a healthy bitrate (≈15–25 Mbps).
- On a genuinely HDR-capable device you'll also get **HDR10/HLG** output; on an HDR10-only panel
  (e.g. many NVIDIA Shield setups) the 4K plays as a clean **SDR downconvert** — full resolution,
  accurate colours, just not HDR's extra brightness/gamut. See [Build options](#build-options) to
  push those setups toward true HDR10.

The patched build also exposes the in-app **quality selector** so you can confirm `3840×2160` is
offered (debug overlays are left off in release builds).

If you're stuck at 1620p, check in order:

1. **ABI** — `adb shell getprop ro.product.cpu.abi` should be `arm64-v8a`, and you must have installed
   the `config.arm64_v8a.apk` split (not `armeabi_v7a`). A 32-bit install can't do 4K.
2. **Build flags** — the bundle must be built with `F1TV_HLG_BYPASS` **and** `F1TV_DISPLAY_HDR_SPOOF`
   on (both default; CI sets them). Public releases from before these defaults do **not** have them.
   If your picture is **dark with crushed blacks** on an HLG-capable box, you are on a release ≤ 3.0.49.4
   (unconditional HLG→PQ reroute) — update.
3. **4K TV** — the panel must actually be 4K (the decode target follows the display). An HDR-capable
   panel additionally gets HDR output.

## Applied patches

All patches are applied to every device (no runtime device gating) unless noted. The two that
actually unlock 2160p are **HDR advertise** and **display-capability spoof**.

| Patch | File · method | What it does |
|---|---|---|
| UHD / device unlock | `DeviceSupportImpl` · `validateIsUhdSupportedDevice`, `validateTmSdkSupport`, `validateLowRamDeviceSupport`, `validateApiLevelSupport` | Each returns `Pair(true, null)`, so the device passes every UHD-capability gate (brand/product whitelist, secure-decoder probe, low-RAM check, API-level check). |
| **Display-capability spoof** *(2160p unlock)* | `DeviceParameters` · `doesDisplaySupport` | Returns `true` so the core believes the panel accepts F1's HLG and serves the **2160p tiles** instead of rejecting HLG upstream and dropping to 1620p SDR. Default on; disable with `F1TV_DISPLAY_HDR_SPOOF=0`. |
| **HDR advertise** *(2160p unlock)* | `EGLRenderTarget` · `getIsBt2020HlgExtensionSupported` | Returns `true`, so `DeviceParameters` reports PQ+HLG EGL support and the backend offers the HDR 2160p tier. The stock probe is kept as `uhdRealHlgSupported()`, plus a raw PQ probe `uhdRealPqSupported()` (no API-33 gate, which wrongly excluded the Shield on Android 11); the two patches below use those real answers. Default on; disable with `F1TV_HLG_BYPASS=0`. |
| Quality selector | `DiagnosticsPreferenceManagerImpl` · `isVideoQualityEnabled` | Returns `true` so the in-app quality picker is visible. (The debug overlays are intentionally left off.) |
| 4K display size | `TrueTVDisplaySizeHelper` · `getDefaultDisplaySize` | Hardcodes a `3840×2160` panel via `getTrueDisplaySizeIfTV`, lifting ClearVR's ~1.5× display-size cap (which otherwise limits a 1080p UI surface to `2880×1620`). |
| Runtime HLG/PQ render path *(correct colours everywhere)* | `RenderTargetConfig` · `requireHLG`, `require2020PQ` | `requireHLG → HLG && uhdRealHlgSupported()`, `require2020PQ → PQ ‖ (HLG && !uhdRealHlgSupported())`. GPUs with the HLG colorspace get a **native HLG surface** (full HDR, no colour shift — fixes the dark/crushed picture of the old unconditional reroute, #20); GPUs with only PQ (NVIDIA Shield) get the **HLG→PQ reroute** that gives accurate 4K there. SDR content untouched. |
| Surface HDR capability *(SDK ≥ SP164 / F1TV 3.0.49+)* | `TMViewModel` · `getDisplayObjectMappings` | Reports the GL surface as HLG-capable only when it really is (`uhdSurfaceHlgRenderable()` = real HLG **or** real PQ). Devices with neither colorspace (MediaTek / Amlogic TVs) are no longer pushed onto the GL HDR path, which failed or stuttered there (TM4014 / HDR flicker, #5 #11 #17); the core routes them to its direct decoder→view path instead. Older SDKs have no such call and are skipped. |
| Decoder capability spoof | `DecoderCapability` · `getAsCoreProtobuf` | Reports non-zero secure tile slots/rows/cols (16/5/5) so the backend serves the full-resolution tile tier instead of a reduced one. |
| Decoder max-resolution hint | `VideoDecoderBridgeShared` · `getMediaFormat` | Adds `max-width`/`max-height` (the decoder's largest supported resolution) to the decoder format. ClearVR configures the decoder at the first tile size (e.g. 960x544) and relies on a mid-stream reallocation when the stream ramps to 3840x2176; MediaTek decoders fail that reallocation (`acquireVdecResource … not enough` → TM4014, #5 #11 #17). Default on; disable with `F1TV_DECODER_MAX_RES=0`. |
| NVIDIA workaround off | `Quirks` · `deviceNeedsNoPostProcessWorkaround` | Returns `false` so the decoder isn't forced into a lower-quality no-post-process path on NVIDIA devices. |
| Version tag | `apktool.yml`, `BuildConfig` | Appends `-UHD` to the version name so patched builds are identifiable. |
| Custom radio *(opt-in)* | `TiledPlayer` · `<init>`, `TiledPlayerActivityTv` · `dispatchKeyEvent`, ClearVR `AudioTrack.write` (found by signature), `SettingsFragment.PrefFragment` · `onCreatePreferences`, `SplashActivity` · `onCreate` (updater) | Only with `F1TV_CUSTOM_AUDIO_URL` set — see [Custom radio audio](#custom-radio-audio-optional). |
| Self-check | *(all of the above)* | After patching, `patch.sh` verifies every marker is present and **fails the build** on any miss, so a new ClearVR SDK can't silently ship a half-patched bundle. |

> Removed in this release: the *"device-model spoof"* (`model=chromecast`). It patched the first
> `Build.MODEL` read in `TvApplication`, which sits in `getDeviceType()` (the Amazon Fire-Stick
> check), not in `getRequestHeader()` — the header always carried the real model, and the only
> effect was mis-detecting Amazon devices. The UHD whitelist is bypassed client-side anyway. Also
> removed: the opt-in `F1TV_DIRECT_TO_VIEW` / `F1TV_PQ_REROUTE` build flags — the render path is
> now chosen per device at runtime (and the SDK in F1TV 3.0.49+ no longer has the app-level
> blit-mode switch the old flag patched).

## Build options

`patch.sh` reads these environment variables (all default to the values below; CI uses the defaults):

| Variable | Default | Effect |
|---|---|---|
| `F1TV_UNIVERSAL_APK` | `1` | Also output `f1tv-patched-universal.apk`: every split in the bundle merged into one APK (installs with `adb install -r`, a file manager, or an updater app such as [Obtainium](https://github.com/ImranR98/Obtainium) watching your fork's releases). Needs `APKEDITOR_JAR` pointing at an [APKEditor](https://github.com/REAndroid/APKEditor) jar (CI downloads it). Only contains the ABIs the source bundle had — Google Play source gives arm64 + armeabi-v7a, APKPure only armeabi-v7a. |
| `F1TV_UHD_PATCHES` | `1` | Master switch. `0` skips **all** UHD unlock/spoof and render-path patches (everything in the table above except the quality selector and version tag), leaving playback exactly as shipped — for devices the stock app already serves UHD to, e.g. to add only the custom radio. Version tag becomes `-RADIO`. |
| `F1TV_HLG_BYPASS` | `1` | Advertise EGL HDR (PQ+HLG) so the backend offers the 2160p tier, and install the runtime HLG/PQ render-path patches. Required for 4K. |
| `F1TV_DISPLAY_HDR_SPOOF` | `1` | Force the display-capability check to accept all HDR types so the core serves 2160p on HDR10-only panels. Required for 4K on the Shield. |
| `F1TV_DECODER_MAX_RES` | `1` | Tell the video decoder up front how large the stream can get (adaptive-playback hint) so the 2160p ramp needs no mid-stream decoder reallocation. |
| `F1TV_DEBUG_LOGS` | `0` | Set `1` to route the ClearVR SDK log (INFO level) to logcat under the tag `ClearVR`, and make the native core write its own log to `/sdcard/Android/data/com.formulaone.production/files/clearvr.tmlog`; the stock app silences both. CI turns this on for every non-`main` branch, so **dev pre-releases log (`-UHD-dbg`) and `main` releases don't**. `scripts/capture_logs.sh` collects both. |

> **Result on an NVIDIA Shield + HDR10 TV:** true **3840×2160** with accurate colours, output as a
> clean SDR downconvert (the Shield's GPU lacks the HLG EGL colorspace, so full HDR10 to the panel
> isn't reliable — but the 4K resolution and colour accuracy are the wins).
> **On an HLG-capable device** (Homatics, Mi Box, most HLG TVs): true 3840×2160 **HLG HDR**, untouched.

## Reporting a playback problem

Please attach the output of the log helper — it is what makes "video error" / `TM40xx` /
HDR-flicker reports actionable:

```bash
./scripts/capture_logs.sh 192.168.1.100:5555   # start it, then play the stream that fails
```

It records the device/ABI/HDR/EGL capabilities once and then only the player, decoder and display
log lines until you press Ctrl+C, into a `f1tv-logs-*.txt` next to it.

## Custom radio audio (optional)

Replace the F1 commentary with a radio stream (e.g. a local-language radio commentary) on live
sessions, automatically kept in sync with the picture. Off unless `F1TV_CUSTOM_AUDIO_URL` is set.

| Variable | Default | Effect |
|---|---|---|
| `F1TV_CUSTOM_AUDIO_URL` | *(unset)* | Direct Icecast/SHOUTcast **MP3 or AAC** stream URL (redirects such as StreamTheWorld's `livestream-redirect` are fine). HLS `.m3u8` is not supported. |
| `F1TV_CUSTOM_AUDIO_ADJUST_MS` | `0` | Starting sync adjustment; you normally fine-tune it on the TV instead. |
| `F1TV_CUSTOM_AUDIO_FALLBACK_MS` | `20000` | Base delay used only if ClearVR reports no wall clock for the stream. |
| `F1TV_CUSTOM_AUDIO_ENABLED` | `1` | Whether the radio is on by default (toggle on the TV). |

In CI, set them as repository **variables** (or a secret for the URL) — the workflow passes them
through. The URL is compiled into the APK, so it is visible in your published release.

Other CI variables: `F1TV_SELF_UPDATE=0` leaves out the in-app updater (on by default; it follows
this repository's releases), `APKMIRROR_FEED_URLS` (space-separated) replaces the built-in list of
APKMirror RSS feeds used to detect new versions.

**Automatic re-login.** F1 TV restores its session at launch with a "silent login"; when F1's
server rejects it, the app wipes the session and shows the sign-in screen. Store your account once
from the PC and the patch signs back in by itself, with the app's own password login and the same
device id (so no extra device slot):

```bash
ADB_SERIAL=192.168.1.50:5555 scripts/set_login.sh path/to/.env   # F1_username / F1_password
```

The account goes over adb only (the receiver requires `android.permission.DUMP`, which only the adb
shell and the system hold), is encrypted with an Android Keystore key on the TV, and is never part
of the APK or a release. Attempts are rate-limited (one per 10 minutes). The patch's settings
screen shows the stored account and the last result; `scripts/set_login.sh --clear` removes it.

**Settings on the TV.** F1 TV's settings page (side menu → Settings) gets an extra
**Grand Prix Radio** button, built from F1 TV's own button layout. It opens the patch's settings
screen: radio on/off, automatic sync, circuit-sound matching, reset correction, automatic
sign-in (with the stored account and device id), diagnostic logs and updates. The button is added
at runtime by recognising the page's button ids, so no F1 TV class is hooked for it.

**One device, for good.** F1 TV registers itself as a device identified by `ANDROID_ID`, which
Android derives from the app's signing key (and resets on a factory reset): a new keystore, a
reinstall after a key change or the Play Store app each count as another device, and F1 refuses
logins once *"the limited number of active associated devices"* is reached. The patch pins the
first device id it sees and keeps using it. `set_login.sh` prints that id; add it to `.env` as
`F1_DEVICE_ID=…` and every later install, with any key, comes back as the same device.
Devices that already used up slots are released on F1's side (account page / F1 TV support).

**Surviving F1TV updates.** Every hook is first looked up at its known class path and, if F1TV
moved or renamed it, found again by structure: the player class by its constructor (an Activity)
and its use of ClearVR's `TiledmediaPlayer`, the key hook in every activity that uses that player
(a `dispatchKeyEvent` is added if it has none), the sign-in state stream by the class that has
`performLogin` + `performSilentLogin`, the device id wherever F1 TV reads `android_id`, and the
launch hook in the launcher activity from the manifest. At runtime the extension reaches F1TV's
player only through reflection by type (`HostPlayer`), so renamed fields or Kotlin accessors do
not break it. Required hooks that cannot be found fail the build, so no broken release is published.

**How the auto-sync works.** ClearVR reports the wall-clock time of the frame on screen. The
patch plays the radio delayed by *video lag* (now − that wall clock) plus your adjustment, so it
follows stream start-up latency, pausing, rewinding and timeshift on its own. The adjustment only
absorbs the radio's own broadcast latency, so you calibrate it once per station. The radio is
buffered (up to 150 s); until enough is buffered — and whenever it can't cover the moment you're
watching — you hear the normal F1 audio. Replays/VOD always use the F1 audio.

**On the TV** (during playback) press **MENU** or a **colour key**, or **long-press OK**:

| Key | Action |
|---|---|
| ◀ / ▶ | Radio 0.25 s earlier / later |
| ▼ / ▲ | Radio 1 s earlier / later |
| OK | Radio on/off |
| BACK | Close |

Settings are remembered. Logs: `adb logcat -s F1Radio`.

The player code is in [`extension/`](extension/) (plain Java, platform APIs only) and is compiled
by `patch.sh` into an extra `classesN.dex`; it needs an Android SDK (`ANDROID_HOME` with a
platform and build-tools — preinstalled on GitHub's Ubuntu runners). Two smali hooks are added:
`TiledPlayer.<init>` and `TiledPlayerActivityTv.dispatchKeyEvent`. If an F1TV update renames
anything the extension calls, the build fails with a clear message rather than shipping a broken
APK.

## License

For personal/educational use only.
