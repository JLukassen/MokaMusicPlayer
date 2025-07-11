package com.yooastan.mokamusicplayer.ui.components

import androidx.compose.foundation.text.ClickableText
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.sp

@Composable
fun FilePathBox(path: String) {
    ClickableText(
        text = AnnotatedString(path),
        onClick = { /* Future: open file details or location */ },
        style = TextStyle(fontSize = 12.sp)
    )
}
