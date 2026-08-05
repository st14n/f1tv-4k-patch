# Known Working / Not Working Devices

This file tracks compatibility reports for the F1TV UHD Patcher. If you are testing a device, consider contributing your findings here.

## Working ✅

| Device / Model | Status | Notes |
|---|---|---|
| NVIDIA Shield TV Pro (2019) | Working | Tested with arm64 build, outputs 4K SDR downconvert on HDR10 panel. |
| Chromecast with Google TV (4K) | Working | arm64 build works out of the box. |
| Xiaomi Mi Box S | Working | Requires `arm64_v8a` split; 4K playback confirmed. |
| Sony Google TV (XR series) | Working | Native 4K/HDR support; full HLG output on compatible displays. |

## Not Working / Known Issues ❌

| Device / Model | Status | Notes |
|---|---|---|
| NVIDIA Shield TV (2015) | Not Working | 32-bit SoC; cannot allocate secure HEVC decoder for 4K (`TM4014` error). |
| Amazon Fire TV Stick (4K Max) | Not Working | FireOS restricts ADB/side-loading patched apps; clearvr fails without native Google Play services integration. |
| Android TV boxes with Amlogic S905W | Partial / Laggy | Weak GPU causes frame drops on EGL path; requires `F1TV_DIRECT_TO_VIEW=1` workaround which breaks HDR colours. |
| Older 32-bit Android TVs (armeabi-v7a only) | Not Working | F1TV requires arm64 for hardware-accelerated 4K decoding; app may crash or cap at 1080p. |

## How to Report

If you are using a device not listed above, please open a new issue with:
- **Device Model**
- **Android Version**
- **CPU ABI** (`adb shell getprop ro.product.cpu.abi`)
- **Display Type** (HDR10 / HLG / SDR)
- **Observed Behavior** (e.g., capped at 1620p, crashes, washed-out colours, frame drops)
