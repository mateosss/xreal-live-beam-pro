package com.xreal.stereoview

import android.app.Application
import com.xreal.stereoview.util.CrashReporter

class StereoApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
