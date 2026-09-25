package fi.callshift.app.ui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import fi.callshift.app.domain.ReplyChannel

object ReplyPermissions {
    fun missing(context: Context, channels: List<String>): Array<String> = buildList {
        if (ReplyChannel.SMS in channels) {
            add(Manifest.permission.SEND_SMS)
            add(Manifest.permission.READ_PHONE_STATE)
        }
        if (channels.any(ReplyChannel::isManual) && Build.VERSION.SDK_INT >= 33) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()
}
