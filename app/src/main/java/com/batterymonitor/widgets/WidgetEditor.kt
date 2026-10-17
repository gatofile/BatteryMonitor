package com.batterymonitor.widgets

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.batterymonitor.monitoring
import com.batterymonitor.ui.BackButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Locale

@Composable
fun WidgetEditorScreen(id: Int, initial: WidgetConfig, initialSize: WidgetSize, sampleKey: Long = 0,
    load: suspend (Int) -> WidgetData, saving: Boolean = false, error: String? = null,
    onCancel: () -> Unit, onSave: (WidgetConfig) -> Unit) {
    var encoded by rememberSaveable(id) { mutableStateOf(WidgetConfigJson.encode(initial)) }
    val config = remember(encoded) { WidgetConfigJson.decode(encoded) }
    fun update(value: WidgetConfig) { encoded = WidgetConfigJson.encode(value.normalized()) }
    var tab by rememberSaveable { mutableStateOf(0) }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf(false) }
    var replacing by remember { mutableStateOf<String?>(null) }
    var previewWidth by rememberSaveable { mutableStateOf(initialSize.width) }
    var previewHeight by rememberSaveable { mutableStateOf(initialSize.height) }
    var sizesOpen by rememberSaveable { mutableStateOf(false) }
    val currentLoad by rememberUpdatedState(load)
    val data by produceState(WidgetData(), sampleKey, config.graphMinutes) {
        value = try { currentLoad(config.graphMinutes) } catch (cancelled: kotlinx.coroutines.CancellationException) { throw cancelled }
            catch (_: Exception) { WidgetData() }
    }
    var rendering by remember { mutableStateOf<RenderedWidget?>(null) }
    LaunchedEffect(encoded, data, previewWidth, previewHeight) {
        delay(90)
        rendering = withContext(Dispatchers.Default) { WidgetRenderer.render(config, data, WidgetSize(previewWidth, previewHeight), 1.6f, 300_000) }
    }
    fun editElement(element: WidgetElement) = update(config.copy(elements = config.elements.map { if (it.id == element.id) element else it }))
    if (picking) MetricPicker(onDismiss = { picking = false }, onPick = { metric ->
        if (replacing != null) config.elements.firstOrNull { it.id == replacing }?.let { editElement(it.copy(metric = metric)) }
        else if (config.elements.size < 64) {
            val added = WidgetElement(metric = metric); update(config.copy(elements = config.elements + added)); selected = added.id
        }
        picking = false; replacing = null
    })
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onCancel) { Text("Cancel") }
                Text("Widget editor", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Button(onClick = { onSave(config) }, enabled = !saving) { Text(if (saving) "Saving…" else "Save") }
            }
            error?.let { Text(it, Modifier.padding(horizontal = 20.dp), color = Color(0xFFFFB66B)) }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Content", "Appearance", "Updates").forEachIndexed { index, title ->
                    FilterChip(selected = tab == index, onClick = { tab = index }, label = { Text(title) })
                }
            }
            LazyColumn(Modifier.testTag("widget-editor-list").weight(1f).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                item {
                    EditorCard("Live preview") {
                        Box(Modifier.fillMaxWidth().height((previewHeight / previewWidth * 270).coerceIn(90f, 250f).dp)
                            .background(Color(0xFF273139), RoundedCornerShape(16.dp)), contentAlignment = Alignment.Center) {
                            rendering?.let { Image(it.bitmap.asImageBitmap(), contentDescription = "Actual widget renderer preview",
                                modifier = Modifier.fillMaxSize().padding(8.dp), contentScale = ContentScale.Fit) }
                        }
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${previewWidth.toInt()} × ${previewHeight.toInt()} dp", Modifier.weight(1f), style = MaterialTheme.typography.labelMedium)
                            TextButton(onClick = { sizesOpen = !sizesOpen }) { Text(if (sizesOpen) "Hide sizes" else "Test sizes") }
                        }
                        val plan = rendering?.plan
                        if (plan != null && plan.hidden > 0) Text("${plan.hidden} selected items need more space.${if (plan.pinnedOverflow) " Some pinned items cannot fit; enlarge the widget or allow them to hide." else " Lower-priority items will return when resized."}",
                            style = MaterialTheme.typography.bodySmall, color = Color(0xFFFFB66B))
                        if (sizesOpen) {
                            TextButton(onClick = { previewWidth = initialSize.width; previewHeight = initialSize.height }) { Text("Use launcher size") }
                            for (height in 1..4) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                for (width in 1..4) FilterChip(previewWidth == width * 72f && previewHeight == height * 88f,
                                    onClick = { previewWidth = width * 72f; previewHeight = height * 88f }, label = { Text("$width×$height") })
                            }
                            Text("Grid buttons simulate 72×88 dp cells only. Your launcher supplies the real dimensions and resize limits.", style = MaterialTheme.typography.bodySmall)
                            EditorSlider("Preview width", previewWidth, 40f..600f, "dp") { previewWidth = it }
                            EditorSlider("Preview height", previewHeight, 40f..600f, "dp") { previewHeight = it }
                        }
                    }
                }
                if (tab == 0) {
                    item { EditorCard("Presets") {
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            WidgetConfig.presets.keys.forEach { name -> FilterChip(config.name == name, onClick = { update(WidgetConfig.preset(name)); selected = null }, label = { Text(name) }) }
                        }
                        Text("Presets are fully editable. First element stays the primary metric.", style = MaterialTheme.typography.bodySmall)
                        EnumChoices("Layout", WidgetLayout.entries, config.layout) { update(config.copy(layout = it)) }
                    } }
                    item { Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("Elements · ${config.elements.size}", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { replacing = null; picking = true }, enabled = config.elements.size < 64) { Text("Add metric") }
                    } }
                    items(config.elements, key = { it.id }) { element ->
                        EditorCard("${config.elements.indexOf(element) + 1}. ${element.metric.title}") {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                TextButton(onClick = { selected = if (selected == element.id) null else element.id }) { Text(if (selected == element.id) "Close" else "Customize") }
                                fun move(delta: Int) {
                                    val list = config.elements.toMutableList(); val index = list.indexOfFirst { it.id == element.id }; val target = index + delta
                                    if (target in list.indices) { list.removeAt(index); list.add(target, element); update(config.copy(elements = list)) }
                                }
                                IconButton(onClick = { move(-1) }, enabled = config.elements.first().id != element.id,
                                    modifier = Modifier.size(40.dp).semantics { contentDescription = "Move ${element.metric.title} up" }) { Text("↑") }
                                IconButton(onClick = { move(1) }, enabled = config.elements.last().id != element.id,
                                    modifier = Modifier.size(40.dp).semantics { contentDescription = "Move ${element.metric.title} down" }) { Text("↓") }
                                IconButton(onClick = { update(config.copy(elements = config.elements.filterNot { it.id == element.id })) }, enabled = config.elements.size > 1,
                                    modifier = Modifier.size(40.dp).semantics { contentDescription = "Remove ${element.metric.title}" }) { Text("×") }
                            }
                            if (selected == element.id) {
                                TextButton(onClick = { replacing = element.id; picking = true }) { Text("Change metric") }
                                EditorSlider("Metric font", element.valueSize, 10f..64f, "sp") { editElement(element.copy(valueSize = it)) }
                                EditorSlider("Label font", element.labelSize, 8f..24f, "sp") { editElement(element.copy(labelSize = it)) }
                                EditorToggle("Show label", element.labels) { editElement(element.copy(labels = it)) }
                                EditorToggle("Show icon", element.icons) { editElement(element.copy(icons = it)) }
                                EditorToggle("May hide at smaller sizes", element.mayHide) { editElement(element.copy(mayHide = it)) }
                                EditorSlider("Visibility priority", element.priority.toFloat(), 0f..100f, "") { editElement(element.copy(priority = it.toInt())) }
                                EditorToggle("Use default color", element.color == null) { editElement(element.copy(color = if (it) null else config.textColor)) }
                                if (element.color != null) ColorEditor("Metric color", element.color) { editElement(element.copy(color = it)) }
                                EditorToggle("Use default alignment", element.alignment == null) { editElement(element.copy(alignment = if (it) null else config.alignment)) }
                                element.alignment?.let { alignment -> EnumChoices("Alignment", WidgetAlignment.entries, alignment) { editElement(element.copy(alignment = it)) } }
                            }
                        }
                    }
                } else if (tab == 1) {
                    item { EditorCard("Surface") {
                        EditorToggle("Show settings button", config.showSettings) { update(config.copy(showSettings = it)) }
                        Text("You can still edit this widget from Settings → Home screen widgets in the app.", style = MaterialTheme.typography.bodySmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { update(config.copy(background = android.graphics.Color.BLACK, opacity = 1f)) }) { Text("Black") }
                            TextButton(onClick = { update(config.copy(background = 0xFF171D23.toInt(), opacity = 1f)) }) { Text("Charcoal") }
                            TextButton(onClick = { update(config.copy(opacity = 0f)) }) { Text("Transparent") }
                        }
                        ColorEditor("Background color", config.background) { update(config.copy(background = it)) }
                        EditorSlider("Opacity", config.opacity * 100, 0f..100f, "%") { update(config.copy(opacity = it / 100)) }
                        EditorSlider("Corner radius", config.corner, 0f..60f, "dp") { update(config.copy(corner = it)) }
                        EditorToggle("Border", config.border) { update(config.copy(border = it)) }
                        if (config.border) EditorSlider("Border width", config.borderWidth, .5f..4f, "dp") { update(config.copy(borderWidth = it)) }
                        EditorToggle("Subtle shadow", config.shadow) { update(config.copy(shadow = it)) }
                        Text("Launchers may apply their own corner clipping or background effects.", style = MaterialTheme.typography.bodySmall)
                    } }
                    item { EditorCard("Typography & spacing") {
                        ColorEditor("Default text color", config.textColor) { update(config.copy(textColor = it)) }
                        EditorToggle("Automatic blue / green accent", config.automaticAccent) { update(config.copy(automaticAccent = it)) }
                        ColorEditor("Custom accent", config.accent) { update(config.copy(accent = it, automaticAccent = false)) }
                        EnumChoices("Default alignment", WidgetAlignment.entries, config.alignment) { update(config.copy(alignment = it)) }
                        EditorSlider("Padding", config.padding, 0f..24f, "dp") { update(config.copy(padding = it)) }
                        EditorSlider("Spacing", config.spacing, 0f..24f, "dp") { update(config.copy(spacing = it)) }
                        EditorToggle("Detailed captions", config.detailed) { update(config.copy(detailed = it)) }
                        Text("Small sizes reduce fonts or hide optional labels. Metrics keep their identity; the overflow count explains hidden content.", style = MaterialTheme.typography.bodySmall)
                    } }
                    item { EditorCard("Graphs") {
                        EditorToggle("Use accent color", config.graphColor == null) { update(config.copy(graphColor = if (it) null else config.accent)) }
                        config.graphColor?.let { ColorEditor("Graph color", it) { color -> update(config.copy(graphColor = color)) } }
                        GraphDuration(config.graphMinutes) { update(config.copy(graphMinutes = it)) }
                    } }
                } else {
                    item { EditorCard("Refresh preference") {
                        EnumChoices("Updates", WidgetCadence.entries, config.cadence, title = { it.title }) { update(config.copy(cadence = it)) }
                        Text("While the existing recorder delivers readings: Battery saver ≈5 min, Balanced ≈1 min, Frequent ≈15 s. State changes can update sooner. Widgets never start a second polling service or wake the phone every second.", style = MaterialTheme.typography.bodySmall)
                        Text("Android and your launcher can delay updates. Stale data is marked; enable background monitoring in app Settings for updates while closed. Force-stop can prevent all updates until you reopen the app.", style = MaterialTheme.typography.bodySmall)
                        EditorToggle("Show last measurement time", config.showUpdated) { update(config.copy(showUpdated = it)) }
                        Text("Tiny sizes may show only the stale-status dot and overflow count. The optional settings button reopens this editor.", style = MaterialTheme.typography.bodySmall)
                    } }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }
}

