package com.batterymonitor.data

import com.batterymonitor.domain.BatterySnapshot
import kotlinx.coroutines.flow.Flow

interface BatteryRepository {
    fun readings(): Flow<BatterySnapshot>
}
