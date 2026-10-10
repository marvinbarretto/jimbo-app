package dev.marvinbarretto.jimbo.widgets

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle

/** The briefing's top priority; a tap lands on /m/today. */
class PriorityWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStore.read(context)
        provideContent {
            GlanceTheme { Content(context, snapshot) }
        }
    }

    @Composable
    private fun Content(context: Context, snapshot: WidgetSnapshot) {
        val unreachable = snapshot.fetchedAtMs == null
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp)
                .clickable(openShell(context, WidgetLinks.TODAY)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("TOP PRIORITY", style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
            Text(
                text = snapshot.priority ?: if (unreachable) "Can't reach Jimbo" else "Nothing set for today",
                maxLines = 3,
                style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = GlanceTheme.colors.onSurface),
            )
        }
    }
}

class PriorityWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PriorityWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
    }
}
