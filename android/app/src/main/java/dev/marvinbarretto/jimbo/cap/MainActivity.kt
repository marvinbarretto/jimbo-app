package dev.marvinbarretto.jimbo.cap

import android.os.Bundle
import com.getcapacitor.BridgeActivity

class MainActivity : BridgeActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // WorkManager handles its own scheduling persistence; UPDATE policy makes this
        // a cheap re-arm on every launch. BootReceiver also calls this after reboot.
        SyncScheduler.schedulePeriodic(this)
        // Kick a one-shot sync on launch so users (and verification builds) don't have
        // to wait for the periodic window to elapse.
        SyncScheduler.enqueueManualSync(this)
    }
}
