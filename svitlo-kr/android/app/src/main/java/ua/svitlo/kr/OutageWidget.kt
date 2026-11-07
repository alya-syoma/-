package ua.svitlo.kr

import android.app.AlarmManager
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.view.View
import android.widget.RemoteViews
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/** Small 2x2 widget, like the one in «Київ Цифровий». */
class SmallWidget : BaseWidget() {
    override val layout = R.layout.widget_small
}

/** Wide 4x2 widget with a 24h bar. */
class MediumWidget : BaseWidget() {
    override val layout = R.layout.widget_medium
}

abstract class BaseWidget : AppWidgetProvider() {
    abstract val layout: Int

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        // Draw immediately from cache, then refresh from network in background.
        Widgets.renderAll(ctx)
        Widgets.schedule(ctx)
        Widgets.refreshNow(ctx)
    }

    override fun onEnabled(ctx: Context) = Widgets.schedule(ctx)
}

object Widgets {
    private const val PERIODIC = "svitlo-periodic"
    private const val ONCE = "svitlo-once"

    fun schedule(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun refreshNow(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }

    private fun ids(ctx: Context, cls: Class<*>): IntArray =
        AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, cls))

    /** Re-render every widget from cached data (cheap, no network). */
    fun renderAll(ctx: Context, data: OutageData? = DataRepo.cached(ctx), stale: Boolean = false) {
        val mgr = AppWidgetManager.getInstance(ctx)
        val prefs = Prefs(ctx)
        val now = System.currentTimeMillis()
        val status = if (data != null && prefs.group.isNotBlank()) computeStatus(data, prefs.group, now) else null
        for (id in ids(ctx, SmallWidget::class.java)) mgr.updateAppWidget(id, build(ctx, R.layout.widget_small, status, prefs, stale, now))
        for (id in ids(ctx, MediumWidget::class.java)) mgr.updateAppWidget(id, build(ctx, R.layout.widget_medium, status, prefs, stale, now))
        status?.until?.let { scheduleTick(ctx, it + 30_000) }
    }

    private fun build(ctx: Context, layout: Int, st: Status?, prefs: Prefs, stale: Boolean, now: Long): RemoteViews {
        val v = RemoteViews(ctx.packageName, layout)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        v.setOnClickPendingIntent(R.id.root, open)

        if (st == null) {
            v.setTextViewText(R.id.title, if (prefs.group.isBlank()) "Оберіть адресу" else "Завантаження…")
            v.setTextViewText(R.id.address, "Натисніть, щоб налаштувати")
            v.setTextViewText(R.id.group, "")
            v.setImageViewResource(R.id.icon, R.drawable.ic_bolt)
            v.setInt(R.id.icon, "setColorFilter", 0x99FFFFFF.toInt())
            if (layout == R.layout.widget_medium) {
                v.setViewVisibility(R.id.subtitle, View.GONE)
                v.setViewVisibility(R.id.bar, View.GONE)
                v.setViewVisibility(R.id.ticks, View.GONE)
            }
            return v
        }

        val off = st.state == Power.OFF
        v.setInt(R.id.root, "setBackgroundResource", if (off) R.drawable.widget_bg_off else R.drawable.widget_bg)
        v.setImageViewResource(R.id.icon, if (off) R.drawable.ic_bolt_off else R.drawable.ic_bolt)
        v.setInt(R.id.icon, "setColorFilter", if (st.state == Power.UNKNOWN) 0x99FFFFFF.toInt() else Colors.of(st.state))
        v.setTextViewText(R.id.title, st.title)
        v.setTextViewText(R.id.group, groupLabel(prefs.group).removePrefix("Черга "))
        v.setTextViewText(R.id.address, (prefs.address.ifBlank { groupLabel(prefs.group) }) + if (stale) " • офлайн" else "")

        if (layout == R.layout.widget_medium) {
            v.setViewVisibility(R.id.subtitle, if (st.subtitle.isBlank()) View.GONE else View.VISIBLE)
            v.setTextViewText(R.id.subtitle, st.subtitle)
            v.setViewVisibility(R.id.bar, View.VISIBLE)
            v.setViewVisibility(R.id.ticks, View.VISIBLE)
            v.setImageViewBitmap(R.id.bar, BarRenderer.render(st.days[0], now, 600, 16))
        }
        return v
    }

    /** Wake up at the next change (power on/off) so "до 20:00" switches on time. */
    private fun scheduleTick(ctx: Context, at: Long) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, TickReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        // Inexact alarm: needs no special permission, fires within a few minutes.
        am.set(AlarmManager.RTC, at, pi)
    }
}

class TickReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Widgets.renderAll(ctx)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        val r = DataRepo.load(applicationContext)
        Widgets.renderAll(applicationContext, r?.data, r?.stale ?: false)
        return Result.success()
    }
}
