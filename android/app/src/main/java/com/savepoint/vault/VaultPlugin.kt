package com.savepoint.vault

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin

/**
 * Puente entre Angular y VaultStore. No contiene lógica criptográfica.
 * Capacitor ejecuta estos métodos fuera del hilo de UI, así que Argon2 no congela la pantalla.
 */
@CapacitorPlugin(name = "Vault")
class VaultPlugin : Plugin() {

    @PluginMethod
    fun status(call: PluginCall) = guard(call) {
        call.resolve(JSObject().apply {
            put("exists", VaultStore.exists(context))
            put("unlocked", VaultStore.isUnlocked())
        })
    }

    @PluginMethod
    fun create(call: PluginCall) = guard(call) {
        val pw = call.getString("masterPassword")
            ?: return@guard call.reject("Falta la contraseña maestra", "INVALID")
        VaultStore.create(context, pw.toCharArray())
        call.resolve()
    }

    @PluginMethod
    fun unlock(call: PluginCall) = guard(call) {
        val pw = call.getString("masterPassword")
            ?: return@guard call.reject("Falta la contraseña maestra", "INVALID")
        VaultStore.unlock(context, pw.toCharArray())
        call.resolve()
    }

    @PluginMethod
    fun lock(call: PluginCall) = guard(call) {
        VaultStore.lock()
        call.resolve()
    }

    @PluginMethod
    fun list(call: PluginCall) = guard(call) {
        val arr = JSArray()
        VaultStore.list(context).forEach { e ->
            arr.put(JSObject().apply {
                put("id", e.id)
                put("title", e.title)
                put("username", e.username)
                put("url", e.url)
                put("updatedAt", e.updatedAt)
            })
        }
        call.resolve(JSObject().put("entries", arr))
    }

    @PluginMethod
    fun get(call: PluginCall) = guard(call) {
        val id = call.getString("id") ?: return@guard call.reject("Falta el id", "INVALID")
        val e = VaultStore.get(context, id)
        call.resolve(JSObject().put("entry", JSObject().apply {
            put("id", e.id)
            put("title", e.title)
            put("username", e.username)
            put("password", e.password)
            put("url", e.url)
            put("notes", e.notes)
            put("updatedAt", e.updatedAt)
        }))
    }

    @PluginMethod
    fun save(call: PluginCall) = guard(call) {
        val o = call.getObject("entry") ?: return@guard call.reject("Falta la entrada", "INVALID")
        val title = o.optString("title").trim()
        if (title.isEmpty()) return@guard call.reject("El título es obligatorio", "INVALID")
        val id = VaultStore.save(
            context,
            VaultEntry(
                id = o.optString("id"),
                title = title,
                username = o.optString("username"),
                password = o.optString("password"),
                url = o.optString("url"),
                notes = o.optString("notes"),
                updatedAt = 0L
            )
        )
        call.resolve(JSObject().put("id", id))
    }

    /** Copia usuario o contraseña directo desde la bóveda, sin pasar el valor por JavaScript. */
    @PluginMethod
    fun copyField(call: PluginCall) = guard(call) {
        val id = call.getString("id") ?: return@guard call.reject("Falta el id", "INVALID")
        val field = call.getString("field") ?: return@guard call.reject("Falta el campo", "INVALID")
        val e = VaultStore.get(context, id)
        val value = when (field) {
            "username" -> e.username
            "password" -> e.password
            else -> return@guard call.reject("Campo no válido", "INVALID")
        }
        SecureClipboard.copy(context, value, sensitive = field == "password")
        call.resolve(JSObject().put("clearsInSeconds", SecureClipboard.CLEAR_AFTER_SECONDS))
    }

    @PluginMethod
    fun delete(call: PluginCall) = guard(call) {
        val id = call.getString("id") ?: return@guard call.reject("Falta el id", "INVALID")
        VaultStore.delete(context, id)
        call.resolve()
    }

    // ---------- Huella ----------

    @PluginMethod
    fun biometricStatus(call: PluginCall) = guard(call) {
        call.resolve(JSObject().apply {
            put("available", BiometricVault.isAvailable(context))
            put("enabled", BiometricVault.isEnabled(context))
        })
    }

    @PluginMethod
    fun enableBiometric(call: PluginCall) = guard(call) {
        if (!VaultStore.isUnlocked()) return@guard call.reject("La bóveda está bloqueada", "LOCKED")
        if (!BiometricVault.isAvailable(context)) {
            return@guard call.reject("No hay huellas registradas en el teléfono", "BIO_UNAVAILABLE")
        }
        activity.runOnUiThread {
            BiometricVault.enable(activity) { result -> resolveBio(call, result) }
        }
    }

    @PluginMethod
    fun disableBiometric(call: PluginCall) = guard(call) {
        BiometricVault.disable(context)
        call.resolve()
    }

    @PluginMethod
    fun unlockWithBiometric(call: PluginCall) = guard(call) {
        if (!BiometricVault.isEnabled(context)) {
            return@guard call.reject("El desbloqueo con huella no está activado", "BIO_NOT_ENABLED")
        }
        activity.runOnUiThread {
            BiometricVault.unlock(activity, negative = "Usar contraseña") { result -> resolveBio(call, result) }
        }
    }

    private fun resolveBio(call: PluginCall, result: BioResult) {
        when (result) {
            BioResult.Success -> call.resolve()
            is BioResult.Cancelled -> call.reject("Cancelado", "BIO_CANCELLED")
            BioResult.Invalidated -> call.reject("Las huellas del teléfono cambiaron", "BIO_INVALIDATED")
            is BioResult.Failed -> call.reject(result.message, "BIO_FAILED")
        }
    }

    // ---------- Autocompletado ----------

    @PluginMethod
    fun autofillStatus(call: PluginCall) = guard(call) {
        val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
        val enabled = supported &&
            context.getSystemService(AutofillManager::class.java)?.hasEnabledAutofillServices() == true
        call.resolve(JSObject().apply {
            put("supported", supported)
            put("enabled", enabled)
        })
    }

    /** Abre el selector del sistema para elegir savePoint como servicio de autocompletado. */
    @PluginMethod
    fun openAutofillSettings(call: PluginCall) = guard(call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return@guard call.reject("Requiere Android 8 o superior", "UNSUPPORTED")
        }
        val picker = Intent(Settings.ACTION_REQUEST_SET_AUTOFILL_SERVICE)
            .setData(Uri.parse("package:${context.packageName}"))
        try {
            activity.startActivity(picker)
        } catch (e: Exception) {
            // Algunas capas de fabricante no exponen el selector directo
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
        call.resolve()
    }

    private inline fun guard(call: PluginCall, block: () -> Unit) {
        try {
            block()
        } catch (e: VaultException) {
            call.reject(e.message, e.code)
        } catch (e: Exception) {
            call.reject("Error interno de la bóveda", "INTERNAL", e)
        }
    }
}
