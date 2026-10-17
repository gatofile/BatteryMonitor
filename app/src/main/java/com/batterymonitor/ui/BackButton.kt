package com.batterymonitor.ui

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.batterymonitor.R

@Composable
fun BackButton(onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = null, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(8.dp))
        Text("Back")
    }
}
