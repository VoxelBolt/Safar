package com.safar.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.widget.ImageView
import com.safar.app.R
import com.safar.app.domain.model.Route
import com.safar.app.domain.model.UserRole
import com.safar.app.ui.theme.SafarBlue
import com.safar.app.ui.theme.SafarNavy
import com.safar.app.ui.theme.SafarWhite

private val MutedColor = Color(0xFF71809A)

@Composable
fun LanguageSelectionScreen(
    selectedLanguage: String,
    onLanguageSelected: (String) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SafarWhite)
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(42.dp))
        BrandLogo(modifier = Modifier.size(104.dp))
        Spacer(Modifier.height(26.dp))
        Text(
            text = stringResource(R.string.choose_language_title),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = SafarNavy,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.choose_language_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(26.dp))

        LanguageOption(
            label = stringResource(R.string.language_russian),
            emoji = "🇷🇺",
            selected = selectedLanguage == "ru",
            onClick = { onLanguageSelected("ru") },
        )
        Spacer(Modifier.height(12.dp))
        LanguageOption(
            label = stringResource(R.string.language_tajik),
            emoji = "🇹🇯",
            selected = selectedLanguage == "tg",
            onClick = { onLanguageSelected("tg") },
        )
        Spacer(Modifier.height(12.dp))
        LanguageOption(
            label = stringResource(R.string.language_english),
            emoji = "🇬🇧",
            selected = selectedLanguage == "en",
            onClick = { onLanguageSelected("en") },
        )

        Spacer(Modifier.height(28.dp))
        PrimaryButton(
            text = stringResource(R.string.continue_button),
            enabled = selectedLanguage.isNotBlank(),
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun RoleSelectionScreen(
    selectedRole: UserRole?,
    onBack: () -> Unit,
    onRoleSelected: (UserRole) -> Unit,
    onContinue: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SafarWhite)
            .navigationBarsPadding()
            .padding(horizontal = 20.dp),
    ) {
        TopBackButton(onClick = onBack)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(4.dp))
            BrandLogo(modifier = Modifier.size(88.dp))
            Spacer(Modifier.height(22.dp))
            Text(
                text = stringResource(R.string.role_title),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = SafarNavy,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.role_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            RoleCard(
                role = UserRole.PASSENGER,
                selected = selectedRole == UserRole.PASSENGER,
                onClick = { onRoleSelected(UserRole.PASSENGER) },
            )
            Spacer(Modifier.height(14.dp))
            RoleCard(
                role = UserRole.DRIVER,
                selected = selectedRole == UserRole.DRIVER,
                onClick = { onRoleSelected(UserRole.DRIVER) },
            )
            Spacer(Modifier.height(18.dp))
        }
        PrimaryButton(
            text = stringResource(R.string.continue_button),
            enabled = selectedRole != null,
            onClick = onContinue,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        )
        Spacer(Modifier.height(14.dp))
    }
}

@Composable
fun RegistrationScreen(
    role: UserRole,
    state: OnboardingState,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onPhoneChange: (String) -> Unit,
    onVehicleChange: (String) -> Unit,
    driverRoutes: List<Route> = emptyList(),
    selectedRouteId: String? = null,
    routesLoading: Boolean = false,
    routesError: Boolean = false,
    onRouteSelected: (Route) -> Unit = {},
    onContinue: () -> Unit,
) {
    var attempted by remember { mutableStateOf(false) }
    val nameValid = state.name.trim().isNotEmpty()
    val phoneValid = state.phone.trim().isNotEmpty()
    val driverFieldsValid = role == UserRole.PASSENGER ||
        (state.vehicleIdentifier.trim().isNotEmpty() && selectedRouteId != null)
    val formValid = nameValid && phoneValid && driverFieldsValid

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SafarWhite)
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopBackButton(onClick = onBack)
            Text(
                text = if (role == UserRole.PASSENGER) {
                    stringResource(R.string.passenger_registration_title)
                } else {
                    stringResource(R.string.driver_registration_title)
                },
                modifier = Modifier.padding(start = 4.dp),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = SafarNavy,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(horizontal = 24.dp),
        ) {
            Spacer(Modifier.height(10.dp))
            Text(
                text = stringResource(R.string.registration_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(22.dp))

            RegistrationField(
                label = stringResource(R.string.name_label),
                value = state.name,
                onValueChange = onNameChange,
                isError = attempted && !nameValid,
                errorText = stringResource(R.string.required_field_error),
                keyboardType = KeyboardType.Text,
            )
            Spacer(Modifier.height(14.dp))
            RegistrationField(
                label = stringResource(R.string.phone_label),
                value = state.phone,
                onValueChange = onPhoneChange,
                isError = attempted && !phoneValid,
                errorText = stringResource(R.string.required_field_error),
                keyboardType = KeyboardType.Phone,
            )

            if (role == UserRole.DRIVER) {
                Spacer(Modifier.height(14.dp))
                RegistrationField(
                    label = stringResource(R.string.vehicle_identifier_label),
                    value = state.vehicleIdentifier,
                    onValueChange = onVehicleChange,
                    isError = attempted && state.vehicleIdentifier.trim().isEmpty(),
                    errorText = stringResource(R.string.required_field_error),
                    keyboardType = KeyboardType.Text,
                )

                Spacer(Modifier.height(14.dp))
                var routeMenuExpanded by remember { mutableStateOf(false) }
                val selectedRoute = driverRoutes.firstOrNull { it.id == selectedRouteId }
                Text(
                    text = stringResource(R.string.driver_route_label),
                    style = MaterialTheme.typography.labelLarge,
                    color = SafarNavy,
                    modifier = Modifier.padding(bottom = 7.dp),
                )
                Box {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .border(1.dp, if (attempted && selectedRoute == null) MaterialTheme.colorScheme.error else Color(0xFFE3E8F0), RoundedCornerShape(14.dp))
                            .clickable(enabled = !routesLoading) { routeMenuExpanded = true },
                        color = SafarWhite,
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 15.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = selectedRoute?.number ?: if (routesLoading) stringResource(R.string.routes_loading) else stringResource(R.string.route_select_hint),
                                modifier = Modifier.weight(1f),
                                color = if (selectedRoute == null) MutedColor else SafarNavy,
                            )
                            Text("⌄", color = SafarBlue)
                        }
                    }
                    DropdownMenu(
                        expanded = routeMenuExpanded,
                        onDismissRequest = { routeMenuExpanded = false },
                    ) {
                        driverRoutes.forEach { route ->
                            DropdownMenuItem(
                                text = { Text(route.number) },
                                onClick = {
                                    routeMenuExpanded = false
                                    onRouteSelected(route)
                                },
                            )
                        }
                    }
                }
                if (routesError) {
                    Text(
                        text = stringResource(R.string.routes_load_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                } else if (attempted && selectedRoute == null && !routesLoading) {
                    Text(
                        text = stringResource(R.string.required_field_error),
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 4.dp),
                    )
                }
            }

            Spacer(Modifier.height(26.dp))
            PrimaryButton(
                text = if (state.registrationInProgress) {
                    stringResource(R.string.registration_in_progress)
                } else {
                    stringResource(R.string.continue_button)
                },
                enabled = !state.registrationInProgress,
                onClick = {
                    attempted = true
                    if (formValid) onContinue()
                },
                modifier = Modifier.fillMaxWidth(),
            )
            if (attempted && !formValid) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = stringResource(R.string.complete_required_fields),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            if (state.registrationError) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.registrationErrorMessage
                        ?: stringResource(R.string.registration_failed),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
