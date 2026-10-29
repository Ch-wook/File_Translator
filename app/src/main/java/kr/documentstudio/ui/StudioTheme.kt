package kr.documentstudio.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private fun activity(context: Context): Activity?=when(context){is Activity->context;is ContextWrapper->activity(context.baseContext);else->null}

/** The shared native chrome follows the OS; document paper and saved formatting are untouched. */
@Composable
fun StudioTheme(accent: Color=Color(0xFF2858A5),content: @Composable ()->Unit) {
    val dark=isSystemInDarkTheme()
    val view=LocalView.current
    val scheme=if(dark)darkColorScheme(primary=lerp(accent,Color.White,.45f),secondary=Color(0xFFB8C8DF),
        background=Color(0xFF11151C),surface=Color(0xFF191E27),surfaceContainer=Color(0xFF242B36))
        else lightColorScheme(primary=accent,secondary=Color(0xFF596A82),background=Color(0xFFF3F5FA),surface=Color.White,surfaceContainer=Color(0xFFEEF1F7))
    SideEffect {activity(view.context)?.window?.let {window->
        WindowCompat.getInsetsController(window,view).apply {isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark}
    }}
    MaterialTheme(colorScheme=scheme,content=content)
}
