// JNI glue for Luxonis OAK cameras (com.xreal.stereoview.oak.OakNative).
//
// One pipeline: the left and right mono cameras as raw GRAY8, optionally the color camera
// encoded to MJPEG on the device or as raw NV12, and the IMU (raw accelerometer + gyroscope).
// Each camera has its own control input (exposure is set per camera). Kotlin threads
// poll each output with a timeout. Timestamps are on the device's monotonic clock (the same
// for cameras and IMU); the host-synced timestamp (CLOCK_MONOTONIC on Android) is passed too.

#include <jni.h>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <memory>
#include <mutex>
#include <string>

#include "depthai/depthai.hpp"
#include "depthai/build/version.hpp"

#define TAG "OakJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)

namespace {

JavaVM* gVm = nullptr;

struct Session {
    std::unique_ptr<dai::Device> device;
    std::shared_ptr<dai::DataOutputQueue> left, right, rgb, imu;
    // Control inputs: left, right, color.
    std::shared_ptr<dai::DataInputQueue> control[3];
    int monoWidth = 0, monoHeight = 0, rgbWidth = 0, rgbHeight = 0;
    bool rgbRaw = false;
    std::string info;
};

int64_t ns(std::chrono::steady_clock::time_point t) {
    return std::chrono::duration_cast<std::chrono::nanoseconds>(t.time_since_epoch()).count();
}

void throwJava(JNIEnv* env, const std::string& message) {
    jclass c = env->FindClass("java/lang/IllegalStateException");
    env->ThrowNew(c, message.c_str());
}

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

dai::MonoCameraProperties::SensorResolution monoResolution(int height) {
    switch(height) {
        case 400: return dai::MonoCameraProperties::SensorResolution::THE_400_P;
        case 480: return dai::MonoCameraProperties::SensorResolution::THE_480_P;
        case 720: return dai::MonoCameraProperties::SensorResolution::THE_720_P;
        default: return dai::MonoCameraProperties::SensorResolution::THE_800_P;
    }
}

const char* socketName(dai::CameraBoardSocket s) {
    switch(s) {
        case dai::CameraBoardSocket::CAM_A: return "CAM_A";
        case dai::CameraBoardSocket::CAM_B: return "CAM_B";
        case dai::CameraBoardSocket::CAM_C: return "CAM_C";
        case dai::CameraBoardSocket::CAM_D: return "CAM_D";
        default: return "OTHER";
    }
}

/** Intrinsics, distortion and pose of one camera, at the streamed size; translations in meters. */
nlohmann::json cameraCalibration(const dai::CalibrationHandler& cal, dai::CameraBoardSocket socket, int w, int h) {
    nlohmann::json j;
    j["socket"] = socketName(socket);
    j["width"] = w;
    j["height"] = h;
    try {
        j["intrinsics"] = cal.getCameraIntrinsics(socket, w, h);
        j["distortion"] = cal.getDistortionCoefficients(socket);
        auto model = cal.getDistortionModel(socket);
        j["model"] = model == dai::CameraModel::Fisheye ? "fisheye" : model == dai::CameraModel::Perspective ? "perspective" : "other";
        // Camera → IMU: x_imu = R x_cam + t (depthai uses centimeters).
        auto m = cal.getCameraToImuExtrinsics(socket, false);
        for(int r = 0; r < 3; r++) m[r][3] /= 100.0f;
        j["imu_from_camera"] = m;
        auto spec = cal.getCameraToImuExtrinsics(socket, true);
        for(int r = 0; r < 3; r++) spec[r][3] /= 100.0f;
        j["imu_from_camera_spec"] = spec;
    } catch(const std::exception& e) {
        j["error"] = e.what();
    }
    return j;
}

}  // namespace

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gVm = vm;
    return JNI_VERSION_1_6;
}

/** Initializes depthai with the JavaVM (its libusb opens devices through UsbManager). */
JNIEXPORT jboolean JNICALL Java_com_xreal_stereoview_oak_OakNative_initialize(JNIEnv*, jclass) {
    try {
        return dai::initialize(gVm) ? JNI_TRUE : JNI_FALSE;
    } catch(const std::exception& e) {
        LOGW("initialize: %s", e.what());
        return JNI_FALSE;
    }
}

