package io.github.artcraftandroid.procraft.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * In-panel Gesture Guide page inside Actions -> Help, matching the visual structure of
 * [CursorPage] and [DrawingTabletPage].
 */
@Composable
internal fun GestureGuidePage(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    Row(
        Modifier.padding(start = 8.dp, top = 12.dp).height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(44.dp, 34.dp).clickable(onClick = onBack),
            contentAlignment = Alignment.Center,
        ) {
            Icon(PcIcons.ChevronLeft, contentDescription = "Back", tint = Pc.PanelTitle, modifier = Modifier.size(22.dp))
        }
        Text("Gesture guide", color = Pc.PanelTitle, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
    }
    Column(
        Modifier
            .heightIn(max = 760.dp)
            .verticalScroll(rememberScrollState())
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 14.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        for (section in GESTURE_GUIDE_SECTIONS) {
            Column(Modifier.fillMaxWidth()) {
                Text(
                    section.title,
                    color = Pc.PanelTitle,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 13.dp, bottom = 8.dp),
                )
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(13.dp))
                        .background(Pc.ActionsGroup),
                ) {
                    section.items.forEachIndexed { i, item ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pc.ActionsSeparator))
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 15.dp, vertical = 10.dp),
                        ) {
                            Text(
                                item.gesture,
                                color = Pc.Text,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                item.action,
                                color = Pc.PanelTitle,
                                fontSize = 13.sp,
                                lineHeight = 17.sp,
                            )
                        }
                    }
                }
            }
        }
    }
}
