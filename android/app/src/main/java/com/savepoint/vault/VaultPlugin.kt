package com.savepoint.vault

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.autofill.AutofillManager
import androidx.activity.result.ActivityResult
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Puente entre Angular y VaultStore. No contiene lógica criptográfica.
 * Capacitor ejecuta estos métodos fuera del hilo de UI, así que Argon2 no congela la pantalla.
 */
@CapacitorPlugin(name = "Vault")
class VaultPlugin : Plugin() {

    override fun load() {
        VaultStore.loadSettings(context)
    }

    @PluginMethod
    fun status(call: PluginCall) = guard(call) {
        call.resolve(JSObject().apply {
            put("exists", VaultStore.exists(context))
            put("unlocked", VaultStore.isUnlocked())
            put("retryAfterMs", VaultStore.retryAfterMs(context))
            put("failedAttempts", VaultStore.failedAttempts(context))
        })
    }

    @PluginMethod
    fun changeMasterPassword(call: PluginCall) = guard(call) {
        val current = call.getString("currentPassword")
            ?: return@guard call.reject("Falta la contraseña actual", "INVALID")
        val next = call.getString("newPassword")
            ?: return@guard call.reject("Falta la contraseña nueva", "INVALID")
        VaultStore.changeMasterPassword(context, current.toCharArray(), next.toCharArray())
        call.resolve()
    }

    @PluginMethod
    fun getLockSettings(call: PluginCall) = guard(call) {
        call.resolve(JSObject().put("autoLockMinutes", VaultStore.autoLockMinutes))
    }

    @PluginMethod
    fun setAutoLock(call: PluginCall) = guard(call) {
        val minutes = call.getInt("minutes") ?: return@guard call.reject("Falta el tiempo", "INVALID")
        VaultStore.setAutoLockMinutes(context, minutes)
        call.resolve()
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
        VaultStore.externalActivityExpected = true
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

    // ---------- Respaldo ----------

    /** Respaldo elegido por el usuario, a la espera de su contraseña maestra. Siempre está cifrado. */
    private var pendingBackup: ByteArray? = null

    @PluginMethod
    fun backupStatus(call: PluginCall) = guard(call) {
        call.resolve(JSObject().put("lastBackupAt", backupPrefs().getLong(KEY_LAST_BACKUP, 0L)))
    }

    /** Abre el selector del sistema para elegir dónde guardar (Descargas, Drive, etc.). */
    @PluginMethod
    fun exportBackup(call: PluginCall) = guard(call) {
        if (!VaultStore.isUnlocked()) return@guard call.reject("La bóveda está bloqueada", "LOCKED")
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "savepoint-respaldo-$date.json")
        }
        VaultStore.externalActivityExpected = true
        startActivityForResult(call, intent, "onExportLocationPicked")
    }

    @ActivityCallback
    private fun onExportLocationPicked(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Cancelado", "BACKUP_CANCELLED")
            return
        }
        guard(call) {
            val bytes = VaultStore.exportBackup(context)
            val out = context.contentResolver.openOutputStream(uri)
                ?: throw VaultException("IO", "No se pudo escribir el archivo")
            out.use { it.write(bytes) }
            backupPrefs().edit().putLong(KEY_LAST_BACKUP, System.currentTimeMillis()).apply()
            call.resolve(JSObject().put("entryCount", VaultStore.inspectBackup(bytes).entryCount))
        }
    }

    /** Abre el selector de archivos para elegir un respaldo. No lo aplica todavía. */
    @PluginMethod
    fun pickBackup(call: PluginCall) = guard(call) {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "application/octet-stream", "text/plain"))
        }
        VaultStore.externalActivityExpected = true
        startActivityForResult(call, intent, "onBackupPicked")
    }

    @ActivityCallback
    private fun onBackupPicked(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val uri = result.data?.data
        if (result.resultCode != Activity.RESULT_OK || uri == null) {
            call.reject("Cancelado", "BACKUP_CANCELLED")
            return
        }
        guard(call) {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw VaultException("IO", "No se pudo leer el archivo")
            val bytes = input.use { readLimited(it) }
            val info = VaultStore.inspectBackup(bytes)
            pendingBackup = bytes
            call.resolve(JSObject().apply {
                put("exportedAt", info.exportedAt)
                put("entryCount", info.entryCount)
                put("hasVault", VaultStore.exists(context))
            })
        }
    }

    /**
     * Aplica el respaldo elegido con su contraseña maestra.
     * Sin bóveda: lo instala como bóveda. Con bóveda abierta: combina las entradas.
     */
    @PluginMethod
    fun restoreBackup(call: PluginCall) = guard(call) {
        val bytes = pendingBackup ?: return@guard call.reject("Primero elige un respaldo", "NO_BACKUP")
        val pw = call.getString("masterPassword")
            ?: return@guard call.reject("Falta la contraseña", "INVALID")
        val res = JSObject()
        if (!VaultStore.exists(context)) {
            // Por si quedó una huella vinculada a una bóveda anterior
            BiometricVault.disable(context)
            val added = VaultStore.restoreBackup(context, bytes, pw.toCharArray())
            res.put("mode", "restored").put("added", added).put("updated", 0).put("unchanged", 0)
        } else {
            val r = VaultStore.mergeBackup(context, bytes, pw.toCharArray())
            res.put("mode", "merged").put("added", r.added).put("updated", r.updated).put("unchanged", r.unchanged)
        }
        pendingBackup = null
        call.resolve(res)
    }

    @PluginMethod
    fun clearPendingBackup(call: PluginCall) = guard(call) {
        pendingBackup = null
        call.resolve()
    }

    private fun readLimited(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > MAX_BACKUP_BYTES) {
                throw VaultException("INVALID_BACKUP", "El archivo es demasiado grande para ser un respaldo")
            }
        }
        return out.toByteArray()
    }

    private fun backupPrefs() = context.getSharedPreferences("savepoint_backup", Context.MODE_PRIVATE)

    private companion object {
        const val KEY_LAST_BACKUP = "lastBackupAt"
        const val MAX_BACKUP_BYTES = 20 * 1024 * 1024
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