/**
 * Builds the pipeline and boots the first OAK found. Blocking (seconds: firmware upload,
 * re-enumeration). config: mono height, fps, rgb (0/1), rgb height, jpeg quality, imu rate
 * (0 = off), auto exposure (0/1), exposure µs, iso, exposure compensation, usb3 (0/1),
 * host queue frames per camera, host queue IMU packets, rgb sensor fps (0 = mono fps), keep every
 * nth mono frame, keep every nth rgb frame, accelerometer rate (0 = the imu rate, which is then
 * the gyroscope's), raw color (0 = MJPEG on the camera, 1 = NV12), then auto exposure, exposure µs,
 * iso, compensation for the right camera and for the color camera (the four after the imu rate
 * are the left camera's).
 * Returns a handle; throws IllegalStateException on failure.
 */
JNIEXPORT jlong JNICALL Java_com_xreal_stereoview_oak_OakNative_start(JNIEnv* env, jclass, jintArray configArray) {
    jint c[26] = {};
    env->GetIntArrayRegion(configArray, 0, std::min<jsize>(26, env->GetArrayLength(configArray)), c);
    const int monoHeight = c[0], fps = c[1], withRgb = c[2], rgbHeight = c[3], quality = c[4], imuRate = c[5];
    const int usb3 = c[10];
    const bool rgbRaw = c[17] != 0;
    // Initial exposure per camera (left, right, color): auto, µs, iso, compensation.
    struct Exposure { bool autoExposure; int us, iso, compensation; };
    const Exposure exposure[3] = {
        {c[6] != 0, c[7], c[8], c[9]},
        {c[18] != 0, c[19], c[20], c[21]},
        {c[22] != 0, c[23], c[24], c[25]},
    };
    auto initial = [&exposure](dai::CameraControl& control, int camera) {
        auto& e = exposure[camera];
        if(!e.autoExposure) control.setManualExposure(static_cast<uint32_t>(e.us), static_cast<uint32_t>(e.iso));
        else if(e.compensation != 0) control.setAutoExposureCompensation(e.compensation);
    };
    // Host-side queue sizes (Settings → Recording buffers): frames per camera, IMU packets.
    const unsigned linkFrames = static_cast<unsigned>(std::max(8, c[11])), imuPackets = static_cast<unsigned>(std::max(50, c[12]));
    const int rgbFps = c[13] > 0 ? c[13] : fps, monoEvery = std::max(1, c[14]), rgbEvery = std::max(1, c[15]);
    const int accelRate = c[16] > 0 ? c[16] : imuRate;
    try {
        auto s = std::make_unique<Session>();
        dai::Pipeline p;

        // Keeps every nth frame of a stream, by sequence number (left and right share them, so
        // pairs stay whole), on the camera: the sensors keep one rate and stay synchronized.
        auto decimated = [&p](dai::Node::Output& out, int every) -> dai::Node::Output& {
            if(every <= 1) return out;
            auto script = p.create<dai::node::Script>();
            script->setProcessor(dai::ProcessorType::LEON_CSS);
            script->setScript(
                "every = " + std::to_string(every) + "\n"
                "while True:\n"
                "    f = node.io['in'].get()\n"
                "    if f.getSequenceNum() % every == 0:\n"
                "        node.io['out'].send(f)\n");
            script->inputs["in"].setBlocking(false);
            script->inputs["in"].setQueueSize(4);
            out.link(script->inputs["in"]);
            return script->outputs["out"];
        };

        int camera = 0;
        for(auto [socket, name] : {std::pair{dai::CameraBoardSocket::CAM_B, "left"}, std::pair{dai::CameraBoardSocket::CAM_C, "right"}}) {
            auto mono = p.create<dai::node::MonoCamera>();
            mono->setBoardSocket(socket);
            mono->setResolution(monoResolution(monoHeight));
            mono->setFps(static_cast<float>(fps));
            initial(mono->initialControl, camera);
            auto control = p.create<dai::node::XLinkIn>();
            control->setStreamName(std::string(name) + "Control");
            control->out.link(mono->inputControl);
            camera++;
            auto out = p.create<dai::node::XLinkOut>();
            out->setStreamName(name);
            decimated(mono->out, monoEvery).link(out->input);
            auto [w, h] = mono->getResolutionSize();
            s->monoWidth = w;
            s->monoHeight = h;
        }
        if(withRgb) {
            auto cam = p.create<dai::node::ColorCamera>();
            cam->setBoardSocket(dai::CameraBoardSocket::CAM_A);
            cam->setResolution(rgbHeight == 720 ? dai::ColorCameraProperties::SensorResolution::THE_720_P
                                                : dai::ColorCameraProperties::SensorResolution::THE_800_P);
            cam->setFps(static_cast<float>(rgbFps));
            s->rgbWidth = 1280;
            s->rgbHeight = rgbHeight == 720 ? 720 : 800;
            cam->setVideoSize(s->rgbWidth, s->rgbHeight);
            initial(cam->initialControl, 2);
            auto control = p.create<dai::node::XLinkIn>();
            control->setStreamName("rgbControl");
            control->out.link(cam->inputControl);
            auto out = p.create<dai::node::XLinkOut>();
            out->setStreamName("rgb");
            if(rgbRaw) {
                // NV12 frames as the ISP makes them (1.5 bytes per pixel over USB).
                decimated(cam->video, rgbEvery).link(out->input);
            } else {
                auto encoder = p.create<dai::node::VideoEncoder>();
                encoder->setDefaultProfilePreset(static_cast<float>(rgbFps) / rgbEvery, dai::VideoEncoderProperties::Profile::MJPEG);
                encoder->setQuality(quality);
                decimated(cam->video, rgbEvery).link(encoder->input);
                encoder->bitstream.link(out->input);
            }
        }
        if(imuRate > 0) {
            auto imu = p.create<dai::node::IMU>();
            // Each sensor at its own rate; every packet carries the latest sample of both (the
            // app writes each sample once, by timestamp).
            imu->enableIMUSensor(dai::IMUSensor::ACCELEROMETER_RAW, accelRate);
            imu->enableIMUSensor(dai::IMUSensor::GYROSCOPE_RAW, imuRate);
            imu->setBatchReportThreshold(1);
            imu->setMaxBatchReports(20);
            auto out = p.create<dai::node::XLinkOut>();
            out->setStreamName("imu");
            imu->out.link(out->input);
        }

        LOGI("Booting OAK: mono %dp, %d fps (every %d), rgb %d (%s) at %d fps (every %d), gyro %d Hz, accel %d Hz",
             monoHeight, fps, monoEvery, withRgb, rgbRaw ? "NV12" : "MJPEG", rgbFps, rgbEvery, imuRate, accelRate);
        s->rgbRaw = rgbRaw;
        s->device = std::make_unique<dai::Device>(p, usb3 ? dai::UsbSpeed::SUPER : dai::UsbSpeed::HIGH);
        auto& d = *s->device;
        // Non-blocking queues: if the app falls behind, the oldest frames are dropped on the
        // host side, never stalling the device (the drops show as sequence gaps).
        s->left = d.getOutputQueue("left", linkFrames, false);
        s->right = d.getOutputQueue("right", linkFrames, false);
        if(withRgb) s->rgb = d.getOutputQueue("rgb", linkFrames, false);
        if(imuRate > 0) s->imu = d.getOutputQueue("imu", imuPackets, false);
        s->control[0] = d.getInputQueue("leftControl");
        s->control[1] = d.getInputQueue("rightControl");
        if(withRgb) s->control[2] = d.getInputQueue("rgbControl");

        nlohmann::json info;
        info["mxid"] = d.getMxId();
        try { info["device_name"] = d.getDeviceName(); } catch(...) {}
        try { info["product_name"] = d.getProductName(); } catch(...) {}
        auto speed = d.getUsbSpeed();
        info["usb_speed"] = speed == dai::UsbSpeed::SUPER || speed == dai::UsbSpeed::SUPER_PLUS ? "USB3" : speed == dai::UsbSpeed::HIGH ? "USB2" : "other";
        try { info["imu_type"] = d.getConnectedIMU(); } catch(...) {}
        nlohmann::json cams = nlohmann::json::array();
        for(auto socket : d.getConnectedCameras()) cams.push_back(socketName(socket));
        info["connected_cameras"] = cams;
        info["mono_size"] = {s->monoWidth, s->monoHeight};
        info["mono_sensor_fps"] = fps;
        info["mono_keep_every"] = monoEvery;
        if(withRgb) {
            info["rgb_format"] = rgbRaw ? "NV12" : "MJPEG";
            info["rgb_sensor_fps"] = rgbFps;
            info["rgb_keep_every"] = rgbEvery;
        }
        if(imuRate > 0) {
            info["imu_gyroscope_requested_hz"] = imuRate;
            info["imu_accelerometer_requested_hz"] = accelRate;
        }
        if(withRgb) info["rgb_size"] = {s->rgbWidth, s->rgbHeight};
        info["depthai_version"] = dai::build::VERSION;
        try {
            auto cal = d.readCalibration();
            nlohmann::json cj;
            cj["left"] = cameraCalibration(cal, dai::CameraBoardSocket::CAM_B, s->monoWidth, s->monoHeight);
            cj["right"] = cameraCalibration(cal, dai::CameraBoardSocket::CAM_C, s->monoWidth, s->monoHeight);
            if(withRgb) cj["rgb"] = cameraCalibration(cal, dai::CameraBoardSocket::CAM_A, s->rgbWidth, s->rgbHeight);
            cj["eeprom"] = cal.eepromToJson();
            info["calibration"] = cj;
        } catch(const std::exception& e) {
            info["calibration_error"] = e.what();
        }
        s->info = info.dump();
        return reinterpret_cast<jlong>(s.release());
    } catch(const std::exception& e) {
        LOGW("start failed: %s", e.what());
        throwJava(env, e.what());
        return 0;
    }
}

