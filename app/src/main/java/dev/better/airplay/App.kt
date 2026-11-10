package dev.better.airplay

import android.app.Application
import android.util.Log
import dev.better.airplay.di.AppContainer

class App : Application() {

    /** The process-wide dependency graph; created once, shared by the service and every screen. */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        // Bring the receiver up whenever our process starts. Background FGS starts can be refused on
        // Android 12+; the boot receiver and the home screen retry.
        try { container.controller.ensureRunning() } catch (e: Exception) {
            Log.w("BetterAirPlay", "deferred service start: ${e.message}")
        }
    }
}
