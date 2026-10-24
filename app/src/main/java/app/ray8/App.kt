package app.ray8

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        SettingsStore.init(this)
        NodeStore.init(this)
    }
}
