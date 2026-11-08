package dev.marvinbarretto.jimbo.walk

import android.content.Context
import android.util.Log
import dev.marvinbarretto.jimbo.data.StepsDatabase
import dev.marvinbarretto.jimbo.telemetry.RawEvent
import dev.marvinbarretto.jimbo.telemetry.WalkCollector
import dev.marvinbarretto.jimbo.telemetry.toEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** Walk events onto the telemetry queue, as GeofenceReceiver writes place events. */
class RoomWalkEventSink(context: Context) : WalkEventSink {
    private val database = StepsDatabase.getInstance(context.applicationContext)
    // Never cancelled: the walk.stop write must outlive the service that sent it.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun record(event: RawEvent) {
        scope.launch {
            try {
                // An unseeded setting row (before the first sync) means the collector's default: on.
                if (database.collectorSettingDao().isEnabled(WalkCollector.ID) == false) return@launch
                database.eventDao().insertAll(listOf(event.toEntity()))
                Log.d("JimboWalk", "Recorded ${event.type} ${event.payload?.get("spot") ?: ""}")
            } catch (e: Exception) {
                Log.e("JimboWalk", "Walk event ${event.type} not recorded", e)
            }
        }
    }
}
