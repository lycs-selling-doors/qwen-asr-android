package dev.workspace.qwen.asr

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Immutable
internal data class QwenExtendedColors(
    val studioAccent: Color,
    val studioAccentSoft: Color,
    val liveCoral: Color,
    val liveCoralSoft: Color,
    val statusSuccess: Color,
    val statusSuccessSoft: Color,
    val statusWarning: Color,
    val statusWarningSoft: Color,
    val statusInfo: Color,
    val statusInfoSoft: Color,
    val terminalBg: Color,
    val terminalRow: Color,
    val terminalText: Color,
    val terminalMuted: Color,
    val heroStart: Color,
    val heroEnd: Color,
    val iconBlueBg: Color,
    val iconBlueFg: Color,
    val iconPurpleBg: Color,
    val iconPurpleFg: Color,
    val iconTealBg: Color,
    val iconTealFg: Color,
    val iconAmberBg: Color,
    val iconAmberFg: Color,
    val iconIndigoBg: Color,
    val iconIndigoFg: Color,
    val iconRoseBg: Color,
    val iconRoseFg: Color
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF007A5E),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFD1F7EC),
    onPrimaryContainer = Color(0xFF002117),
    secondary = Color(0xFF3A4E6C),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3ECF8),
    onSecondaryContainer = Color(0xFF102038),
    tertiary = Color(0xFF4F46E5),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFE5E7FF),
    onTertiaryContainer = Color(0xFF151259),
    error = Color(0xFFD92D20),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE4E2),
    onErrorContainer = Color(0xFF55100C),
    background = Color(0xFFF4F5F8),
    onBackground = Color(0xFF10131C),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF10131C),
    surfaceVariant = Color(0xFFEAECEF),
    onSurfaceVariant = Color(0xFF5A6275),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF8F9FB),
    surfaceContainer = Color(0xFFF0F2F6),
    surfaceContainerHigh = Color(0xFFE7EAF0),
    surfaceContainerHighest = Color(0xFFDDE1EA),
    outline = Color(0xFF8C94A8),
    outlineVariant = Color(0xFFE2E6EE)
)

private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF34D399),
    onPrimary = Color(0xFF002B1F),
    primaryContainer = Color(0xFF064E3B),
    onPrimaryContainer = Color(0xFFA7F3D0),
    secondary = Color(0xFF9FB4D6),
    onSecondary = Color(0xFF13243E),
    secondaryContainer = Color(0xFF1F2D45),
    onSecondaryContainer = Color(0xFFD6E4FA),
    tertiary = Color(0xFFA5B4FC),
    onTertiary = Color(0xFF1E1B5E),
    tertiaryContainer = Color(0xFF2D2B6E),
    onTertiaryContainer = Color(0xFFE0E7FF),
    error = Color(0xFFF97066),
    onError = Color(0xFF4B0905),
    errorContainer = Color(0xFF551612),
    onErrorContainer = Color(0xFFFEE4E2),
    background = Color(0xFF0B0D12),
    onBackground = Color(0xFFF1F4FA),
    surface = Color(0xFF131720),
    onSurface = Color(0xFFF1F4FA),
    surfaceVariant = Color(0xFF1E2432),
    onSurfaceVariant = Color(0xFF98A2B8),
    surfaceContainerLowest = Color(0xFF080A0E),
    surfaceContainerLow = Color(0xFF11151E),
    surfaceContainer = Color(0xFF171C28),
    surfaceContainerHigh = Color(0xFF1F2535),
    surfaceContainerHighest = Color(0xFF283044),
    outline = Color(0xFF6B758E),
    outlineVariant = Color(0xFF232A3B)
)

private val LightExtendedColors = QwenExtendedColors(
    studioAccent = Color(0xFF008F6D),
    studioAccentSoft = Color(0xFFE2F9F1),
    liveCoral = Color(0xFFE53935),
    liveCoralSoft = Color(0xFFFFEBEE),
    statusSuccess = Color(0xFF059669),
    statusSuccessSoft = Color(0xFFD1FAE5),
    statusWarning = Color(0xFFB45309),
    statusWarningSoft = Color(0xFFFEF3C7),
    statusInfo = Color(0xFF2563EB),
    statusInfoSoft = Color(0xFFDBEAFE),
    terminalBg = Color(0xFF0F131C),
    terminalRow = Color(0xFF161C29),
    terminalText = Color(0xFFE6ECF7),
    terminalMuted = Color(0xFF8B97B0),
    heroStart = Color(0xFF0B3B36),
    heroEnd = Color(0xFF132645),
    iconBlueBg = Color(0xFFE0ECFF),
    iconBlueFg = Color(0xFF1D4ED8),
    iconPurpleBg = Color(0xFFEDE9FE),
    iconPurpleFg = Color(0xFF6D28D9),
    iconTealBg = Color(0xFFCCFBF1),
    iconTealFg = Color(0xFF0F766E),
    iconAmberBg = Color(0xFFFEF3C7),
    iconAmberFg = Color(0xFFB45309),
    iconIndigoBg = Color(0xFFE0E7FF),
    iconIndigoFg = Color(0xFF4338CA),
    iconRoseBg = Color(0xFFFFE4E6),
    iconRoseFg = Color(0xFFBE123C)
)

private val DarkExtendedColors = QwenExtendedColors(
    studioAccent = Color(0xFF34D399),
    studioAccentSoft = Color(0xFF0B3228),
    liveCoral = Color(0xFFFF6B6B),
    liveCoralSoft = Color(0xFF3E1618),
    statusSuccess = Color(0xFF34D399),
    statusSuccessSoft = Color(0xFF093628),
    statusWarning = Color(0xFFFBBF24),
    statusWarningSoft = Color(0xFF3D2A09),
    statusInfo = Color(0xFF60A5FA),
    statusInfoSoft = Color(0xFF12284A),
    terminalBg = Color(0xFF080A10),
    terminalRow = Color(0xFF111521),
    terminalText = Color(0xFFE6ECF7),
    terminalMuted = Color(0xFF7D89A3),
    heroStart = Color(0xFF0A332D),
    heroEnd = Color(0xFF15203B),
    iconBlueBg = Color(0xFF14284D),
    iconBlueFg = Color(0xFF60A5FA),
    iconPurpleBg = Color(0xFF281D4D),
    iconPurpleFg = Color(0xFFA78BFA),
    iconTealBg = Color(0xFF0A332F),
    iconTealFg = Color(0xFF2DD4BF),
    iconAmberBg = Color(0xFF3D290A),
    iconAmberFg = Color(0xFFFBBF24),
    iconIndigoBg = Color(0xFF1E2252),
    iconIndigoFg = Color(0xFF818CF8),
    iconRoseBg = Color(0xFF421524),
    iconRoseFg = Color(0xFFFB7185)
)

internal val LocalQwenColors = staticCompositionLocalOf { LightExtendedColors }

internal val QwenTypography = Typography(
    headlineLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 28.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.5).sp
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Bold,
        fontSize = 24.sp,
        lineHeight = 31.sp,
        letterSpacing = (-0.3).sp
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp,
        lineHeight = 27.sp
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 18.sp,
        lineHeight = 25.sp
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 17.sp
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 12.sp,
        lineHeight = 16.sp
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 14.sp
    )
)

internal val QwenShapes = Shapes(
    extraSmall = RoundedCornerShape(10.dp),
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp)
)

@Composable
internal fun QwenDictationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors
    CompositionLocalProvider(LocalQwenColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = QwenTypography,
            shapes = QwenShapes,
            content = content
        )
    }
}
