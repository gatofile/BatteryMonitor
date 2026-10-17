package com.batterymonitor.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.os.PowerManager
import com.batterymonitor.domain.Availability
import com.batterymonitor.domain.BatteryConversions
import com.batterymonitor.domain.BatteryCurrentNormalizer
import com.batterymonitor.domain.BatteryHealth
import com.batterymonitor.domain.BatterySnapshot
import com.batterymonitor.domain.ChargeSource
import com.batterymonitor.domain.ChargeStatus
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull

class AndroidBatteryRepository(context: Context,
    private val interval: (BatterySnapshot) -> Long = { 1_000 }) : BatteryRepository {
    private val appContext = context.applicationContext
    private val batteryManager = appContext.getSystemService(BatteryManager::class.java)
    private val currentNormalizer = BatteryCurrentNormalizer()
    private val powerManager = appContext.getSystemService(PowerManager::class.java)
    private val requests = Channel<Unit>(Channel.CONFLATED)
    private val capacity by lazy { BatteryCapacityReader.read(appContext) }
    fun requestSample() { requests.trySend(Unit) }

    override fun readings(): Flow<BatterySnapshot> = callbackFlow {
        val latest = AtomicReference<Intent?>()
        val connection = AtomicBoolean(false)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == Intent.ACTION_BATTERY_CHANGED) {
                    val previous = latest.getAndSet(Intent(intent))
                    if (previous?.intOrNull(BatteryManager.EXTRA_PLUGGED) == 0 &&
                        (intent.intOrNull(BatteryManager.EXTRA_PLUGGED) ?: 0) > 0) connection.set(true)
                    if (previous != null && listOf(BatteryManager.EXTRA_PLUGGED, BatteryManager.EXTRA_STATUS,
                            BatteryManager.EXTRA_LEVEL).any { previous.intOrNull(it) != intent.intOrNull(it) }) requestSample()
                } else {
                    // BATTERY_CHANGED and POWER_CONNECTED describe the same connection.
                    // Don't generate a second usage reset after the sticky state updated.
                    if (intent.action == Intent.ACTION_POWER_CONNECTED &&
                        (latest.get()?.intOrNull(BatteryManager.EXTRA_PLUGGED) ?: 0) == 0) connection.set(true)
                    if (intent.action == Intent.ACTION_POWER_DISCONNECTED) connection.set(false)
                    requestSample()
                }
            }
        }
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED).apply {
            addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED)
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        }
        // This protected system broadcast needs no runtime permission.
        val sticky = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            appContext.registerReceiver(receiver, filter)
        }
        latest.compareAndSet(null, sticky)

        val poller = launch(Dispatchers.Default) {
            while (isActive) {
                val reading = readSnapshot(latest.get())
                val waitMillis = interval(reading).coerceAtLeast(1_000)
                val plugged = reading.source in listOf(ChargeSource.USB, ChargeSource.AC, ChargeSource.WIRELESS)
                send(reading.copy(expectedIntervalMillis = waitMillis,
                    powerConnectionObserved = plugged && connection.getAndSet(false)))
                // The foreground service optionally holds a CPU lock; this reader never wakes the device itself.
                withTimeoutOrNull(waitMillis) { requests.receive() }
            }
        }
        awaitClose {
            poller.cancel()
            appContext.unregisterReceiver(receiver)
        }
    }

    private fun readSnapshot(intent: Intent?): BatterySnapshot {
        if (intent == null) return BatterySnapshot(availability = Availability.UNAVAILABLE)
        if (intent.hasExtra(BatteryManager.EXTRA_PRESENT) &&
            !intent.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)
        ) return BatterySnapshot(availability = Availability.UNAVAILABLE)

        val status = when (intent.intOrNull(BatteryManager.EXTRA_STATUS)) {
            BatteryManager.BATTERY_STATUS_CHARGING -> ChargeStatus.CHARGING
            BatteryManager.BATTERY_STATUS_DISCHARGING -> ChargeStatus.DISCHARGING
            BatteryManager.BATTERY_STATUS_FULL -> ChargeStatus.FULL
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> ChargeStatus.NOT_CHARGING
            else -> ChargeStatus.UNKNOWN
        }
        val current = runCatching {
            batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull()
        val remaining = if (status == ChargeStatus.CHARGING) {
            runCatching { batteryManager?.computeChargeTimeRemaining() }.getOrNull()
        } else null

        return BatterySnapshot(
            timestampMillis = System.currentTimeMillis(),
            elapsedRealtimeMillis = SystemClock.elapsedRealtime(),
            uptimeMillis = SystemClock.uptimeMillis(),
            screenOn = powerManager?.isInteractive,
            availability = Availability.AVAILABLE,
            capacity = capacity,
            percentage = BatteryConversions.percentage(
                intent.intOrNull(BatteryManager.EXTRA_LEVEL),
                intent.intOrNull(BatteryManager.EXTRA_SCALE),
            ),
            temperatureCelsius = BatteryConversions.temperature(intent.intOrNull(BatteryManager.EXTRA_TEMPERATURE)),
            voltageVolts = BatteryConversions.voltage(intent.intOrNull(BatteryManager.EXTRA_VOLTAGE)),
            currentMilliAmps = currentNormalizer.current(
                current,
                status,
                unplugged = intent.intOrNull(BatteryManager.EXTRA_PLUGGED) == 0,
            ),
            status = status,
            source = when (intent.intOrNull(BatteryManager.EXTRA_PLUGGED)) {
                BatteryManager.BATTERY_PLUGGED_USB -> ChargeSource.USB
                BatteryManager.BATTERY_PLUGGED_AC -> ChargeSource.AC
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> ChargeSource.WIRELESS
                0 -> ChargeSource.NONE
                else -> ChargeSource.UNKNOWN
            },
            health = when (intent.intOrNull(BatteryManager.EXTRA_HEALTH)) {
                BatteryManager.BATTERY_HEALTH_GOOD -> BatteryHealth.GOOD
                BatteryManager.BATTERY_HEALTH_OVERHEAT -> BatteryHealth.OVERHEAT
                BatteryManager.BATTERY_HEALTH_DEAD -> BatteryHealth.DEAD
                BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> BatteryHealth.OVER_VOLTAGE
                BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> BatteryHealth.FAILURE
                BatteryManager.BATTERY_HEALTH_COLD -> BatteryHealth.COLD
                else -> BatteryHealth.UNKNOWN
            },
            technology = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY)?.trim()?.takeIf { it.isNotEmpty() },
            chargeTimeRemainingMillis = BatteryConversions.chargeTime(remaining, status),
            chargeCounterMah = runCatching {
                batteryManager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
                    ?.takeIf { it > 0 }?.div(1000.0)
            }.getOrNull(),
        )
    }

    private fun Intent.intOrNull(key: String): Int? =
        if (hasExtra(key)) getIntExtra(key, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE } else null
}
