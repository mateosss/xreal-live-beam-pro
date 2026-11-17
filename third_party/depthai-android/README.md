# depthai-core for Android (arm64-v8a)

Prebuilt by `tools/build_depthai_android.sh` from
[luxonis/depthai-core](https://github.com/luxonis/depthai-core) at the tag in `VERSION`
(MIT license), with its dependencies as Hunter fetches them, including the OAK device
firmware embedded in the library.

- `include/`: depthai-core's public headers; `include/dependencies/`: those of its
  dependencies (XLink, nlohmann/json, libnop, spdlog, …).
- The libraries are in `app/src/main/jniLibs/arm64-v8a/`: `libdepthai-core.so` (MIT) and
  `libusb-1.0.so` (Luxonis' libusb fork, LGPL-2.1, linked dynamically; its Android backend
  opens USB devices through Android's UsbManager).

Rebuild with `tools/build_depthai_android.sh` after changing the version there.
