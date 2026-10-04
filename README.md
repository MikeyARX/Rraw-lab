# R-RAW Lab v0.1

Phase 1 of Project R-RAW / RidderX RAW.

This is an Android 17 Camera2 engineering probe for still photos only. It does **not** claim that a device supports 12-bit or 14-bit RAW until Camera2 advertises that exact output format.

## What v0.1 does

- Enumerates every openable Camera2 camera ID and reports logical/physical-camera information.
- Reports RAW capability, RAW12, RAW14, and RAW_SENSOR support.
- Lists supported RAW resolutions and reported minimum frame durations.
- Reports sensor CFA, black level, white level, ISO range, exposure range, focal lengths, and manual-sensor capability.
- Shows a live preview.
- Captures one still RAW12 or RAW14 frame and saves the packed plane bytes unchanged plus a JSON sidecar.
- Captures RAW_SENSOR as DNG using Android `DngCreator` for comparison.
- Saves captures in `Downloads/RRAWLab`.
- Copies the full capability report to the clipboard so it can be pasted back into ChatGPT.

## Build

The included GitHub Actions workflow builds a debug APK with Android API 37 and uploads it as a workflow artifact.

## Important

RAW14 was added in Android 17 / API 37. A phone running Android 17 can still report RAW14 as unsupported if its camera HAL does not expose that format. `RAW_SENSOR` is a 16-bit storage container and is reported separately rather than being mislabeled as 14-bit.