JNIEXPORT jstring JNICALL Java_com_xreal_stereoview_oak_OakNative_info(JNIEnv* env, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    return env->NewStringUTF(s ? s->info.c_str() : "{}");
}

/**
 * Next mono frame of camera [which] (0 left, 1 right) into [out] (width × height bytes).
 * meta: device timestamp mid-exposure, host timestamp, sequence, width, height, exposure µs,
 * iso, device timestamp at start of exposure. Returns bytes copied, 0 on timeout, −1 if closed.
 */
JNIEXPORT jint JNICALL Java_com_xreal_stereoview_oak_OakNative_pollMono(
    JNIEnv* env, jclass, jlong handle, jint which, jbyteArray out, jlongArray meta, jint timeoutMs) {
    auto* s = reinterpret_cast<Session*>(handle);
    if(!s) return -1;
    auto& q = which == 0 ? s->left : s->right;
    try {
        bool timedOut = false;
        auto frame = q->get<dai::ImgFrame>(std::chrono::milliseconds(timeoutMs), timedOut);
        if(timedOut || !frame) return 0;
        auto& data = frame->getData();
        jsize n = std::min<jsize>(static_cast<jsize>(data.size()), env->GetArrayLength(out));
        env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(data.data()));
        jlong m[8] = {
            ns(frame->getTimestampDevice(dai::CameraExposureOffset::MIDDLE)),
            ns(frame->getTimestamp(dai::CameraExposureOffset::MIDDLE)),
            frame->getSequenceNum(),
            static_cast<jlong>(frame->getWidth()),
            static_cast<jlong>(frame->getHeight()),
            static_cast<jlong>(std::chrono::duration_cast<std::chrono::microseconds>(frame->getExposureTime()).count()),
            frame->getSensitivity(),
            ns(frame->getTimestampDevice(dai::CameraExposureOffset::START)),
        };
        env->SetLongArrayRegion(meta, 0, 8, m);
        return n;
    } catch(const std::exception& e) {
        LOGW("pollMono: %s", e.what());
        return -1;
    }
}

