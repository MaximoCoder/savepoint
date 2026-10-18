package com.savepoint.autofill

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
import android.widget.Toast
import androidx.annotation.RequiresApi
import com.savepoint.vault.VaultStore

/**
 * Servicio de autocompletado del sistema. Android lo llama cuando el usuario toca un campo
 * de usuario o contraseña en cualquier app. El valor se escribe directo en el campo:
 * no pasa por el portapapeles ni por el teclado.
 *
 * No ofrece sugerencias "inline" en el teclado a propósito: así el teclado ni siquiera ve
 * los nombres de las cuentas; se usa el menú desplegable del sistema.
 */
@RequiresApi(Build.VERSION_CODES.O)
class SavePointAutofillService : AutofillService() {

    override fun onFillRequest(
        request: FillRequest,
        cancellationSignal: CancellationSignal,
        callback: FillCallback,
    ) {
        try {
            val structure = request.fillContexts.lastOrNull()?.structure
            if (structure == null || !VaultStore.exists(this)) {
                callback.onSuccess(null)
                return
            }

            val fields = StructureParser.parse(structure)
            // Nunca autocompletar dentro de savePoint ni en pantallas sin campos de acceso
            if (fields.isEmpty || fields.packageName == packageName) {
                callback.onSuccess(null)
                return
            }

            val response = if (VaultStore.isUnlocked()) {
                AutofillResponses.datasets(this, fields)
            } else {
                AutofillResponses.locked(this, fields)
            }
            callback.onSuccess(response)
        } catch (e: Exception) {
            // Si algo falla, no sugerir nada antes que romper la app que lo pidió
            callback.onSuccess(null)
        }
    }

    /**
     * El usuario aceptó "Guardar en savePoint" después de iniciar sesión en otra app.
     * Con la bóveda abierta se guarda directo; si está bloqueada, se abre el desbloqueo.
     */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        try {
            val credential = CredentialExtractor.extract(request.fillContexts)
            if (credential == null || credential.packageName == packageName) {
                callback.onFailure("No se encontró una contraseña para guardar")
                return
            }
            if (!VaultStore.exists(this)) {
                callback.onFailure("Primero crea tu bóveda en savePoint")
                return
            }

            if (VaultStore.isUnlocked()) {
                val outcome = CredentialSaver.save(this, credential)
                Toast.makeText(this, CredentialSaver.message(outcome), Toast.LENGTH_SHORT).show()
                callback.onSuccess()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // La credencial se queda en memoria; la actividad solo recibe la orden de desbloquear
                PendingSave.put(credential)
                val intent = Intent(this, AutofillSaveActivity::class.java)
                val flags = PendingIntent.FLAG_CANCEL_CURRENT or PendingIntent.FLAG_IMMUTABLE
                val sender = PendingIntent.getActivity(this, 0, intent, flags).intentSender
                callback.onSuccess(sender)
            } else {
                callback.onFailure("Abre savePoint y vuelve a iniciar sesión para guardar")
            }
        } catch (e: Exception) {
            callback.onFailure("No se pudo guardar en savePoint")
        }
    }
}
