package com.safar.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.safar.app.R
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.UserRole
import com.safar.app.domain.model.Vehicle
import com.safar.app.ui.onboarding.OnboardingState
import com.safar.app.ui.theme.SafarBlue
import com.safar.app.ui.theme.SafarNavy
import com.safar.app.ui.theme.SafarWhite
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun PassengerFavoritesRealScreen(
    viewModel: FavoriteViewModel,
    modifier: Modifier = Modifier,
) {
    val ui by viewModel.uiState.collectAsState()
    LaunchedEffect(Unit) { viewModel.load() }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.favorites_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = SafarNavy,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = viewModel::load) { Text(stringResource(R.string.refresh)) }
        }
        Text(stringResource(R.string.favorites_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        when {
            ui.loading -> LoadingMessage()
            ui.error != null -> ErrorMessage(onRetry = viewModel::retry)
            ui.vehicles.isEmpty() -> EmptyState(
                glyph = Glyph.STAR,
                title = stringResource(R.string.favorites_empty_title),
                subtitle = stringResource(R.string.favorites_empty_subtitle),
            )
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                items(ui.vehicles, key = { it.id }) { vehicle ->
                    FavoriteVehicleRealCard(
                        vehicle = vehicle,
                        routeNumber = ui.routeNumbers[vehicle.routeId] ?: stringResource(R.string.route_not_selected),
                        isFavorite = true,
                        onToggle = { viewModel.toggle(vehicle) },
                    )
                }
            }
        }
    }
}

