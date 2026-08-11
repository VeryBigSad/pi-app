package io.github.verybigsad.pimobile.security

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.credentials.CredentialProviderService
import androidx.annotation.RequiresApi
import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CreatePublicKeyCredentialResponse
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.CreateCredentialException
import androidx.credentials.exceptions.CreateCredentialNoCreateOptionException
import androidx.credentials.exceptions.CreateCredentialProviderConfigurationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import kotlinx.coroutines.CancellationException

enum class PasskeyCeremony {
    REGISTRATION,
    ASSERTION,
}

sealed interface PasskeyResult {
    data class Registration(val responseJson: String) : PasskeyResult
    data class Assertion(val responseJson: String) : PasskeyResult
    data class Locked(val reason: PasskeyLockReason) : PasskeyResult

    sealed interface Failure : PasskeyResult {
        val code: String
    }

    data object Cancelled : Failure {
        override val code: String get() = "PASSKEY_CANCELLED"
    }

    data class NoCredential(val ceremony: PasskeyCeremony) : Failure {
        override val code: String get() = "PASSKEY_NO_CREDENTIAL"
    }

    data class ProviderError(val providerType: String? = null) : Failure {
        override val code: String get() = "PASSKEY_PROVIDER_ERROR"
    }

    data class Failed(override val code: String) : Failure
}

/** Passkey ceremony surface used by pairing orchestration; production is [PasskeyClient]. */
interface PasskeyCeremonyPerformer {
    suspend fun register(optionsJson: String): PasskeyResult

    suspend fun assert(optionsJson: String): PasskeyResult
}

class PasskeyClient(private val activity: Activity) : PasskeyCeremonyPerformer {
    private val manager = CredentialManager.create(activity)

    fun availability(): PasskeyAvailability {
        if (activity.packageName != PasskeyIdentity.PackageName) {
            return PasskeyAvailability.Locked(PasskeyLockReason.APPLICATION_IDENTITY_MISMATCH)
        }
        val origin = runCatching { AndroidOrigin.current(activity) }.getOrNull()
        if (origin == null || origin !in PasskeyOrigins.allowedAndroidOrigins()) {
            return PasskeyAvailability.Locked(PasskeyLockReason.APPLICATION_IDENTITY_MISMATCH)
        }
        if (PasskeyDebugHooks.executor != null) {
            return PasskeyAvailability.Available(PasskeyProviderKind.PLAY_SERVICES, 1, false)
        }
        return PasskeyProviderProbe.availability(activity)
    }

    override suspend fun register(optionsJson: String): PasskeyResult {
        val request = runCatching { PasskeyPolicy.registration(optionsJson) }.getOrElse {
            return PasskeyResult.Failed("PASSKEY_REGISTRATION_OPTIONS_INVALID")
        }
        PasskeyDebugHooks.executor?.let { executor ->
            return executeDebug(PasskeyCeremony.REGISTRATION, request) { executor.createCredential(optionsJson) }
        }
        val unavailable = availability() as? PasskeyAvailability.Locked
        if (unavailable != null) return PasskeyResult.Locked(unavailable.reason)
        return try {
            val response = manager.createCredential(
                context = activity,
                request = CreatePublicKeyCredentialRequest(requestJson = optionsJson),
            )
            val publicKey = response as? CreatePublicKeyCredentialResponse
                ?: return PasskeyResult.Failed("PASSKEY_RESPONSE_INVALID")
            PasskeyPolicy.validateRegistrationResponse(publicKey.registrationResponseJson, request)
            PasskeyResult.Registration(publicKey.registrationResponseJson)
        } catch (_: CreateCredentialCancellationException) {
            PasskeyResult.Cancelled
        } catch (_: CreateCredentialNoCreateOptionException) {
            PasskeyResult.NoCredential(PasskeyCeremony.REGISTRATION)
        } catch (_: CreateCredentialProviderConfigurationException) {
            PasskeyResult.Locked(PasskeyLockReason.PROVIDER_CONFIGURATION_MISSING)
        } catch (error: CreateCredentialException) {
            PasskeyResult.ProviderError(error.type)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            PasskeyResult.Failed("PASSKEY_RESPONSE_INVALID")
        }
    }

