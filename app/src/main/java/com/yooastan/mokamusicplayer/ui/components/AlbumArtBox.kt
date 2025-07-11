package com.yooastan.mokamusicplayer.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.yooastan.mokamusicplayer.R
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest


@Composable
fun AlbumArtBox(albumArt: ImageBitmap?) {
    if (albumArt != null) {
        Image(
            bitmap = albumArt,
            contentDescription = "Album Art",
            modifier = Modifier
                .size(220.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
        )
    } else {
        Image(
            painter = painterResource(id = R.drawable.placeholder_albumart),
            contentDescription = "Default Album Art",
            modifier = Modifier
                .size(220.dp)
                .border(2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(16.dp))
        )
    }
}