/**
 * Next color frame (a JPEG, or NV12 with the Y plane rows packed at the frame's width); meta as
 * [pollMono]. Returns its size (−size − 1 if [out] is too small).
 */
JNIEXPORT jint JNICALL Java_com_xreal_stereoview_oak_OakNative_pollRgb(
    JNIEnv* env, jclass, jlong handle, jbyteArray out, jlongArray meta, jint timeoutMs) {
    auto* s = reinterpret_cast<Session*>(handle);
    if(!s || !s->rgb) return -1;
    try {
        bool timedOut = false;
        auto frame = s->rgb->get<dai::ImgFrame>(std::chrono::milliseconds(timeoutMs), timedOut);
        if(timedOut || !frame) return 0;
        auto& data = frame->getData();
        jsize n = static_cast<jsize>(data.size());
        const int w = static_cast<int>(frame->getWidth()), h = static_cast<int>(frame->getHeight());
        if(s->rgbRaw) {
            // NV12, packed to the frame's width: rows may come padded to a larger stride
            // (inferred from the size; depthai doesn't expose it).
            const jsize packed = w * h * 3 / 2;
            if(packed > env->GetArrayLength(out)) return -packed - 1;
            const size_t stride = h > 0 ? data.size() * 2 / (3 * static_cast<size_t>(h)) : 0;
            if(static_cast<jsize>(data.size()) < packed) return 0;
            if(stride > static_cast<size_t>(w) && stride * h * 3 / 2 == data.size()) {
                for(int r = 0; r < h * 3 / 2; r++) {
                    env->SetByteArrayRegion(out, r * w, w, reinterpret_cast<const jbyte*>(data.data() + r * stride));
                }
            } else {
                env->SetByteArrayRegion(out, 0, packed, reinterpret_cast<const jbyte*>(data.data()));
            }
            n = packed;
        } else {
            if(n > env->GetArrayLength(out)) return -n - 1;
            env->SetByteArrayRegion(out, 0, n, reinterpret_cast<const jbyte*>(data.data()));
        }
        jlong m[8] = {
            ns(frame->getTimestampDevice(dai::CameraExposureOffset::MIDDLE)),
            ns(frame->getTimestamp(dai::CameraExposureOffset::MIDDLE)),
            frame->getSequenceNum(),
            s->rgbRaw ? w : s->rgbWidth,
            s->rgbRaw ? h : s->rgbHeight,
            static_cast<jlong>(std::chrono::duration_cast<std::chrono::microseconds>(frame->getExposureTime()).count()),
            frame->getSensitivity(),
            ns(frame->getTimestampDevice(dai::CameraExposureOffset::START)),
        };
        env->SetLongArrayRegion(meta, 0, 8, m);
        return n;
    } catch(const std::exception& e) {
        LOGW("pollRgb: %s", e.what());
        return -1;
    }
}

