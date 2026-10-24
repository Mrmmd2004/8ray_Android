package app.ray8

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

object LocaleHelper {
    fun wrap(base: Context): Context {
        val lang = base.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("language", null)
            ?: SettingsStore.defaultLanguage()
        val locale = Locale(lang)
        Locale.setDefault(locale)
        val cfg = Configuration(base.resources.configuration)
        cfg.setLocale(locale)
        cfg.setLayoutDirection(locale)
        return base.createConfigurationContext(cfg)
    }
}
