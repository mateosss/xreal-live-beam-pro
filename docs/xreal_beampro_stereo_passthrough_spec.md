# XREAL Beam Pro — 3D Stereo Camera Passthrough Viewer
## App Specification for Claude Code

---

## Overview

An Android app targeting the XREAL Beam Pro that opens both rear cameras simultaneously, composites their feeds into a Side-By-Side (SBS) stereo frame, and outputs it full-screen via DisplayPort to the connected XREAL Air glasses — creating a real-time 3D passthrough experience.

**Target device:** XREAL Beam Pro (Android 14, NebulaOS)  
**Output device:** XREAL Air / Air 2 / Air 2 Pro (connected via USB-C DP Alt)  
**Min SDK:** 31 (Android 12)  
**Target SDK:** 34 (Android 14)  
**Language:** Kotlin  
**Build system:** Gradle (AGP 8.x)

---

## Hardware Context

The Beam Pro has two rear 50MP cameras with ~50mm lens separation — approximately matching human interpupillary distance. This makes it geometrically suitable for stereoscopic 3D capture.

The XREAL Air glasses act as a pure DisplayPort monitor. Whatever is rendered on the Beam Pro's display (or a Presentation display surface) is mirrored to the glasses. There is no separate SDK call needed for the mirror — it's handled at the OS/DP level.

---

## Core Features

### 1. Dual Camera Preview (SBS Compositing)

- Open both rear cameras simultaneously using **Camera2 API** (`CameraManager`, `CameraDevice`)
- Identify left and right camera by querying `LENS_POSE_TRANSLATION` from `CameraCharacteristics`
- Stream both cameras at **1920×1080 @ 30fps** (or highest supported shared resolution)
- Composite the two feeds into a single **3840×1080 SBS frame** (left eye | right eye)
- Render using **OpenGL ES 2.0** via `GLSurfaceView` or `TextureView` with two `SurfaceTexture` bindings

### 2. Full-Screen Output to Glasses

- Use `WindowManager` flags to force full-screen, hide status/navigation bars
- On devices with multiple displays (Presentation API), detect the DP display and push the SBS surface there
- Lock screen orientation to **landscape**
- Prevent screen dimming/sleep while active (`FLAG_KEEP_SCREEN_ON`)

### 3. Camera Synchronization

- Use hardware-level sync where available (`SYNC_MAX_LATENCY` = 0)
- Fall back to software timestamp alignment: discard frame pairs with delta > 33ms
- Target sub-frame latency between left/right feeds

### 4. Controls Overlay (on Beam Pro touchscreen)

Since the Beam Pro screen mirrors to the glasses, controls must be shown on a **separate overlay** that stays on the Beam Pro screen only (use `TYPE_APPLICATION_OVERLAY` window or a second `Presentation`). UI should be minimal:

| Control | Action |
|---|---|
| Play/Pause | Toggle camera streams |
| Convergence slider | Adjust horizontal image shift (IPD correction) |
| Depth slider | Scale the SBS inter-eye distance digitally |
| Swap L/R | Swap left and right camera feeds |
| Brightness | Camera exposure compensation |
| Record | Capture SBS video to file (stretch goal) |

### 5. Settings / Persistence

Store user preferences via `DataStore<Preferences>`:
- Last convergence offset
- Last depth scale
- L/R swap state
- Preferred resolution/fps

---

## Technical Architecture

```
MainActivity
├── CameraManager
│   ├── openCamera(leftCameraId)  → CameraDevice (left)
│   └── openCamera(rightCameraId) → CameraDevice (right)
│       └── CaptureSession → ImageReader / SurfaceTexture
│
├── StereoRenderer (GLSurfaceView.Renderer)
│   ├── OES texture for left eye
│   ├── OES texture for right eye
│   ├── SBS compositing shader (GLSL)
│   └── onDrawFrame() → render to display surface
│
├── DisplayManager
│   └── detect Presentation display (DP output)
│       └── StereoPresentation extends Presentation
│           └── hosts GLSurfaceView
│
└── ControlsOverlayService (optional)
    └── Floating UI on main display only
```

