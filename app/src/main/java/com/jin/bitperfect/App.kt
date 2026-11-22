package com.jin.bitperfect

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // QNN HTP loads its DSP skeleton from ADSP_LIBRARY_PATH. Set this before
        // any ONNX Runtime/QNN library is loaded by the playback service.
        try {
            val nativeDir = applicationInfo.nativeLibraryDir
            android.system.Os.setenv("ADSP_LIBRARY_PATH", nativeDir, true)
            android.system.Os.setenv("LD_LIBRARY_PATH", nativeDir, true)
            AppLogger.i("DAU_BACKEND", "early ADSP_LIBRARY_PATH=$nativeDir")
        } catch (t: Throwable) {
            AppLogger.w("DAU_BACKEND", "early QNN path setup failed", t)
        }
    }
}