@Composable
fun PassengerProfileRealScreen(
    isDriver: Boolean,
    onLanguageSelected: (String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewModel: ProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val ui by viewModel.uiState.collectAsState()
    var editOpen by remember { mutableStateOf(false) }
    var languageOpen by remember { mutableStateOf(false) }
    var infoTitle by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isDriver) { viewModel.load(isDriver) }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SafarNavy)
        Text(stringResource(R.string.profile_subtitle), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))

        when {
            ui.loading && ui.user == null -> LoadingMessage()
            ui.error != null && ui.user == null -> ErrorMessage(onRetry = { viewModel.retry(isDriver) })
            else -> {
                ProfileHeaderReal(
                    name = ui.user?.name ?: stringResource(R.string.user_default_name),
                    phone = ui.user?.phone.orEmpty(),
                    onClick = { editOpen = true },
                )
                Spacer(Modifier.height(12.dp))
                if (isDriver) {
                    Surface(shape = RoundedCornerShape(18.dp), color = SafarWhite) {
                        Column(Modifier.fillMaxWidth()) {
                            ProfileValueRow(stringResource(R.string.vehicle_label), ui.vehicle?.identifier ?: "—")
                            ProfileValueRow(stringResource(R.string.route_label), ui.route?.number ?: stringResource(R.string.route_not_selected))
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }

                ProfileAction(
                    stringResource(R.string.edit_profile),
                    onClick = { editOpen = true },
                )
                ProfileAction(
                    stringResource(R.string.notifications),
                    onClick = { infoTitle = stringResource(R.string.notifications_info) },
                )
                ProfileAction(
                    stringResource(R.string.language_label),
                    value = languageName(ui.user?.let { null } ?: "ru"),
                    onClick = { languageOpen = true },
                )
                if (isDriver) {
                    ProfileAction(
                        stringResource(R.string.driver_verification),
                        value = if (ui.driver?.verified == true) stringResource(R.string.verified) else stringResource(R.string.not_verified),
                        onClick = { infoTitle = stringResource(R.string.driver_verification_info) },
                    )
                }
                ProfileAction(
                    stringResource(R.string.help),
                    onClick = { infoTitle = stringResource(R.string.help_info) },
                )
                ProfileAction(
                    stringResource(R.string.about_app),
                    value = "v0.1.0",
                    onClick = { infoTitle = stringResource(R.string.about_info) },
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(16.dp),
                ) {
                    Text(stringResource(R.string.logout))
                }
                ui.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.generic_save_error), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

    if (editOpen && ui.user != null) {
        ProfileEditDialog(
            initialName = ui.user.name,
            initialPhone = ui.user.phone,
            saving = ui.saving,
            onDismiss = { editOpen = false },
            onSave = { name, phone ->
                viewModel.save(name, phone)
                editOpen = false
            },
        )
    }

    if (languageOpen) {
        LanguageDialog(
            current = "ru",
            onDismiss = { languageOpen = false },
            onSelect = {
                onLanguageSelected(it)
                languageOpen = false
            },
        )
    }

    infoTitle?.let { title ->
        AlertDialog(
            onDismissRequest = { infoTitle = null },
            confirmButton = { TextButton(onClick = { infoTitle = null }) { Text(stringResource(R.string.ok)) } },
            title = { Text(title) },
            text = { Text(infoTextFor(title)) },
        )
    }
}

@Composable
fun DriverProfileRealScreen(
    onLanguageSelected: (String) -> Unit,
    onLogout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PassengerProfileRealScreen(
        isDriver = true,
        onLanguageSelected = onLanguageSelected,
        onLogout = onLogout,
        modifier = modifier,
    )
}

@Composable
fun DriverStatisticsRealScreen(modifier: Modifier = Modifier) {
    val viewModel: StatisticsViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val ui by viewModel.uiState.collectAsState()
    LaunchedEffect(ui.period) { viewModel.load(ui.period) }

    Column(
        modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.statistics_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = SafarNavy)
        Spacer(Modifier.height(14.dp))
        SegmentedControl(
            labels = listOf(
                stringResource(R.string.period_7_days),
                stringResource(R.string.period_30_days),
                stringResource(R.string.period_all),
            ),
            selected = ui.period.ordinal,
            onSelected = { viewModel.selectPeriod(StatisticsPeriod.entries[it]) },
        )
        Spacer(Modifier.height(14.dp))

        when {
            ui.loading -> LoadingMessage()
            ui.error != null -> ErrorMessage(onRetry = viewModel::retry)
            ui.sessions.isEmpty() -> EmptyState(
                glyph = Glyph.STATS,
                title = stringResource(R.string.statistics_empty_title),
                subtitle = stringResource(R.string.statistics_empty_subtitle),
            )
            else -> {
                SummaryCard(
                    title = stringResource(R.string.work_time),
                    value = formatWorkDuration(ui.totalWorkSeconds),
                )
                Spacer(Modifier.height(10.dp))
                SummaryCard(
                    title = stringResource(R.string.distance_travelled),
                    value = formatDistance(ui.totalDistanceMeters),
                )
                Spacer(Modifier.height(18.dp))
                Text(stringResource(R.string.statistics_sessions_count, ui.sessions.size), fontWeight = FontWeight.SemiBold, color = SafarNavy)
                Spacer(Modifier.height(10.dp))
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(ui.days, key = { it.dateLabel }) { day ->
                        Surface(shape = RoundedCornerShape(14.dp), color = SafarWhite) {
                            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(day.dateLabel, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(formatWorkDuration(day.workSeconds), fontWeight = FontWeight.SemiBold, color = SafarNavy)
                                Spacer(Modifier.width(12.dp))
                                Text(formatDistance(day.distanceMeters), color = SafarBlue)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileHeaderReal(name: String, phone: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = SafarWhite,
        onClick = onClick,
    ) {
        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = androidx.compose.foundation.shape.CircleShape, color = androidx.compose.ui.graphics.Color(0xFFEAF2FF)) {
                Text("●", modifier = Modifier.padding(18.dp), color = SafarBlue)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SafarNavy)
                Text(phone.ifBlank { "—" }, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ProfileValueRow(title: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = SafarNavy)
        Text(value, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProfileAction(title: String, value: String? = null, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        shape = RoundedCornerShape(14.dp),
        color = SafarWhite,
        onClick = onClick,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = SafarNavy)
            value?.let { Text(it, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun ProfileEditDialog(
    initialName: String,
    initialPhone: String,
    saving: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var name by remember(initialName) { mutableStateOf(initialName) }
    var phone by remember(initialPhone) { mutableStateOf(initialPhone) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.edit_profile)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(name, { name = it }, label = { Text(stringResource(R.string.name_label)) }, singleLine = true)
                OutlinedTextField(phone, { phone = it }, label = { Text(stringResource(R.string.phone_label)) }, singleLine = true)
            }
        },
        confirmButton = {
            TextButton(enabled = !saving, onClick = { onSave(name, phone) }) {
                Text(if (saving) stringResource(R.string.saving) else stringResource(R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun LanguageDialog(
    current: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.language_label)) },
        text = {
            Column {
                listOf("ru" to stringResource(R.string.language_russian), "en" to stringResource(R.string.language_english), "tg" to stringResource(R.string.language_tajik))
                    .forEach { (tag, label) ->
                        TextButton(onClick = { onSelect(tag) }, modifier = Modifier.fillMaxWidth()) {
                            Text(if (tag == current) "✓ $label" else label)
                        }
                    }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) } },
    )
}

@Composable
private fun FavoriteVehicleRealCard(
    vehicle: Vehicle,
    routeNumber: String,
    isFavorite: Boolean,
    onToggle: () -> Unit,
) {
    Surface(shape = RoundedCornerShape(18.dp), color = SafarWhite) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(routeNumber, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = SafarNavy)
                    Text(vehicle.identifier, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onToggle) { Text(if (isFavorite) "★" else "☆", fontSize = 24.sp, color = SafarBlue) }
            }
            Text(
                if (vehicle.active) stringResource(R.string.transport_on_route) else stringResource(R.string.transport_offline),
                color = if (vehicle.active) androidx.compose.ui.graphics.Color(0xFF22A447) else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            vehicle.currentLocation?.let {
                Text(
                    if (vehicle.active) stringResource(R.string.location_available) else stringResource(R.string.location_stale),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun SummaryCard(title: String, value: String) {
    Surface(shape = RoundedCornerShape(18.dp), color = SafarWhite) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(4.dp))
            Text(value, color = SafarNavy, fontSize = 25.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun LoadingMessage() {
    Column(Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = SafarBlue)
        Spacer(Modifier.height(10.dp))
        Text(stringResource(R.string.loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorMessage(onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(stringResource(R.string.generic_load_error), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        TextButton(onClick = onRetry) { Text(stringResource(R.string.retry)) }
    }
}

private fun formatWorkDuration(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0L)
    val h = safe / 3600
    val m = (safe % 3600) / 60
    return "%d ч %02d мин".format(h, m)
}

private fun formatDistance(meters: Double): String =
    if (meters >= 1000.0) "%.1f км".format(Locale.getDefault(), meters / 1000.0)
    else "%d м".format(meters.coerceAtLeast(0.0).toLong())

private fun languageName(tag: String): String = when (tag.lowercase()) {
    "en" -> "English"
    "tg" -> "Тоҷикӣ"
    else -> "Русский"
}

private fun infoTextFor(title: String): String = title
