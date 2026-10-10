package dev.marvinbarretto.jimbo.widgets

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import dev.marvinbarretto.jimbo.MainActivity

/** Opens the shell at [path] — MainActivity validates and navigates. */
internal fun openShell(context: Context, path: String): Action =
    actionStartActivity(
        Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            // Distinct data per path so PendingIntents don't collapse into one.
            data = android.net.Uri.parse("jimbo-widget://open$path")
            putExtra(WidgetLinks.EXTRA_PATH, path)
        },
    )

/** Today's kcal and protein against target, with a one-tap quick-add. */
class MacroWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStore.read(context)
        val stale = WidgetData.isStale(snapshot, System.currentTimeMillis())
        provideContent {
            GlanceTheme { Content(context, snapshot, stale) }
        }
    }

    @Composable
    private fun Content(context: Context, snapshot: WidgetSnapshot, stale: Boolean) {
        val macros = snapshot.macros
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(GlanceTheme.colors.widgetBackground)
                .padding(12.dp)
                .clickable(openShell(context, WidgetLinks.LOG)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (macros == null) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text("— kcal", style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface))
                    Text("Can't reach Jimbo", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                }
            } else {
                Image(
                    provider = ImageProvider(
                        Ring.render(
                            WidgetData.fraction(macros.kcal, WidgetData.KCAL_TARGET),
                            WidgetData.fraction(macros.proteinG, WidgetData.PROTEIN_TARGET_G),
                        ),
                    ),
                    contentDescription = "Calories and protein progress",
                    modifier = GlanceModifier.size(72.dp),
                )
                Spacer(GlanceModifier.width(12.dp))
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        "${macros.kcal} / ${WidgetData.KCAL_TARGET} kcal",
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onSurface),
                    )
                    Text(
                        "${macros.proteinG} / ${WidgetData.PROTEIN_TARGET_G} g protein",
                        style = TextStyle(fontSize = 13.sp, color = GlanceTheme.colors.onSurface),
                    )
                    if (stale) {
                        Text("Out of date", style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
                    }
                }
            }
            Text(
                "+",
                modifier = GlanceModifier
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .background(GlanceTheme.colors.primary)
                    .clickable(openShell(context, WidgetLinks.LOG_COMPOSER)),
                style = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GlanceTheme.colors.onPrimary),
            )
        }
    }
}

class MacroWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = MacroWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetRefreshWorker.schedule(context)
    }
}