    override suspend fun assert(optionsJson: String): PasskeyResult {
        val request = runCatching { PasskeyPolicy.assertion(optionsJson) }.getOrElse {
            return PasskeyResult.Failed("PASSKEY_ASSERTION_OPTIONS_INVALID")
        }
        PasskeyDebugHooks.executor?.let { executor ->
            return executeDebug(PasskeyCeremony.ASSERTION, request) { executor.getCredential(optionsJson) }
        }
        val unavailable = availability() as? PasskeyAvailability.Locked
        if (unavailable != null) return PasskeyResult.Locked(unavailable.reason)
        return try {
            val response = manager.getCredential(
                context = activity,
                request = GetCredentialRequest(
                    credentialOptions = listOf(GetPublicKeyCredentialOption(requestJson = optionsJson)),
                ),
            )
            val publicKey = response.credential as? PublicKeyCredential
                ?: return PasskeyResult.Failed("PASSKEY_RESPONSE_INVALID")
            PasskeyPolicy.validateAssertionResponse(publicKey.authenticationResponseJson, request)
            PasskeyResult.Assertion(publicKey.authenticationResponseJson)
        } catch (_: GetCredentialCancellationException) {
            PasskeyResult.Cancelled
        } catch (_: NoCredentialException) {
            PasskeyResult.NoCredential(PasskeyCeremony.ASSERTION)
        } catch (_: GetCredentialProviderConfigurationException) {
            PasskeyResult.Locked(PasskeyLockReason.PROVIDER_CONFIGURATION_MISSING)
        } catch (error: GetCredentialException) {
            PasskeyResult.ProviderError(error.type)
        } catch (error: CancellationException) {
            throw error
        } catch (_: IllegalArgumentException) {
            PasskeyResult.Failed("PASSKEY_RESPONSE_INVALID")
        }
    }

    private suspend fun executeDebug(
        ceremony: PasskeyCeremony,
        request: ValidatedPasskeyRequest,
        execute: suspend () -> String,
    ): PasskeyResult {
        val responseJson = try {
            execute()
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return PasskeyResult.ProviderError("debug-executor")
        }
        return runCatching {
            when (ceremony) {
                PasskeyCeremony.REGISTRATION -> PasskeyPolicy.validateRegistrationResponse(responseJson, request)
                PasskeyCeremony.ASSERTION -> PasskeyPolicy.validateAssertionResponse(responseJson, request)
            }
        }.fold(
            onSuccess = {
                when (ceremony) {
                    PasskeyCeremony.REGISTRATION -> PasskeyResult.Registration(responseJson)
                    PasskeyCeremony.ASSERTION -> PasskeyResult.Assertion(responseJson)
                }
            },
            onFailure = { PasskeyResult.Failed("PASSKEY_RESPONSE_INVALID") },
        )
    }
}

internal object PasskeyProviderProbe {
    internal const val MinimumPlayServicesVersion = 230_815_045

    fun availability(context: Context): PasskeyAvailability = if (Build.VERSION.SDK_INT <= 33) {
        val playServicesAvailable = runCatching {
            GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(
                context,
                MinimumPlayServicesVersion,
            ) == ConnectionResult.SUCCESS
        }.getOrDefault(false)
        PasskeyProviderMatrix.evaluate(Build.VERSION.SDK_INT, playServicesAvailable, 0)
    } else {
        val enabledProviders = runCatching { enabledFrameworkProviders(context) }.getOrDefault(0)
        PasskeyProviderMatrix.evaluate(Build.VERSION.SDK_INT, false, enabledProviders)
    }

    @RequiresApi(34)
    @Suppress("DEPRECATION")
    private fun enabledFrameworkProviders(context: Context): Int {
        val framework = context.getSystemService(android.credentials.CredentialManager::class.java) ?: return 0
        val services = context.packageManager.queryIntentServices(
            Intent(CredentialProviderService.SERVICE_INTERFACE),
            android.content.pm.PackageManager.MATCH_ALL,
        )
        require(services.size <= 64)
        return services.mapNotNull { info ->
            val service = info.serviceInfo ?: return@mapNotNull null
            if (service.permission != "android.permission.BIND_CREDENTIAL_PROVIDER_SERVICE") return@mapNotNull null
            ComponentName(service.packageName, service.name)
        }.distinct().count { component ->
            runCatching { framework.isEnabledCredentialProviderService(component) }.getOrDefault(false)
        }
    }
}
