package com.thrive.app

import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import com.thrive.app.data.local.SettingsStore
import com.thrive.app.update.ReEngagement
import com.thrive.app.update.UpdateNotifier
import com.thrive.app.update.UpdateScheduler
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

class ThriveApp : Application(), ImageLoaderFactory {

    lateinit var settings: SettingsStore
        private set

    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(getSharedPreferences("thrive_settings", Context.MODE_PRIVATE))
        UpdateNotifier.ensureChannel(this)
        UpdateScheduler.schedule(this)
        ReEngagement.schedule(this)
    }

    /**
     * Image loading with a real browser User-Agent. Recipe photos and product
     * photos are served by Wikimedia Commons / Open Food Facts, which reject
     * Coil's default `okhttp/...` User-Agent with HTTP 403 — every photo in the
     * app silently fell back to the branded tile. A desktop-browser UA keeps
     * the network policy honest (no auth, no API key) while letting the photos
     * actually load.
     */
    override fun newImageLoader(): ImageLoader {
        val client = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header(
                        "User-Agent",
                        "Mozilla/5.0 (Linux; Android 14; Thrive/1.4) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0 Mobile Safari/537.36",
                    )
                    .build()
                chain.proceed(request)
            }
            .build()
        return ImageLoader.Builder(this)
            .okHttpClient(client)
            .crossfade(true)
            .build()
    }

    /** Called whenever the app comes to the foreground — resets the idle timer. */
    fun markAppUsed() {
        ReEngagement.markAppUsed(settings)
    }
}
