package nova.lunavane.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import nova.lunavane.R
import nova.lunavane.ui.components.BrandMark
import nova.lunavane.ui.components.ClickableRow
import nova.lunavane.ui.components.DataRow
import nova.lunavane.ui.components.SectionCard
import nova.lunavane.ui.components.StatusPill
import nova.lunavane.ui.components.StatusTone

internal enum class SettingsPage {
    ROOT,
    ABOUT,
    LICENSES,
}

@Composable
internal fun SettingsScreen(
    page: SettingsPage,
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    dynamicColorSupported: Boolean,
    appVersion: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    when (page) {
        SettingsPage.ROOT -> SettingsRoot(
            themeMode = themeMode,
            dynamicColor = dynamicColor,
            dynamicColorSupported = dynamicColorSupported,
            appVersion = appVersion,
            onThemeModeChange = onThemeModeChange,
            onDynamicColorChange = onDynamicColorChange,
            onOpenPage = onOpenPage,
        )
        SettingsPage.ABOUT -> AboutPage(appVersion = appVersion)
        SettingsPage.LICENSES -> LicensesPage()
    }
}

@Composable
private fun SettingsRoot(
    themeMode: ThemeMode,
    dynamicColor: Boolean,
    dynamicColorSupported: Boolean,
    appVersion: String,
    onThemeModeChange: (ThemeMode) -> Unit,
    onDynamicColorChange: (Boolean) -> Unit,
    onOpenPage: (SettingsPage) -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    val githubUrl = stringResource(R.string.project_github_url)
    val openUrl: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            SectionCard(title = stringResource(R.string.settings_appearance)) {
                ThemeModeRow(
                    label = stringResource(R.string.settings_theme_system),
                    selected = themeMode == ThemeMode.SYSTEM,
                    onClick = { onThemeModeChange(ThemeMode.SYSTEM) },
                )
                ThemeModeRow(
                    label = stringResource(R.string.settings_theme_light),
                    selected = themeMode == ThemeMode.LIGHT,
                    onClick = { onThemeModeChange(ThemeMode.LIGHT) },
                )
                ThemeModeRow(
                    label = stringResource(R.string.settings_theme_dark),
                    selected = themeMode == ThemeMode.DARK,
                    onClick = { onThemeModeChange(ThemeMode.DARK) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = dynamicColor && dynamicColorSupported,
                            enabled = dynamicColorSupported,
                            role = Role.Switch,
                            onValueChange = onDynamicColorChange,
                        )
                        .padding(vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_dynamic_color),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = if (dynamicColorSupported) {
                                stringResource(R.string.settings_dynamic_color_desc)
                            } else {
                                stringResource(R.string.settings_dynamic_color_unsupported)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(12.dp))
                    Switch(
                        checked = dynamicColor && dynamicColorSupported,
                        onCheckedChange = null,
                        enabled = dynamicColorSupported,
                    )
                }
            }
        }

        item {
            SectionCard(title = stringResource(R.string.settings_about)) {
                DataRow(
                    label = stringResource(R.string.settings_version),
                    value = appVersion,
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ClickableRow(
                    title = stringResource(R.string.settings_about_project),
                    onClick = { onOpenPage(SettingsPage.ABOUT) },
                    trailing = { Chevron() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ClickableRow(
                    title = stringResource(R.string.settings_github),
                    onClick = { openUrl(githubUrl) },
                    trailing = { Chevron() },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                ClickableRow(
                    title = stringResource(R.string.settings_licenses),
                    onClick = { onOpenPage(SettingsPage.LICENSES) },
                    trailing = { Chevron() },
                )
            }
        }

        item {
            SectionCard(title = stringResource(R.string.settings_unofficial_title)) {
                Text(
                    text = stringResource(R.string.settings_unofficial_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun ThemeModeRow(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.RadioButton,
            )
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun Chevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun AboutPage(appVersion: String) {
    val uriHandler = LocalUriHandler.current
    val githubUrl = stringResource(R.string.project_github_url)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            SectionCard {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    BrandMark(size = 72.dp)
                    Text(
                        text = stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = stringResource(R.string.about_version, appVersion),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            SectionCard(title = stringResource(R.string.about_project_title)) {
                Text(
                    text = stringResource(R.string.about_body),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.about_license_body),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard(title = stringResource(R.string.settings_unofficial_title)) {
                Text(
                    text = stringResource(R.string.settings_unofficial_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            Button(
                onClick = {
                    runCatching { uriHandler.openUri(githubUrl) }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.settings_github))
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun LicensesPage() {
    val uriHandler = LocalUriHandler.current
    val noticesUrl = stringResource(R.string.third_party_notices_url)
    val components = listOf(
        stringResource(R.string.component_lunavane) to stringResource(R.string.license_mpl),
        stringResource(R.string.component_zerotier) to stringResource(R.string.license_mpl),
        stringResource(R.string.component_nlohmann) to stringResource(R.string.license_mit),
        stringResource(R.string.component_prometheus) to stringResource(R.string.license_mit),
        stringResource(R.string.component_lz4) to stringResource(R.string.license_bsd2),
        stringResource(R.string.component_androidx) to stringResource(R.string.license_apache),
    )
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        item {
            SectionCard {
                Text(
                    text = stringResource(R.string.licenses_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Start,
                )
            }
        }
        item {
            SectionCard(title = stringResource(R.string.licenses_components_title)) {
                components.forEachIndexed { index, (component, license) ->
                    if (index > 0) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = component,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        StatusPill(text = license, tone = StatusTone.NEUTRAL)
                    }
                }
            }
        }
        item {
            OutlinedButton(
                onClick = {
                    runCatching {
                        uriHandler.openUri(noticesUrl)
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.licenses_full_notices))
            }
        }
        item {
            Spacer(Modifier.height(8.dp))
        }
    }
}