### GLSL Fragment Shader (SBS Compositing)

```glsl
// Left eye occupies [0.0, 0.5] in x, right eye [0.5, 1.0]
// convergence uniform shifts images horizontally for depth comfort

uniform samplerExternalOES uLeftTexture;
uniform samplerExternalOES uRightTexture;
uniform float uConvergence;   // range: -0.05 to 0.05
uniform mat4 uLeftTransform;
uniform mat4 uRightTransform;

varying vec2 vTexCoord;

void main() {
    if (vTexCoord.x < 0.5) {
        vec2 leftUV = vec2(vTexCoord.x * 2.0 + uConvergence, vTexCoord.y);
        gl_FragColor = texture2D(uLeftTexture, leftUV);
    } else {
        vec2 rightUV = vec2((vTexCoord.x - 0.5) * 2.0 - uConvergence, vTexCoord.y);
        gl_FragColor = texture2D(uRightTexture, rightUV);
    }
}
```

---

## Permissions

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.RECORD_AUDIO" />  <!-- for video recording stretch goal -->
<uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE"
    android:maxSdkVersion="28" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.SYSTEM_ALERT_WINDOW" />  <!-- overlay -->

<uses-feature android:name="android.hardware.camera" android:required="true" />
<uses-feature android:name="android.hardware.camera.level.full" android:required="false" />
```

Request `CAMERA` and `SYSTEM_ALERT_WINDOW` at runtime (API 23+).

---

## Camera2 Key Implementation Notes

```kotlin
// Enumerate cameras and identify left/right by pose translation
val cameraManager = getSystemService(CAMERA_SERVICE) as CameraManager
val stereoIds = cameraManager.cameraIdList.filter { id ->
    val chars = cameraManager.getCameraCharacteristics(id)
    chars.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
}

// Sort by LENS_POSE_TRANSLATION x-axis value — negative x = left, positive x = right
val sorted = stereoIds.sortedBy { id ->
    cameraManager.getCameraCharacteristics(id)
        .get(CameraCharacteristics.LENS_POSE_TRANSLATION)?.get(0) ?: 0f
}
val leftCameraId = sorted.first()
val rightCameraId = sorted.last()

// Open both cameras and create a shared CaptureSession if the device supports
// multi-camera (logical camera or concurrent camera sessions)
// Use CameraDevice.createCaptureSession() for each, or
// CameraManager.openCamera() concurrently if CONCURRENT_CAMERA_MAX_SIZE allows

// Target resolution: query StreamConfigurationMap for largest shared size ≤ 1920×1080
val map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
val sizes = map?.getOutputSizes(SurfaceTexture::class.java)
val targetSize = sizes?.filter { it.width <= 1920 && it.height <= 1080 }
    ?.maxByOrNull { it.width * it.height }
```

---

## Concurrent Camera Sessions

Check if the device supports concurrent camera use:

```kotlin
val concurrentCameraIds = cameraManager.concurrentCameraIds
val supportsConcurrent = concurrentCameraIds.any { set ->
    set.containsAll(listOf(leftCameraId, rightCameraId))
}
// If not supported, fall back to LogicalMultiCamera or sequential open with retry
```

If concurrent sessions aren't supported, check for a **logical multi-camera** that encapsulates both physicals:

```kotlin
val logicalId = cameraManager.cameraIdList.firstOrNull { id ->
    val chars = cameraManager.getCameraCharacteristics(id)
    val physicals = chars.physicalCameraIds
    physicals.containsAll(listOf(leftCameraId, rightCameraId))
}
// Use PhysicalCaptureResultListener and addPhysicalCameraSettings() on CaptureRequest
```

---

## Display / Presentation Output

```kotlin
val displayManager = getSystemService(DISPLAY_SERVICE) as DisplayManager
val presentations = displayManager.displays.filter {
    it.flags and Display.FLAG_PRESENTATION != 0
}
// The DP-connected XREAL glasses will appear as a Presentation display
val glassesDisplay = presentations.firstOrNull()

