package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color

private val InkingiDarkColorScheme = darkColorScheme(
    primary = InkingiCyan,
    onPrimary = InkingiNavyDark,
    primaryContainer = InkingiNavyCardElevated,
    onPrimaryContainer = InkingiCyan,
    secondary = InkingiBlue,
    onSecondary = Color.White,
    secondaryContainer = InkingiNavyCard,
    onSecondaryContainer = InkingiTextPrimary,
    tertiary = InkingiTeal,
    onTertiary = InkingiNavyDark,
    background = InkingiNavyDark,
    onBackground = InkingiTextPrimary,
    surface = InkingiNavyCard,
    onSurface = InkingiTextPrimary,
    surfaceVariant = InkingiNavyCardElevated,
    onSurfaceVariant = InkingiTextSecondary,
    outline = InkingiNavyBorder,
    error = InkingiRiskHigh,
    onError = Color.White
)

private val InkingiLightColorScheme = lightColorScheme(
    primary = Color(0xFF007A99),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC7F3FB),
    onPrimaryContainer = Color(0xFF002029),
    secondary = Color(0xFF1E5BB8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFD6E4FF),
    onSecondaryContainer = Color(0xFF001B3F),
    tertiary = Color(0xFF007A66),
    onTertiary = Color.White,
    background = Color(0xFFF4F6FB),
    onBackground = Color(0xFF0B132B),
    surface = Color.White,
    onSurface = Color(0xFF0B132B),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFFCBD5E1),
    error = Color(0xFFDC2626),
    onError = Color.White
)

@Composable
fun MyApplicationTheme(
    language: String = "fr",
    darkTheme: Boolean = true, // Default to sleek cybersecurity dark mode
    dynamicColor: Boolean = false, // Keep consistent high-contrast cybersecurity brand theme
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) InkingiDarkColorScheme else InkingiLightColorScheme
    val context = androidx.compose.ui.platform.LocalContext.current

    val locale = java.util.Locale(if (language.equals("en", ignoreCase = true)) "en" else "fr")
    val configuration = remember(language, context.resources.configuration) {
        android.content.res.Configuration(context.resources.configuration).apply {
            setLocale(locale)
        }
    }
    val localizedContext = remember(context, configuration) {
        context.createConfigurationContext(configuration)
    }

    val activityResultRegistryOwner = androidx.activity.compose.LocalActivityResultRegistryOwner.current
        ?: (context as? androidx.activity.result.ActivityResultRegistryOwner)

    val providers = mutableListOf<androidx.compose.runtime.ProvidedValue<*>>(
        androidx.compose.ui.platform.LocalConfiguration provides configuration,
        androidx.compose.ui.platform.LocalContext provides localizedContext
    )
    activityResultRegistryOwner?.let {
        providers.add(androidx.activity.compose.LocalActivityResultRegistryOwner provides it)
    }

    androidx.compose.runtime.CompositionLocalProvider(
        *providers.toTypedArray()
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}
