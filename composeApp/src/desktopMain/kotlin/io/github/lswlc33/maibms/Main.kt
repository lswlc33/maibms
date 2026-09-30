package io.github.lswlc33.maibms

import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import io.github.lswlc33.maibms.ui.App

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "麻衣 BMS",
        state = rememberWindowState(width = 420.dp, height = 860.dp)
    ) {
        App()
    }
}
