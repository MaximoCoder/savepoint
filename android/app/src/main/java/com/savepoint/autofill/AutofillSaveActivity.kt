package com.savepoint.autofill

import android.app.Activity
import android.os.Build
import android.widget.Toast
import androidx.annotation.RequiresApi

/**
 * Aparece cuando el usuario acepta "Guardar en savePoint" y la bóveda está bloqueada.
 * Desbloquea y guarda la credencial que quedó en [PendingSave].
 */
@RequiresApi(Build.VERSION_CODES.O)
class AutofillSaveActivity : BaseUnlockActivity() {

    override val promptTitle = "Desbloquea para guardar"

    override fun onUnlocked() {
        val credential = PendingSave.take()
        if (credential == null) {
            Toast.makeText(this, "Se agotó el tiempo para guardar. Vuelve a iniciar sesión.", Toast.LENGTH_LONG).show()
            return cancel()
        }
        val message = try {
            CredentialSaver.message(CredentialSaver.save(this, credential))
        } catch (e: Exception) {
            "No se pudo guardar en savePoint"
        }
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        finishWith(Activity.RESULT_OK)
    }
}
