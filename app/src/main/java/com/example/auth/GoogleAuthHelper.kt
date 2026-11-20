package com.example.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialException
import androidx.credentials.exceptions.GetCredentialProviderConfigurationException
import androidx.credentials.exceptions.NoCredentialException
import com.example.R
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GetSignInWithGoogleOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential.Companion.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL
import com.google.firebase.Firebase
import com.google.firebase.auth.GoogleAuthProvider
import com.google.firebase.auth.auth
import java.security.MessageDigest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

object GoogleAuthHelper {
    private const val TAG = "Auth"
    private const val REGISTERED_FIREBASE_SHA1 = "7721D991211A6EE4789AC1B5FB972C4309C02EA2"

    fun attemptAutoSignIn(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onUnauthenticated: () -> Unit,
        scope: CoroutineScope
    ) {
        if (Firebase.auth.currentUser != null) {
            onAuthSuccess()
            return
        }
        val clientId = try {
            context.getString(R.string.default_web_client_id)
        } catch (_: Exception) {
            onUnauthenticated()
            return
        }
        val activity = context.findActivity()
        val credentialContext: Context = activity ?: context
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(true)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(true)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()

        scope.launch {
            try {
                val cred = credentialManager.getCredential(credentialContext, request).credential
                if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
                    Firebase.auth.signInWithCredential(
                        GoogleAuthProvider.getCredential(token, null)
                    ).await()
                    onAuthSuccess()
                } else {
                    onUnauthenticated()
                }
            } catch (_: Exception) {
                onUnauthenticated()
            }
        }
    }

    fun onGoogleSignInClicked(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: (String?) -> Unit = {}
    ) {
        val clientId = resolveClientId(context, onAuthError) ?: return
        val signInOption = GetSignInWithGoogleOption.Builder(serverClientId = clientId).build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(signInOption)
            .build()
        executeCredentialSignIn(
            context = context,
            credentialManager = credentialManager,
            request = request,
            onAuthSuccess = onAuthSuccess,
            onAuthError = onAuthError,
            onAuthCancelled = onAuthCancelled,
            scope = scope
        )
    }

    fun onGoogleAccountSelectorClicked(
        context: Context,
        credentialManager: CredentialManager,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        scope: CoroutineScope,
        onAuthCancelled: (String?) -> Unit = {}
    ) {
        val clientId = resolveClientId(context, onAuthError) ?: return
        val googleIdOption = GetGoogleIdOption.Builder()
            .setFilterByAuthorizedAccounts(false)
            .setServerClientId(clientId)
            .setAutoSelectEnabled(false)
            .build()
        val request = GetCredentialRequest.Builder()
            .addCredentialOption(googleIdOption)
            .build()
        executeCredentialSignIn(
            context = context,
            credentialManager = credentialManager,
            request = request,
            onAuthSuccess = onAuthSuccess,
            onAuthError = onAuthError,
            onAuthCancelled = onAuthCancelled,
            scope = scope
        )
    }

    private fun resolveClientId(context: Context, onAuthError: (String) -> Unit): String? {
        return try {
            context.getString(R.string.default_web_client_id).takeIf { it.isNotBlank() }
                ?: run {
                    onAuthError("Falta configuración de Google Sign-In (default_web_client_id vacío).")
                    null
                }
        } catch (_: Exception) {
            onAuthError("Falta configuración de Google Sign-In (default_web_client_id).")
            null
        }
    }

    private fun executeCredentialSignIn(
        context: Context,
        credentialManager: CredentialManager,
        request: GetCredentialRequest,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit,
        onAuthCancelled: (String?) -> Unit,
        scope: CoroutineScope
    ) {
        val activity = context.findActivity()
        if (activity == null) {
            onAuthError("No se pudo obtener la actividad para iniciar sesión.")
            return
        }

        scope.launch {
            try {
                val cred = credentialManager.getCredential(activity, request).credential
                handleGoogleCredential(cred, onAuthSuccess, onAuthError)
            } catch (e: GetCredentialCancellationException) {
                Log.w(TAG, "Google Sign-In cancelled or dismissed: ${e.message}", e)
                onAuthCancelled(buildCancellationDiagnostic(context, e))
            } catch (e: NoCredentialException) {
                Log.w(TAG, "No Google credential available", e)
                onAuthError(
                    "No se encontró una cuenta Google lista en este teléfono. Verifica en Ajustes → Google que tengas sesión iniciada o prueba con el botón principal."
                )
            } catch (e: GetCredentialProviderConfigurationException) {
                Log.e(TAG, "Credential provider misconfigured", e)
                onAuthError(
                    "Google Play Services no está actualizado o falta configuración del proveedor de credenciales."
                )
            } catch (e: GetCredentialException) {
                Log.e(TAG, "Google Sign-In credential error: ${e.type} ${e.errorMessage}", e)
                val detail = e.errorMessage?.toString().orEmpty()
                val shaHint = buildShaMismatchNoteIfAny(context)
                val friendly = when {
                    shaHint != null -> shaHint
                    detail.contains("28444") || detail.contains("10:") || detail.contains("DEVELOPER_ERROR", ignoreCase = true) ->
                        "Error de configuración OAuth / SHA-1 en Firebase. Asegúrate de que el APK esté compilado con el debug.keystore del repositorio."
                    detail.contains("16:") ->
                        "No se pudo conectar con Google Play Services. Revisa tu conexión o actualiza Google Play Services."
                    detail.isNotBlank() -> detail
                    else -> e.localizedMessage ?: "No se pudo completar el inicio de sesión con Google."
                }
                onAuthError(friendly)
            } catch (e: Exception) {
                Log.e(TAG, "Google Sign-In failed", e)
                onAuthError(e.localizedMessage ?: "No se pudo iniciar sesión con Google.")
            }
        }
    }

    private suspend fun handleGoogleCredential(
        cred: Credential,
        onAuthSuccess: () -> Unit,
        onAuthError: (String) -> Unit
    ) {
        if (cred is CustomCredential && cred.type == TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
            val token = GoogleIdTokenCredential.createFrom(cred.data).idToken
            Firebase.auth.signInWithCredential(
                GoogleAuthProvider.getCredential(token, null)
            ).await()
            onAuthSuccess()
        } else {
            onAuthError("Tipo de credencial inesperado al autenticar con Google.")
        }
    }

    private fun buildCancellationDiagnostic(
        context: Context,
        e: GetCredentialCancellationException
    ): String {
        val shaMismatch = buildShaMismatchNoteIfAny(context)
        if (shaMismatch != null) return shaMismatch

        val rawDetail = e.errorMessage?.toString()?.takeIf { it.isNotBlank() }
            ?: e.message?.takeIf { it.isNotBlank() }
        val detailLine = if (rawDetail != null) "\nDetalle: $rawDetail" else ""

        return "Google interrumpió el inicio de sesión después de elegir la cuenta.$detailLine\n" +
            "• Si acabas de agregar el correo en la ventana emergente, primero inicia sesión con esa cuenta Google en Ajustes del teléfono (o Play Store) y luego usa \"Selector rápido de cuenta Google\".\n" +
            "• Si el correo ya está en el teléfono, verifica en Google Cloud Console (pantalla de consentimiento OAuth) que la app esté en Producción o que tu correo figure en Usuarios de prueba."
    }

    private fun buildShaMismatchNoteIfAny(context: Context): String? {
        val actualSha1 = getAppSigningSha1(context) ?: return null
        val normalized = actualSha1.replace(":", "").uppercase()
        if (normalized == REGISTERED_FIREBASE_SHA1) return null
        return "La firma SHA-1 de este APK ($actualSha1) NO coincide con la registrada en Firebase (77:21:D9:91:...). " +
            "Recompila el APK restaurando debug.keystore.base64 o agrega esa huella SHA-1 en Firebase Console y actualiza google-services.json."
    }

    private fun getAppSigningSha1(context: Context): String? {
        return try {
            val pm = context.packageManager
            val pkgName = context.packageName
            val certBytes: ByteArray? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNING_CERTIFICATES)
                val signingInfo = info.signingInfo
                val sigs = if (signingInfo?.hasMultipleSigners() == true) {
                    signingInfo.apkContentsSigners
                } else {
                    signingInfo?.signingCertificateHistory
                }
                sigs?.firstOrNull()?.toByteArray()
            } else {
                @Suppress("DEPRECATION")
                val info = pm.getPackageInfo(pkgName, PackageManager.GET_SIGNATURES)
                @Suppress("DEPRECATION")
                info.signatures?.firstOrNull()?.toByteArray()
            }
            if (certBytes == null) return null
            val digest = MessageDigest.getInstance("SHA-1").digest(certBytes)
            digest.joinToString(":") { "%02X".format(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun signOut(
        credentialManager: CredentialManager,
        scope: CoroutineScope,
        onSignOutComplete: () -> Unit = {}
    ) {
        scope.launch {
            withContext(NonCancellable) {
                try {
                    credentialManager.clearCredentialState(ClearCredentialStateRequest())
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to clear credential state: ${e.message}")
                } finally {
                    Firebase.auth.signOut()
                    onSignOutComplete()
                }
            }
        }
    }
}
