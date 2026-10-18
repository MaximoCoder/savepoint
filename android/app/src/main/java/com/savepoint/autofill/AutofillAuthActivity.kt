package com.savepoint.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.view.autofill.AutofillManager
import android.widget.Toast
import androidx.annotation.RequiresApi

/**
 * Aparece cuando el usuario toca "Desbloquear savePoint" en el menú de autocompletado.
 * Desbloquea y le devuelve a Android las sugerencias para ese campo.
 */
@RequiresApi(Build.VERSION_CODES.O)
class AutofillAuthActivity : BaseUnlockActivity() {

    private var fields: AutofillFields? = null

    override val promptTitle = "Desbloquear savePoint"

    override fun canStart(): Boolean {
        fields = AutofillFields.readFrom(intent)
        return fields != null
    }

    override fun onUnlocked() {
        val f = fields ?: return cancel()
        val response = try {
            AutofillResponses.datasets(this, f)
        } catch (e: Exception) {
            null
        }
        if (response == null) {
            Toast.makeText(this, "No hay contraseñas guardadas en savePoint", Toast.LENGTH_SHORT).show()
            return cancel()
        }
        finishWith(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
    }
}
