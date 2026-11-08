package dev.marvinbarretto.jimbo

import android.service.notification.NotificationListenerService
import org.junit.Assert.assertEquals
import org.junit.Test

class RemovalReasonTest {
    @Test fun namesTheReasonsThatMatter() {
        assertEquals("opened", removalReasonName(NotificationListenerService.REASON_CLICK))
        assertEquals("dismissed", removalReasonName(NotificationListenerService.REASON_CANCEL))
        assertEquals("cleared_by_app", removalReasonName(NotificationListenerService.REASON_APP_CANCEL))
        assertEquals("cleared_by_app", removalReasonName(NotificationListenerService.REASON_APP_CANCEL_ALL))
        assertEquals("timed_out", removalReasonName(NotificationListenerService.REASON_TIMEOUT))
        assertEquals("other_4", removalReasonName(4))
    }
}
