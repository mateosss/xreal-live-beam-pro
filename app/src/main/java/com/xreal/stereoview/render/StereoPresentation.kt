package com.xreal.stereoview.render

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager

/** Shows the stereo view full-screen on the glasses while the phone screen keeps the controls. */
class StereoPresentation(
    context: Context,
    display: Display,
    private val content: View,
) : Presentation(context, display) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(
            content,
            ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
        )
    }

    override fun onStart() {
        super.onStart()
        window?.insetsController?.hide(WindowInsets.Type.systemBars())
    }
}
