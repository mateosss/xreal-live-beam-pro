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
| Touchscreen controls and DataStore settings | `ui/ControlsOverlay.kt`, `ui/SettingsViewModel.kt` |

**Camera selection.** The app ranks every pair of rear cameras it can find. Matching focal
lengths count most, then hardware-synced logical multi-cameras, then pairs the device lists
as concurrent, then the widest lens spacing. It tries the best few in turn, falling back to a
single camera feeding both eyes (2D) if none opens. Camera IDs missing from the public list
are probed too, because vendors sometimes hide extra cameras. *More → Camera pair* overrides
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
the glasses' field of view (*More → Glasses field of view*). Disparity then matches what the
eyes would see directly. *Fill*, *Fit* and *Stretch* fit the camera image to the eye instead.
Half-SBS output (each half stretched to double width by the glasses) is accounted for. It is
detected from the output size, or set in *More → SBS layout*.

**Stabilization.** Rendering from a low-pass-filtered gyro orientation removes hand shake.
Both eyes get the same rotation, so the stereo alignment is unaffected.

**Output.** If the glasses show up as a presentation display, the stereo view goes there and
the phone screen keeps only the controls. Otherwise the view is drawn full-screen on the phone,
which the glasses mirror. In that mode, tap the view to hide or show the control panel.
*More → Output* forces mirrored mode.

**Sync.** A frame is drawn once both eyes have a new frame. If the second eye's frame doesn't
arrive within the threshold (33 ms by default), the frame is drawn anyway, so a slow camera
never freezes the other eye. The HUD shows each eye's fps, the left/right timestamp gap, and
how many frame pairs exceeded the threshold.

## Controls

| Control | Effect |
|---|---|
| Pause / Play | Closes / reopens the cameras |
| Swap L/R | Swaps which camera feeds which eye |
| 1:1 / Fill / Fit / Stretch | True angular scale, or fit the camera image into each eye by cropping, letterboxing or stretching |
| Auto-align | Measures and corrects vertical offset and roll between the eyes; point at a detailed scene and hold still |
| Convergence | ±200 px horizontal shift per eye, in opposite directions |
| Depth | Zooms each eye 1–2× about its center, which also scales disparity. Pinch on the touchscreen does the same |
| Brightness | Exposure compensation, using the camera's own range |
| HUD | Frame-rate and sync statistics |
| Info | Report of every camera (including hidden ones), the displays, camera system properties and the app log, with Share/Copy |
| More | Camera pair, output mode, image rotation override, max resolution, sync wait, stabilization, glasses field of view, SBS layout, lens calibration source, manual alignment, reset adjustments |

All settings persist through DataStore.

## Debugging without adb

If the app crashes, the next launch shows the report (Java stack trace, or the native
tombstone recovered from the system's exit records) with Share and Copy buttons. For problems
that aren't crashes, **Info** shares the camera setup together with the app's recent log.

## Not implemented / caveats

- Confirmed working on a Beam Pro with both rear cameras streaming. Camera exposure and
  display detection may still vary between firmware versions. If the image is rotated wrong,
  use *More → Image rotation*; if depth looks inverted, use *Swap L/R*.
- No separate floating overlay window: a `TYPE_APPLICATION_OVERLAY` window would be mirrored
  to the glasses along with everything else, so the controls go on the phone screen instead
  (presentation mode) or on a hideable panel. This is why the app requests only the `CAMERA`
  permission.
- Stretch goals from the spec are not built: SBS recording, automatic convergence, L/R color
  matching, and lens-distortion correction from `LENS_INTRINSIC_CALIBRATION`.

## License

MIT, see [LICENSE](LICENSE), except for `docs/xreal_beampro_stereo_passthrough_spec.md`.
