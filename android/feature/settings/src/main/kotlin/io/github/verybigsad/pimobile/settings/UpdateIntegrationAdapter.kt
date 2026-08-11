package io.github.verybigsad.pimobile.settings

import io.github.verybigsad.pimobile.update.UpdateState

/**
 * Adapts core/update [UpdateState] into the settings [UpdateUiState]. Current-version and
 * last-checked data do not exist inside [UpdateState]; the host injects both.
 */
class UpdateIntegrationUiAdapter(
    private val currentVersionName: String?,
    private val currentVersionCode: Long?,
    private val lastCheckedEpochMillis: () -> Long?,
) : UpdateUiStateAdapter<UpdateState> {
    override fun toUiState(source: UpdateState): UpdateUiState {
        val lastChecked = lastCheckedEpochMillis()
        val availability = when (source) {
            UpdateState.Disabled -> UpdateAvailability.Disabled
            UpdateState.Idle ->
                if (lastChecked == null) UpdateAvailability.Unknown else UpdateAvailability.UpToDate
            UpdateState.Checking -> UpdateAvailability.Checking
            is UpdateState.Available -> UpdateAvailability.Available(source.candidate.versionName)
            is UpdateState.InstallPermissionRequired -> UpdateAvailability.Available(source.candidate.versionName)
            is UpdateState.Downloading -> UpdateAvailability.Available(source.candidate.versionName)
            is UpdateState.Paused -> UpdateAvailability.Available(source.candidate.versionName)
            is UpdateState.Verifying -> UpdateAvailability.Available(source.candidate.versionName)
            is UpdateState.ReadyToInstall -> UpdateAvailability.Available(source.candidate.versionName)
            is UpdateState.Staging -> UpdateAvailability.Available(source.candidate.versionName)
            is UpdateState.AwaitingSystemConfirmation -> UpdateAvailability.Available(source.candidate.versionName)
            is UpdateState.Installing -> UpdateAvailability.Available(source.candidate.versionName)
            is UpdateState.Installed -> UpdateAvailability.UpToDate
            is UpdateState.Failed -> UpdateAvailability.Failed(source.code)
        }
        return UpdateUiState(
            currentVersionName = currentVersionName,
            currentVersionCode = currentVersionCode,
            lastCheckedEpochMillis = lastChecked,
            availability = availability,
        )
    }
}