@Composable private fun MetricPicker(onDismiss: () -> Unit, onPick: (WidgetMetric) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("Choose a metric") }, text = {
        LazyColumn(Modifier.heightIn(max = 440.dp)) {
            WidgetMetric.entries.groupBy { it.group }.forEach { (group, metrics) ->
                item { Text(group, Modifier.padding(vertical = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(metrics) { metric -> Text(metric.title, Modifier.fillMaxWidth().clickable { onPick(metric) }.padding(vertical = 13.dp)) }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("Cancel") } })
}

@Composable internal fun EditorCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium); content()
        }
    }
}
@Composable private fun EditorSlider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, unit: String, onChange: (Float) -> Unit) {
    Text("$label · ${String.format(Locale.getDefault(), if (range.endInclusive <= 4) "%.1f" else "%.0f", value)} $unit", style = MaterialTheme.typography.labelLarge)
    Slider(value.coerceIn(range.start, range.endInclusive), onValueChange = onChange, valueRange = range)
}
@Composable private fun EditorToggle(label: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Switch(value, onChange, Modifier.testTag("widget-toggle-$label")) }
}
@Composable private fun <T> EnumChoices(label: String, choices: List<T>, selected: T, title: (T) -> String = { it.toString().lowercase().replaceFirstChar { c -> c.titlecase() } }, onChange: (T) -> Unit) {
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        choices.forEach { FilterChip(it == selected, onClick = { onChange(it) }, label = { Text(title(it)) }) }
    }
}
@Composable private fun GraphDuration(minutes: Int, onChange: (Int) -> Unit) {
    EnumChoices("Graph history", listOf(15, 30, 60, 360, 1440), minutes, title = { if (it < 60) "${it}m" else "${it / 60}h" }, onChange = onChange)
}
@Composable private fun ColorEditor(label: String, value: Int, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(String.format(Locale.ROOT, "#%06X", value and 0xFFFFFF)) }
    Text(label, style = MaterialTheme.typography.labelLarge)
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(0xFFFFFFFF, 0xFF80B8FF, 0xFF78DFB0, 0xFFFFB66B, 0xFFFF7D88, 0xFFBBA0FF, 0xFF75E3EB, 0xFF000000).forEach { color ->
            Box(Modifier.size(30.dp).background(Color(color.toInt()), RoundedCornerShape(9.dp)).clickable { onChange(color.toInt()) }
                .semantics { contentDescription = "Color ${color.toString(16)}" })
        }
    }
    OutlinedTextField(text, onValueChange = { raw ->
        text = raw.take(7)
        if (text.matches(Regex("#[0-9a-fA-F]{6}"))) onChange(android.graphics.Color.parseColor(text))
    }, label = { Text("Hex color · #RRGGBB") }, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable fun WidgetGalleryScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var ids by remember { mutableStateOf(BatteryWidgetProvider.widgetIds(context).toList()) }
    var revision by remember { mutableStateOf(0) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) {
            ids = BatteryWidgetProvider.widgetIds(context).toList(); revision++
        } }
        lifecycle.addObserver(observer); onDispose { lifecycle.removeObserver(observer) }
    }
    Surface(Modifier.fillMaxSize(), color = Color.Black) {
        LazyColumn(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item { Column { BackButton(onBack); Text("Home screen widgets", style = MaterialTheme.typography.headlineMedium) } }
            item { EditorCard("Add a widget") {
                Text("Long-press an empty space on your home screen, choose Widgets, then BatteryMonitor. Choose a preset, customize it and save. Resize it using your launcher's handles.")
                Text("1×1 through 4×4 and other intermediate dimensions adapt automatically. Exact grid sizes depend on your launcher.", style = MaterialTheme.typography.bodySmall)
            } }
            if (ids.isEmpty()) item { Text("No widgets placed yet. Each placed widget will appear here with its own settings.", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(ids, key = { it }) { id ->
                val config = remember(id, revision) { WidgetConfigStore(context).get(id) }
                EditorCard(config.name) {
                    Text("Widget #$id · ${config.elements.size} elements · ${config.cadence.title}", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { context.startActivity(Intent(context, WidgetConfigurationActivity::class.java).putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)) }) { Text("Customize") }
                }
            }
            item { Text("The existing monitoring service supplies widget readings. Android and HyperOS decide when background work and launcher redraws are allowed.",
                Modifier.padding(bottom = 24.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}
