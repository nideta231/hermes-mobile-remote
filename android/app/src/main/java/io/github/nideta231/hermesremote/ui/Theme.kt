package io.github.nideta231.hermesremote.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Gold = Color(0xFFE8B54A)
val Ok = Color(0xFF5BC27A)
val Bad = Color(0xFFE5675C)
val Warn = Color(0xFFE0A53A)

private val scheme = darkColorScheme(
    primary = Gold,
    onPrimary = Color(0xFF231A05),
    secondary = Color(0xFF9DB4D6),
    background = Color(0xFF121316),
    surface = Color(0xFF121316),
    surfaceVariant = Color(0xFF22252C),
    surfaceContainer = Color(0xFF1A1C21),
    surfaceContainerHigh = Color(0xFF22252C),
    surfaceContainerHighest = Color(0xFF2A2D35),
    onSurface = Color(0xFFE6E6E9),
    onSurfaceVariant = Color(0xFFA9ABB3),
    outline = Color(0xFF3A3E47),
    error = Bad,
)

@Composable
fun HermesTheme(content: @Composable () -> Unit) = MaterialTheme(colorScheme = scheme, content = content)
