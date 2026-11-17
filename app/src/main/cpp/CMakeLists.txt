# JNI glue between the app and depthai-core (Luxonis OAK cameras). depthai-core and its
# libusb are prebuilt for arm64-v8a by tools/build_depthai_android.sh into
# third_party/depthai-android (headers) and app/src/main/jniLibs (libraries).
cmake_minimum_required(VERSION 3.22)
project(oakjni CXX)

set(DAI ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/depthai-android)
set(LIBS ${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI})

add_library(depthai-core SHARED IMPORTED)
set_target_properties(depthai-core PROPERTIES IMPORTED_LOCATION ${LIBS}/libdepthai-core.so)
add_library(usb-1.0 SHARED IMPORTED)
set_target_properties(usb-1.0 PROPERTIES IMPORTED_LOCATION ${LIBS}/libusb-1.0.so)

add_library(oakjni SHARED oak_jni.cpp)
target_compile_features(oakjni PRIVATE cxx_std_17)
target_include_directories(oakjni PRIVATE ${DAI}/include ${DAI}/include/depthai-shared/3rdparty ${DAI}/include/dependencies)
target_link_libraries(oakjni depthai-core usb-1.0 log)