if (glassesDisplay != null) {
    val presentation = StereoPresentation(this, glassesDisplay)
    presentation.show()
    // StereoPresentation hosts the GLSurfaceView with StereoRenderer
} else {
    // Fallback: render full-screen on main display (glasses mirror it)
    setContentView(stereoGLSurfaceView)
}
```

---

## Project Structure

```
app/
├── src/main/
│   ├── AndroidManifest.xml
│   ├── java/com/xreal/stereoview/
│   │   ├── MainActivity.kt              # Entry point, permission handling
│   │   ├── camera/
│   │   │   ├── CameraController.kt      # Opens both cameras, manages sessions
│   │   │   ├── FrameSynchronizer.kt     # Timestamp-based L/R frame alignment
│   │   │   └── CameraUtils.kt           # Resolution selection, characteristic queries
│   │   ├── render/
│   │   │   ├── StereoRenderer.kt        # GLSurfaceView.Renderer, SBS compositing
│   │   │   ├── StereoShader.kt          # GLSL program management
│   │   │   └── StereoPresentation.kt    # Presentation subclass for DP display
│   │   ├── ui/
│   │   │   ├── ControlsOverlay.kt       # Floating controls (convergence, depth, etc.)
│   │   │   └── SettingsViewModel.kt     # DataStore-backed settings
│   │   └── util/
│   │       └── DisplayUtils.kt          # Display detection helpers
│   └── res/
│       ├── layout/activity_main.xml
│       └── layout/overlay_controls.xml
└── build.gradle.kts
```

---

## Dependencies (build.gradle.kts)

```kotlin
dependencies {
    implementation("androidx.core:core-ktx:1.13.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    // Camera2 is part of AOSP — no extra dependency needed
    // OpenGL ES 2.0 is part of AOSP — no extra dependency needed
}
```

---

## Key Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Beam Pro may not support concurrent camera sessions | Probe `concurrentCameraIds`; fall back to logical multi-camera or sequential with retry |
| Physical camera IDs may not expose `LENS_POSE_TRANSLATION` | Fall back to index-based assignment (0 = left, 1 = right); add swap control so user can correct |
| DP display not detected as `FLAG_PRESENTATION` | Fall back to rendering full-screen on main display (it mirrors to glasses anyway) |
| Frame sync latency between two independent sessions | `FrameSynchronizer` drops pairs with >33ms delta; tunable threshold in settings |
| OpenGL texture distortion (aspect ratio) | Compute correct UV transform from camera intrinsics (`LENS_INTRINSIC_CALIBRATION`) |
| NebulaOS restrictions on overlay windows | Fallback: render controls as a semi-transparent bottom bar within the main activity |

---

## Stretch Goals (post-MVP)

- **SBS video recording** using `MediaCodec` + `MediaMuxer`, output as `.mp4` viewable in the Beam Pro's native gallery in 3D
- **Convergence auto-adjust** using disparity estimation (OpenCV via JNI or ML Kit depth API)
- **Zoom control** with pinch gesture on Beam Pro touchscreen
- **Color correction** matching between left and right cameras (white balance sync via `CaptureRequest.COLOR_CORRECTION_MODE`)
- **Latency HUD** overlay showing frame delta and render time (debug mode)

---

## Build & Sideload Instructions (for Claude Code)

1. `./gradlew assembleDebug`
2. `adb connect <BeamPro_IP>:5555` (enable wireless ADB in developer options)
3. `adb install app/build/outputs/apk/debug/app-debug.apk`
4. Launch via `adb shell am start -n com.xreal.stereoview/.MainActivity`
5. Connect Air glasses via USB-C before or after launch

---

## Notes for Claude Code

- Do not use CameraX — Camera2 API directly gives full control over concurrent sessions and physical camera targeting that CameraX abstracts away
- The XREAL SDK is not required for this app — the glasses are a DP monitor; no NRSDK calls needed
- NebulaOS is Android 14 so all standard Android APIs apply
- Keep the GLSurfaceView renderer on a dedicated GL thread (default behavior of `GLSurfaceView`)
- The convergence slider is the most important UX element — default to 0, range ±50px at render resolution