/**
 * Next IMU packets into [out], 10 doubles each: accel device ns, ax, ay, az (m/s²),
 * gyro device ns, gx, gy, gz (rad/s), accel host ns, gyro host ns. Returns the packet count.
 */
JNIEXPORT jint JNICALL Java_com_xreal_stereoview_oak_OakNative_pollImu(
    JNIEnv* env, jclass, jlong handle, jdoubleArray out, jint timeoutMs) {
    auto* s = reinterpret_cast<Session*>(handle);
    if(!s || !s->imu) return -1;
    try {
        bool timedOut = false;
        auto data = s->imu->get<dai::IMUData>(std::chrono::milliseconds(timeoutMs), timedOut);
        if(timedOut || !data) return 0;
        jsize capacity = env->GetArrayLength(out) / 10;
        std::vector<jdouble> v;
        int count = 0;
        for(auto& p : data->packets) {
            if(count >= capacity) break;
            auto& a = p.acceleroMeter;
            auto& g = p.gyroscope;
            v.insert(v.end(), {static_cast<double>(ns(a.getTimestampDevice())), a.x, a.y, a.z,
                               static_cast<double>(ns(g.getTimestampDevice())), g.x, g.y, g.z,
                               static_cast<double>(ns(a.getTimestamp())), static_cast<double>(ns(g.getTimestamp()))});
            count++;
        }
        env->SetDoubleArrayRegion(out, 0, static_cast<jsize>(v.size()), v.data());
        return count;
    } catch(const std::exception& e) {
        LOGW("pollImu: %s", e.what());
        return -1;
    }
}

/** Auto exposure (with compensation −9..9) or manual exposure time and ISO, for one camera (0 left, 1 right, 2 color). */
JNIEXPORT void JNICALL Java_com_xreal_stereoview_oak_OakNative_setExposure(
    JNIEnv*, jclass, jlong handle, jint camera, jboolean autoExposure, jint exposureUs, jint iso, jint compensation) {
    auto* s = reinterpret_cast<Session*>(handle);
    if(!s || camera < 0 || camera > 2 || !s->control[camera]) return;
    try {
        dai::CameraControl c;
        if(autoExposure) {
            c.setAutoExposureEnable();
            c.setAutoExposureCompensation(compensation);
        } else {
            c.setManualExposure(static_cast<uint32_t>(exposureUs), static_cast<uint32_t>(iso));
        }
        s->control[camera]->send(std::make_shared<dai::CameraControl>(c));
    } catch(const std::exception& e) {
        LOGW("setExposure: %s", e.what());
    }
}

/** Closes the device (it reboots into its bootloader) and frees the session. */
JNIEXPORT void JNICALL Java_com_xreal_stereoview_oak_OakNative_stop(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if(!s) return;
    try {
        if(s->device) s->device->close();
    } catch(const std::exception& e) {
        LOGW("stop: %s", e.what());
    }
    delete s;
}

}  // extern "C"
