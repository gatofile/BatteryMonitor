package com.batterymonitor.widgets

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.widget.RemoteViews
import com.batterymonitor.MainActivity
import com.batterymonitor.R
import com.batterymonitor.monitoring
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

class BatteryWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { refresh(context, ids) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, newOptions: Bundle) { refresh(context, intArrayOf(id)) }
    override fun onDeleted(context: Context, ids: IntArray) {
        ids.forEach { WidgetConfigStore(context).remove(it) }
        context.monitoring.widgets.instancesChanged()
    }
    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) {
        val store = WidgetConfigStore(context)
        val restored = oldWidgetIds.zip(newWidgetIds).filter { store.contains(it.first) && it.second > 0 }
            .map { (old, new) -> new to store.get(old) }
        oldWidgetIds.forEach(store::remove)
        restored.forEach { (id, config) -> store.save(id, config) }
        val manager = AppWidgetManager.getInstance(context)
        newWidgetIds.filter { validId(context, it) }.forEach { id -> manager.updateAppWidgetOptions(id,
            Bundle().apply { putBoolean(AppWidgetManager.OPTION_APPWIDGET_RESTORE_COMPLETED, true) }) }
        context.monitoring.widgets.instancesChanged()
        // AppWidgetProvider delivers onUpdate with the new IDs after onRestored.
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED)
            refresh(context, widgetIds(context))
    }
    private fun refresh(context: Context, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try { withTimeout(8_000) { context.monitoring.widgets.refresh(ids) } }
            catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { /* The launcher retains its previous view; the next recorder event retries. */ }
            finally { pending?.finish() }
        }
    }
    companion object {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        fun component(context: Context) = ComponentName(context, BatteryWidgetProvider::class.java)
        fun widgetIds(context: Context): IntArray = AppWidgetManager.getInstance(context).getAppWidgetIds(component(context))
        fun validId(context: Context, id: Int) = id > AppWidgetManager.INVALID_APPWIDGET_ID &&
            AppWidgetManager.getInstance(context).getAppWidgetInfo(id)?.provider == component(context)

        fun sizes(options: Bundle): List<WidgetSize> {
            if (Build.VERSION.SDK_INT >= 31) {
                @Suppress("DEPRECATION")
                val supplied = if (Build.VERSION.SDK_INT >= 33) options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
                    else options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES)
                if (!supplied.isNullOrEmpty()) return supplied.map { WidgetSize(it.width, it.height).valid() }.distinct().take(16)
            }
            val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 160).toFloat()
            val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 100).toFloat()
            val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, minW.toInt()).toFloat()
            val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, minH.toInt()).toFloat()
            return listOf(WidgetSize(minW, maxH).valid(), WidgetSize(maxW, minH).valid()).distinct()
        }

        fun remoteViews(context: Context, id: Int, config: WidgetConfig, data: WidgetData, options: Bundle): RemoteViews {
            val dimensions = sizes(options)
            // AppWidgetService caps the combined bitmap memory relative to the device's screen.
            val screen = context.resources.displayMetrics
            val totalPixels = (screen.widthPixels.toLong() * screen.heightPixels).coerceIn(16_384, 1_000_000).toInt()
            val budget = (totalPixels / dimensions.size).coerceAtLeast(1024)
            fun build(size: WidgetSize): RemoteViews {
                val rendered = WidgetRenderer.render(config, data, size, context.resources.displayMetrics.density, budget)
                return RemoteViews(context.packageName, R.layout.battery_widget).apply {
                    setImageViewBitmap(R.id.widget_image, rendered.bitmap)
                    setContentDescription(R.id.widget_image, rendered.description)
                    setViewVisibility(R.id.widget_edit, if (config.showSettings) android.view.View.VISIBLE else android.view.View.GONE)
                    setOnClickPendingIntent(R.id.widget_image, PendingIntent.getActivity(context, id,
                        Intent(context, MainActivity::class.java).setData(Uri.parse("batterymonitor://widget/open/$id")),
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                    setOnClickPendingIntent(R.id.widget_edit, PendingIntent.getActivity(context, id,
                        Intent(context, WidgetConfigurationActivity::class.java).setData(Uri.parse("batterymonitor://widget/edit/$id"))
                            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                }
            }
            return if (Build.VERSION.SDK_INT >= 31 && dimensions.size > 1)
                RemoteViews(dimensions.associate { SizeF(it.width, it.height) to build(it) })
            else if (dimensions.size > 1) RemoteViews(build(dimensions.last()), build(dimensions.first()))
            else build(dimensions.first())
        }
    }
}
