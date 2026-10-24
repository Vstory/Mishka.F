package top.yukonga.mishka.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import top.yukonga.mishka.ui.component.blur.LocalBlurEnabled
import top.yukonga.mishka.ui.component.blur.LocalTopBarBlurStyle
import top.yukonga.miuix.kmp.theme.ColorSchemeMode
import top.yukonga.miuix.kmp.theme.LocalContentColor
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.ThemeColorSpec
import top.yukonga.miuix.kmp.theme.ThemeController
import top.yukonga.miuix.kmp.theme.platformDynamicColors

/**
 * App 统一主题容器：Miuix 配色 + App 级 CompositionLocal（深浅色、Monet、缩放密度、模糊开关）。
 *
 * 从 [top.yukonga.mishka.App] 里原样抽出来，给独立面板 Activity（[top.yukonga.mishka.PanelActivity]）
 * 复用同一份主题：面板是单独任务栈外的 Activity，缺了这层会掉回 Miuix 默认配色、顶栏与弹窗
 * 颜色跟主界面不一致。
 */
@Composable
fun MishkaTheme(
    themeConfig: ThemeConfig = ThemeConfig(),
    content: @Composable () -> Unit,
) {
    val colorSchemeMode = when {
        !themeConfig.useMonet && themeConfig.colorMode == 1 -> ColorSchemeMode.Light
        !themeConfig.useMonet && themeConfig.colorMode == 2 -> ColorSchemeMode.Dark
        !themeConfig.useMonet -> ColorSchemeMode.System
        themeConfig.colorMode == 1 -> ColorSchemeMode.MonetLight
        themeConfig.colorMode == 2 -> ColorSchemeMode.MonetDark
        else -> ColorSchemeMode.MonetSystem
    }
    val isDark = themeConfig.resolveIsDark(isSystemInDarkTheme())
    val systemSeedColor = if (themeConfig.useMonet && themeConfig.accentColor == ThemeAccentColor.Default) {
        platformDynamicColors(isDark).primary
    } else {
        null
    }
    val keyColor = when {
        !themeConfig.useMonet -> null
        themeConfig.accentColor == ThemeAccentColor.Default -> systemSeedColor
        else -> themeConfig.accentColor.seedColor
    }
    val controller = remember(themeConfig, colorSchemeMode, keyColor, isDark) {
        ThemeController(
            colorSchemeMode = colorSchemeMode,
            keyColor = keyColor,
            colorSpec = ThemeColorSpec.Spec2025,
            paletteStyle = themeConfig.paletteStyle,
        )
    }
    val colors = controller.currentColors()
    val themedColors = remember(colors, isDark, themeConfig.pureBlack) {
        if (themeConfig.useMonet && themeConfig.pureBlack && isDark) {
            colors.copy(
                background = Color.Black,
                surface = Color.Black,
            )
        } else {
            colors
        }
    }

    MiuixTheme(colors = themedColors) {
        val currentDensity = LocalDensity.current
        val appDensity = remember(currentDensity, themeConfig.densityScale) {
            Density(
                density = currentDensity.density * themeConfig.densityScale,
                fontScale = currentDensity.fontScale,
            )
        }
        CompositionLocalProvider(
            LocalAppDarkMode provides isDark,
            LocalAppMonetEnabled provides themeConfig.useMonet,
            LocalPlatformDensity provides currentDensity,
            LocalDensity provides appDensity,
            LocalBlurEnabled provides themeConfig.blurEnabled,
            LocalTopBarBlurStyle provides themeConfig.topBarBlurStyle,
            LocalContentColor provides MiuixTheme.colorScheme.onBackground,
        ) {
            content()
        }
    }
}
