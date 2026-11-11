package com.muxlume.app.ui.main

import com.muxlume.app.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.muxlume.app.ui.components.Wordmark
import com.muxlume.app.ui.theme.Mux
import com.muxlume.app.ui.theme.MuxTheme

/** Screen title (or the wordmark) with search always one tap away. */
@Composable
fun TopHeader(title: String?, onSearch: (() -> Unit)?, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().windowInsetsPadding(WindowInsets.statusBars).padding(start = if (onBack != null) 8.dp else Mux.ScreenPadding, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.top_header_back), tint = MuxTheme.TextPrimary) }
        }
        if (title == null) {
            Wordmark(Modifier.weight(1f))
        } else {
            Text(title, style = MaterialTheme.typography.headlineMedium, color = MuxTheme.TextPrimary, modifier = Modifier.weight(1f).semantics { heading() })
        }
        if (onSearch != null) {
            IconButton(onSearch, Modifier.size(48.dp).clip(Mux.PillShape).background(MuxTheme.Raised)) {
                Icon(Icons.Rounded.Search, stringResource(R.string.top_header_search), tint = MuxTheme.TextPrimary)
            }
        }
    }
}