private fun BrandLogo(modifier: Modifier = Modifier) {
    // Use the platform ImageView for bundled raster assets.
    // This avoids a device-specific Jetpack Compose painterResource crash
    // that can occur on some physical devices while decoding drawable assets.
    AndroidView(
        factory = { context ->
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setImageResource(R.drawable.safar_logo_base)
                contentDescription = context.getString(R.string.app_name)
            }
        },
        update = { imageView ->
            imageView.setImageResource(R.drawable.safar_logo_base)
        },
        modifier = modifier.clip(RoundedCornerShape(22.dp)),
    )
}

@Composable
private fun LanguageOption(
    label: String,
    emoji: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(62.dp)
            .clip(RoundedCornerShape(16.dp))
            .border(
                width = 1.dp,
                color = if (selected) SafarBlue else Color(0xFFE3E8F0),
                shape = RoundedCornerShape(16.dp),
            )
            .clickable(onClick = onClick),
        color = SafarWhite,
        tonalElevation = 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = SafarNavy,
                fontWeight = FontWeight.Medium,
            )
            RadioButton(
                selected = selected,
                onClick = null,
            )
        }
    }
}

@Composable
private fun RoleCard(
    role: UserRole,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val title = if (role == UserRole.PASSENGER) {
        stringResource(R.string.passenger_role)
    } else {
        stringResource(R.string.driver_role)
    }
    val subtitle = if (role == UserRole.PASSENGER) {
        stringResource(R.string.passenger_role_subtitle)
    } else {
        stringResource(R.string.driver_role_subtitle)
    }

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) SafarBlue else Color(0xFFE3E8F0),
                shape = RoundedCornerShape(22.dp),
            )
            .clickable(onClick = onClick),
        color = SafarWhite,
        shadowElevation = if (selected) 2.dp else 0.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AndroidView(
                factory = { context ->
                    ImageView(context).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        setImageResource(
                            if (role == UserRole.PASSENGER) R.drawable.passenger_illustration
                            else R.drawable.driver_illustration,
                        )
                        contentDescription = title
                    }
                },
                update = { imageView ->
                    imageView.setImageResource(
                        if (role == UserRole.PASSENGER) R.drawable.passenger_illustration
                        else R.drawable.driver_illustration,
                    )
                },
                modifier = Modifier
                    .size(58.dp)
                    .clip(CircleShape),
            )
            Spacer(Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = SafarNavy,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                text = "›",
                style = MaterialTheme.typography.headlineSmall,
                color = if (selected) SafarBlue else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun RegistrationField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    isError: Boolean,
    errorText: String,
    keyboardType: KeyboardType,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        isError = isError,
        supportingText = if (isError) {
            { Text(errorText) }
        } else null,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        shape = RoundedCornerShape(16.dp),
    )
}

@Composable
private fun PrimaryButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = SafarBlue,
            contentColor = SafarWhite,
            disabledContainerColor = Color(0xFFDDE5F1),
            disabledContentColor = Color(0xFF7D8CA2),
        ),
    ) {
        Text(text = text, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun TopBackButton(onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
    ) {
        Text(
            text = "‹",
            style = MaterialTheme.typography.headlineMedium,
            color = SafarNavy,
        )
    }
}
