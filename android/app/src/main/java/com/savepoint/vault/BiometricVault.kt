package com.savepoint.vault

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

sealed class BioResult {
    object Success : BioResult()
    /** El usuario cerró el diálogo. [usePassword] = tocó "Usar contraseña". */
    data class Cancelled(val usePassword: Boolean) : BioResult()
    /** Se agregaron o quitaron huellas: la llave quedó inválida y la huella se desactivó. */
    object Invalidated : BioResult()
    data class Failed(val message: String) : BioResult()
}

/**
 * Desbloqueo con huella.
 *
 * La llave de la bóveda (la misma que abre la contraseña maestra) se guarda cifrada con
 * una llave AES del Android Keystore que:
 *  - nunca sale del hardware seguro del teléfono,
 *  - solo se puede usar justo después de una huella válida (clase "strong"),
 *  - se invalida sola si se registra una huella nueva, para que nadie agregue la suya y entre.
 *
 * La contraseña maestra sigue siendo la llave de respaldo: si la huella falla o se invalida,
 * se desbloquea con contraseña y se puede volver a activar.
 */
object BiometricVault {
    private const val KEY_ALIAS = "savepoint_biometric_v1"
    private const val FILE_NAME = "biometric.json"
    private const val GCM_TAG_BITS = 128

    fun isAvailable(ctx: Context): Boolean =
        BiometricManager.from(ctx).canAuthenticate(BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

    fun isEnabled(ctx: Context): Boolean = file(ctx).exists() && keyStore().containsAlias(KEY_ALIAS)

    fun disable(ctx: Context) {
        file(ctx).delete()
        runCatching { keyStore().deleteEntry(KEY_ALIAS) }
    }

    /** Pide la huella y guarda la llave de la bóveda envuelta. Requiere la bóveda desbloqueada. Hilo principal. */
    fun enable(activity: FragmentActivity, onResult: (BioResult) -> Unit) {
        val dek = try {
            VaultStore.exportKey()
        } catch (e: VaultException) {
            onResult(BioResult.Failed(e.message ?: "La bóveda está bloqueada"))
            return
        }

        val cipher = try {
            disable(activity)
            createKey()
            Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, getKey()) }
        } catch (e: Exception) {
            dek.fill(0)
            disable(activity)
            onResult(BioResult.Failed("No se pudo preparar la llave segura"))
            return
        }

        authenticate(
            activity,
            cipher,
            title = "Activar desbloqueo con huella",
            subtitle = "Confirma con tu huella",
            negative = "Cancelar",
        ) { result, authCipher ->
            if (result == BioResult.Success && authCipher != null) {
                try {
                    val ct = authCipher.doFinal(dek)
                    writeFile(activity, authCipher.iv, ct)
                    onResult(BioResult.Success)
                } catch (e: Exception) {
                    disable(activity)
                    onResult(BioResult.Failed("No se pudo activar la huella"))
                } finally {
                    dek.fill(0)
                }
            } else {
                dek.fill(0)
                disable(activity)
                onResult(result)
            }
        }
    }

    /** Pide la huella y desbloquea la bóveda. Hilo principal. */
    fun unlock(activity: FragmentActivity, negative: String, onResult: (BioResult) -> Unit) {
        val stored = readFile(activity)
        if (stored == null || !keyStore().containsAlias(KEY_ALIAS)) {
            disable(activity)
            onResult(BioResult.Failed("El desbloqueo con huella no está activado"))
            return
        }
        val (iv, ct) = stored

        val cipher = try {
            Cipher.getInstance("AES/GCM/NoPadding").apply {
                init(Cipher.DECRYPT_MODE, getKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
            }
        } catch (e: KeyPermanentlyInvalidatedException) {
            disable(activity)
            onResult(BioResult.Invalidated)
            return
        } catch (e: Exception) {
            onResult(BioResult.Failed("No se pudo usar la llave segura"))
            return
        }

        authenticate(
            activity,
            cipher,
            title = "Desbloquear savePoint",
            subtitle = "Usa tu huella para abrir la bóveda",
            negative = negative,
        ) { result, authCipher ->
            if (result == BioResult.Success && authCipher != null) {
                try {
                    VaultStore.unlockWithKey(activity, authCipher.doFinal(ct))
                    onResult(BioResult.Success)
                } catch (e: Exception) {
                    disable(activity)
                    onResult(BioResult.Failed("La huella ya no abre esta bóveda. Usa tu contraseña maestra."))
                }
            } else {
                onResult(result)
            }
        }
    }

    // ---------- Internos ----------

    private fun authenticate(
        activity: FragmentActivity,
        cipher: Cipher,
        title: String,
        subtitle: String,
        negative: String,
        onDone: (BioResult, Cipher?) -> Unit,
    ) {
        val callback = object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onDone(BioResult.Success, result.cryptoObject?.cipher)
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                val r = when (errorCode) {
                    BiometricPrompt.ERROR_NEGATIVE_BUTTON -> BioResult.Cancelled(usePassword = true)
                    BiometricPrompt.ERROR_USER_CANCELED,
                    BiometricPrompt.ERROR_CANCELED -> BioResult.Cancelled(usePassword = false)
                    else -> BioResult.Failed(errString.toString())
                }
                onDone(r, null)
            }
            // onAuthenticationFailed (dedo no reconocido): el diálogo sigue abierto y deja reintentar.
        }

        val prompt = BiometricPrompt(activity, ContextCompat.getMainExecutor(activity), callback)
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setSubtitle(subtitle)
            .setNegativeButtonText(negative)
            .setAllowedAuthenticators(BIOMETRIC_STRONG)
            .setConfirmationRequired(false)
            .build()
        prompt.authenticate(info, BiometricPrompt.CryptoObject(cipher))
    }

    private fun keyStore(): KeyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun getKey(): SecretKey = keyStore().getKey(KEY_ALIAS, null) as SecretKey

    private fun createKey() {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 0 segundos = cada uso de la llave exige una huella nueva
            builder.setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
        } else {
            @Suppress("DEPRECATION")
            builder.setUserAuthenticationValidityDurationSeconds(-1)
        }

        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(builder.build())
            generateKey()
        }
    }

    private fun file(ctx: Context) = File(ctx.noBackupFilesDir, FILE_NAME)

    private fun writeFile(ctx: Context, iv: ByteArray, ct: ByteArray) {
        val json = JSONObject()
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("ct", Base64.encodeToString(ct, Base64.NO_WRAP))
            .toString()
            .toByteArray(Charsets.UTF_8)
        val af = AtomicFile(file(ctx))
        val out = af.startWrite()
        try {
            out.write(json)
            af.finishWrite(out)
        } catch (e: Exception) {
            af.failWrite(out)
            throw e
        }
    }

    private fun readFile(ctx: Context): Pair<ByteArray, ByteArray>? {
        if (!file(ctx).exists()) return null
        return runCatching {
            val o = JSONObject(String(AtomicFile(file(ctx)).readFully(), Charsets.UTF_8))
            Base64.decode(o.getString("iv"), Base64.NO_WRAP) to Base64.decode(o.getString("ct"), Base64.NO_WRAP)
        }.getOrNull()
    }
}
