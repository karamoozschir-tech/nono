package ir.hamed.pricewidget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class PriceWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { mgr.updateAppWidget(it, build(context, mgr, it)) }
        schedule(context)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, mgr, id, newOptions)
        mgr.updateAppWidget(id, build(context, mgr, id))
    }

    override fun onEnabled(context: Context) {
        schedule(context)
        refreshNow(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_REFRESH -> refreshNow(context)
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> updateAll(context)
        }
    }

    companion object {
        const val ACTION_REFRESH = "ir.hamed.pricewidget.REFRESH"

        private val net = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        fun schedule(context: Context) {
            val req = PeriodicWorkRequestBuilder<UpdateWorker>(15, TimeUnit.MINUTES)
                .setConstraints(net)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork("periodic", ExistingPeriodicWorkPolicy.KEEP, req)
        }

        fun refreshNow(context: Context) {
            val req = OneTimeWorkRequestBuilder<UpdateWorker>().setConstraints(net).build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("now", ExistingWorkPolicy.REPLACE, req)
        }

        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, PriceWidgetProvider::class.java))
            ids.forEach { mgr.updateAppWidget(it, build(context, mgr, it)) }
        }

        fun build(context: Context, mgr: AppWidgetManager, id: Int): RemoteViews {
            val o = mgr.getAppWidgetOptions(id)
            val wDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 0).let { if (it < 100) 320 else it }
            val hDp = o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 0).let { if (it < 150) 560 else it }

            val rv = RemoteViews(context.packageName, R.layout.widget)
            rv.setImageViewBitmap(R.id.bg, Renderer.render(context, wDp, hDp))

            // اندازه‌ی ساعت زنده متناسب با اندازه‌ی ویجت
            val clockDp = minOf(wDp * 0.21f, hDp * 0.092f)
            rv.setTextViewTextSize(R.id.clock, TypedValue.COMPLEX_UNIT_DIP, clockDp)

            // لمس ویجت = بروزرسانی فوری
            val intent = Intent(context, PriceWidgetProvider::class.java).setAction(ACTION_REFRESH)
            val pi = PendingIntent.getBroadcast(
                context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            rv.setOnClickPendingIntent(R.id.root, pi)
            return rv
        }
    }
}
