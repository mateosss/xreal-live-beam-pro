# XREAL Beam Pro — Stereo Camera Passthrough

Human note: LLM-built utility app to do a stereoscopic live stream of the stereo xreal beam pro cameras to the xreal glasses (tested on air 2 ultra ones). The original prompt was derived from
[gabriellupu/xreal-glasses-live-beam-pro](https://github.com/gabriellupu/xreal-glasses-live-beam-pro), copied to [`docs/xreal_beampro_stereo_passthrough_spec.md`](docs/xreal_beampro_stereo_passthrough_spec.md), with additional prompts to fix issues. Automated apk, code, and text follows, beware.

Android app for the XREAL Beam Pro. It opens both rear cameras, places their feeds side by side
(left eye | right eye) and shows the result full-screen on connected XREAL Air glasses, for
real-time 3D passthrough.

Kotlin · Camera2 · OpenGL ES 2.0 · minSdk 31 / targetSdk 34 · no XREAL SDK required.

## Build & install

Requires JDK 17 and the Android SDK (platform 34), with `sdk.dir` set in `local.properties`
or `ANDROID_HOME` set.

```sh
./gradlew assembleDebug
adb connect <BeamPro_IP>:5555          # wireless debugging, or use USB
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n com.xreal.stereoview/.MainActivity
```

Connect the glasses before or after launch; the app reacts to the display being plugged in or
removed. For true 3D, the glasses must be in their 3D/SBS mode (3840×1080), so each eye sees
one half. In 2D mode both eyes see the whole frame.

## Releases and CI

`.github/workflows/android.yml` builds, tests and lints every push and pull request, and
keeps the APK as a workflow artifact. Pushing a tag `vX.Y.Z` that matches `versionName` in
`app/build.gradle.kts` also creates a GitHub release with the APK and its SHA-256, using the
tag's annotation as the release notes:

```sh
# bump versionCode and versionName, commit, then
git tag -a v0.6.0 -m "Release notes…"
git push origin main v0.6.0
```

Tags pushed before the workflow existed (or more than three at once, which GitHub ignores)
can be released from *Actions → Android → Run workflow* with the tag as input.

**Signing.** Android only installs an update signed with the same key as the installed app,
so releases need one key that never changes. Create it once and store it as repository
secrets (*Settings → Secrets and variables → Actions*):

```sh
keytool -genkeypair -keystore release.jks -alias release -keyalg RSA -keysize 4096 -validity 10000
base64 -w0 release.jks    # → secret SIGNING_KEYSTORE_BASE64
```

plus `SIGNING_KEYSTORE_PASSWORD`, `SIGNING_KEY_ALIAS` (`release`) and `SIGNING_KEY_PASSWORD`.
Keep `release.jks` backed up and out of the repository. Without the secrets, CI signs with a
throwaway debug key and warns. Moving from a locally built debug APK to a release-signed one
needs one uninstall, because the keys differ.

## How it works

| Piece | File |
|---|---|
| Picks the camera pair and the left/right order, with a fallback chain | `camera/CameraUtils.kt` |
| Opens the cameras and streams into two SurfaceTextures, stepping down sizes on failure | `camera/CameraController.kt` |
| Pairs left/right frames before drawing | `camera/FrameSynchronizer.kt` |
| Per-camera intrinsics, lens distortion and orientation | `camera/CameraModel.kt` |
| Stereo rectification and the per-eye ray mapping | `render/StereoGeometry.kt`, `render/StereoShader.kt` |
| Gyro stabilization, applied identically to both eyes | `render/Stabilizer.kt` |
| Measures vertical offset and roll between the eyes from the image | `render/AlignmentEstimator.kt` |
| Side-by-side compositing and the auto-align pass | `render/StereoRenderer.kt` |
| Full-screen output on the glasses display | `render/StereoPresentation.kt`, `util/DisplayUtils.kt` |
| Touchscreen controls and DataStore settings | `ui/ControlsOverlay.kt`, `ui/SettingsSheet.kt`, `ui/SettingsViewModel.kt` |
| Capture profiles | `ui/Profiles.kt`, `ui/ProfilesDialog.kt` |
| Dataset recording: files, metadata, encoders, IMU | `record/*.kt`, `render/GlRecorder.kt` |
| Converts a recording to the EuRoC layout | `tools/to_euroc.py` |

**Camera selection.** The app ranks every pair of rear cameras it can find. Matching focal
lengths count most, then hardware-synced logical multi-cameras, then pairs the device lists
as concurrent, then the widest lens spacing. It tries the best few in turn, falling back to a
single camera feeding both eyes (2D) if none opens. Camera IDs missing from the public list
are probed too, because vendors sometimes hide extra cameras. *Settings → Camera pair* overrides
the automatic choice.

Left and right come from `LENS_POSE_TRANSLATION`, converted to the current screen orientation.
If the pose isn't available, the ID order is used. **Swap L/R** corrects either case.

**Rectification.** Raw feeds from two cameras are never perfectly parallel. A small tilt or
height difference between them produces vertical disparity, which the eyes can't fuse. Each
output pixel is therefore mapped to a ray in a shared rectified frame. That frame's x axis
runs along the baseline, and both eyes look along the same forward axis. The ray is then
projected through each camera's own intrinsics and lens distortion. The camera's factory
calibration (`LENS_POSE_ROTATION`, `LENS_INTRINSIC_CALIBRATION`, `LENS_DISTORTION`) is used
when it is plausible; otherwise the sensor orientation is used. Per-camera OIS, EIS and HAL
undistortion are turned off where possible, since each moves one eye's image independently.

**Auto-align.** This removes what calibration misses. It renders both eyes small, compares
mean row-intensity profiles of a strip at each edge, and corrects the vertical offset and
roll it finds. Row profiles barely change with horizontal disparity, and each strip's
disparity is compensated first, so the scene's depth doesn't bias the result.

**Scale.** In *1:1* mode, camera angles are shown at their real size in the glasses, using
the glasses' field of view (*Settings → Glasses field of view*). Disparity then matches what the
eyes would see directly. *Fill*, *Fit* and *Stretch* fit the camera image to the eye instead.
Half-SBS output (each half stretched to double width by the glasses) is accounted for. It is
detected from the output size, or set in *Settings → SBS layout*.

**Stabilization.** Rendering from a low-pass-filtered gyro orientation removes hand shake.
Both eyes get the same rotation, so the stereo alignment is unaffected.

**Output.** If the glasses show up as a presentation display, the stereo view goes there and
the phone screen keeps only the controls. Otherwise the view is drawn full-screen on the phone,
which the glasses mirror. In that mode, tap the view to hide or show the control panel.
*Settings → Output* forces mirrored mode.

**Sync.** A frame is drawn once both eyes have a new frame. If the second eye's frame doesn't
arrive within the threshold (33 ms by default), the frame is drawn anyway, so a slow camera
never freezes the other eye. The HUD shows each eye's fps, the left/right timestamp gap, and
how many frame pairs exceeded the threshold.

## Profiles

The button at the left of the control panel picks a **profile**: a named set of capture
settings (exposure, frame rate, resolution, focus, stabilization, scale, zoom, convergence,
and every recording and IMU option). Device setup is kept separately and survives profile
switches: camera pair, image rotation, L/R swap, eye alignment, glasses FOV, SBS layout,
lens calibration and output. Two profiles are built in:

| Profile | For |
|---|---|
| **Passthrough** | Live 3D viewing: 1:1 scale, medium stabilization, autofocus, up to 1080p |
| **EuRoC dataset** | SLAM recordings: raw images, focus locked at infinity, no stabilization, ≤720p at 30 fps, 60 Mbit/s H.264, IMU through the direct channel (fastest listener where unsupported) with calibrated and raw accelerometer/gyroscope, raw magnetometer and game rotation vector |

To edit a profile, switch to it and change settings: the panel shows *Name •* while they
differ, and *Save to profile* in Settings (or the profile's ⋮ menu) stores them. The profiles
dialog also creates profiles from the current settings, and renames, duplicates and deletes
them. *Restore built-ins* resets the two default profiles. A recording's `metadata.json`
names the profile it used.

## Recording datasets (stereo + IMU, for SLAM)

**Rec** records both cameras and the IMU until pressed again; the button turns red and shows
the elapsed time. Configure it under *Settings → Recording*:

| Option | Choices |
|---|---|
| Images | **Raw**: each camera's stream as delivered (lens distortion intact, not rotated). **Rectified**: what each eye shows (undistorted, rectified, aligned, with the current zoom, convergence and stabilization; *1:1* scale is recorded as *Fill*) |
| Format | H.264 or H.265 video per camera (hardware encoder, no B-frames), or one JPEG per frame |
| Video bitrate | 10–100 Mbit/s per camera |
| IMU rate | Fastest the sensors allow, 500/200/100 Hz through a listener, or a shared-memory direct channel (~800 or ~200 Hz) where the sensor supports it |
| IMU streams | Accelerometer, gyroscope, their uncalibrated versions with bias, magnetometer (calibrated or not), game/rotation vector, gravity, linear acceleration, barometer |
| Recordings… | Lists recordings with their sizes, and deletes them |

*Settings → Focus → Locked at infinity* keeps the intrinsics fixed, which SLAM needs. For SLAM,
also turn stabilization off when recording rectified images, or each frame is rotated by
its logged `stab_q`.

Recordings go to `Android/data/com.xreal.stereoview/files/recordings/<date_time>/`,
reachable over USB (MTP) or with
`adb pull /sdcard/Android/data/com.xreal.stereoview/files/recordings`:

```
metadata.json             device, every camera characteristic, every sensor, settings,
                          calibration (intrinsics, distortion, camera↔device rotation,
                          lens positions, rectified intrinsics), clocks, per-stream stats
settings.jsonl            settings at start and after every change, with timestamps
cam0/, cam1/              cam0 = left eye
  video.mp4 | data/<timestamp_ns>.jpg
  frames.csv              frame, timestamp_ns, render_ns [, fx, fy, cx, cy, stab_q…]
  capture_results.csv     per capture: timestamp, readout time, exposure, frame duration,
                          rolling shutter skew, ISO, AE/AF state, focus, OIS and distortion
                          correction modes, per-frame intrinsics, arrival time
imu/<stream>.csv          timestamp_ns, arrival_ns, values…, accuracy
```

**Timestamps.** `timestamp_ns` is the camera's `SENSOR_TIMESTAMP` (start of exposure of
the first row), the same value the SurfaceTexture reports for the stored frame. IMU
timestamps are `SensorEvent.timestamp` (CLOCK_BOOTTIME). Video frame *i* is row *i* of
`frames.csv`, and the MP4 presentation times are the sensor timestamps too.
`metadata.json → timestamps` says whether the camera clock is BOOTTIME and how to convert
it if not. Every row also carries its arrival time on BOOTTIME, so the two clocks can be
checked against each other. Join `frames.csv` and `capture_results.csv` on `timestamp_ns`
to get mid-exposure times (`+ exposure/2 + skew/2`).

**EuRoC conversion.** `tools/to_euroc.py REC OUT [--gray] [--mid-exposure] [--uncalibrated-imu]`
writes `OUT/mav0/{cam0,cam1,imu0}` with PNG frames, `data.csv` and `sensor.yaml`. It needs
Python 3 and ffmpeg. The gyroscope gives the IMU timestamps, and the accelerometer is
interpolated onto them. Camera-IMU extrinsics come from the factory lens poses and
orientations, so refine them with Kalibr, and replace the placeholder IMU noise values.

## Controls

The panel at the bottom of the touchscreen has a toolbar and, below it, sliders that the
chevron button collapses. When the glasses mirror the phone, tapping the view hides or shows
the whole panel.

| Control | Effect |
|---|---|
| Profile | Switches and manages profiles (see above) |
| Pause / Play | Closes / reopens the cameras |
| L/R | Swaps which camera feeds which eye (highlighted when on) |
| 1:1 / Fill / Fit / Stretch | True angular scale, or fit the camera image into each eye by cropping, letterboxing or stretching |
| Align | Measures and corrects vertical offset and roll between the eyes; point at a detailed scene and hold still |
| HUD | Frame-rate, sync and sensor statistics |
| ⓘ | Report of every camera (including hidden ones), the displays, camera system properties and the app log, with Share/Copy |
| ⚙ | Settings sheet, grouped into Capture and Recording (stored in profiles), Glasses & output, and Camera setup |
| Rec | Starts / stops a dataset recording |
| Convergence | ±200 px horizontal shift per eye, in opposite directions |
| Depth | Zooms each eye 1–2× about its center, which also scales disparity. Pinch on the touchscreen does the same |
| Auto / Manual | Auto exposure with a Brightness (EV) slider, or manual Shutter (down to 1/20000 s) and ISO sliders. Short shutter times cut motion blur at the cost of a darker, noisier image |

All settings persist through DataStore. The defaults (Swap L/R on, image rotation 270°,
convergence −50 px) are the ones that work on the Beam Pro.

**Frame rate.** *Settings → Frame rate* lists the rates the camera supports. The stream
resolution drops automatically to the largest size both cameras can deliver at that rate.
Rates beyond auto exposure's ranges need manual exposure, which sets the frame duration
directly. The HUD shows the exposure time, ISO and frame duration the sensor actually used.

## Glasses USB probe

*Settings → Glasses USB probe* checks whether the app can talk to the glasses directly over
USB, as [Monado](https://monado.freedesktop.org/) does for their IMU and, on the Air 2 Ultra,
their tracking cameras. It lists every USB device and interface Android exposes to apps
(video, HID, vendor-specific, with endpoints). For XREAL glasses (vendor `3318`) it then asks
for USB access, tries to claim each interface, and reads briefly from its input endpoints.
It never forces an interface away from its current owner, which could break the glasses'
display or tracking until they are replugged, and it sends nothing to the glasses. The
report ends with a verdict and can be shared; **Info** includes the USB listing too.

## Debugging without adb

If the app crashes, the next launch shows the report (Java stack trace, or the native
tombstone recovered from the system's exit records) with Share and Copy buttons. For problems
that aren't crashes, **Info** shares the camera setup together with the app's recent log.

## Not implemented / caveats

- Confirmed working on a Beam Pro with both rear cameras streaming. Camera exposure and
  display detection may still vary between firmware versions. If the image is rotated wrong,
  use *Settings → Image rotation*; if depth looks inverted, use *L/R*.
- No separate floating overlay window: a `TYPE_APPLICATION_OVERLAY` window would be mirrored
  to the glasses along with everything else, so the controls go on the phone screen instead
  (presentation mode) or on a hideable panel. This is why the app requests only the `CAMERA`
  permission.
- Stretch goals from the spec are not built: automatic convergence and L/R color matching.
- Recording was tested on the emulator (one camera). On the Beam Pro, check `metadata.json`
  first: the camera timestamp source, whether the direct IMU channel was used, and dropped
  frames (`stats`).

## License

MIT, see [LICENSE](LICENSE), except for `docs/xreal_beampro_stereo_passthrough_spec.md`.
