package com.savepoint.autofill

import android.os.Build
import android.os.CancellationSignal
import android.service.autofill.AutofillService
import android.service.autofill.FillCallback
import android.service.autofill.FillRequest
import android.service.autofill.SaveCallback
import android.service.autofill.SaveRequest
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

    /** Guardar credenciales nuevas desde otras apps queda para una siguiente versión. */
    override fun onSaveRequest(request: SaveRequest, callback: SaveCallback) {
        callback.onSuccess()
    }
}
