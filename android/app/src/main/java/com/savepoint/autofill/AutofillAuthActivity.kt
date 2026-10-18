package com.savepoint.autofill

import android.app.Activity
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.autofill.AutofillManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.savepoint.vault.BioResult
import com.savepoint.vault.BiometricVault
import com.savepoint.vault.VaultException
import com.savepoint.vault.VaultStore
import java.util.concurrent.Executors

/**
 * Pantalla transparente que aparece cuando el usuario toca "Desbloquear savePoint" en el
 * menú de autocompletado. Desbloquea (huella o contraseña maestra) y le devuelve a Android
 * las sugerencias para ese campo.
 */
@RequiresApi(Build.VERSION_CODES.O)
class AutofillAuthActivity : AppCompatActivity() {

    private var fields: AutofillFields? = null
    private val worker = Executors.newSingleThreadExecutor()
    private var finished = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        fields = AutofillFields.readFrom(intent)
        if (fields == null) return cancel()

        when {
            VaultStore.isUnlocked() -> deliver()
            BiometricVault.isEnabled(this) && BiometricVault.isAvailable(this) -> askBiometric()
            else -> askPassword(null)
        }
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    private fun askBiometric() {
        BiometricVault.unlock(this, negative = "Usar contraseña") { result ->
            when (result) {
                BioResult.Success -> deliver()
                is BioResult.Cancelled -> if (result.usePassword) askPassword(null) else cancel()
                BioResult.Invalidated -> askPassword("Las huellas del teléfono cambiaron. Usa tu contraseña maestra.")
                is BioResult.Failed -> askPassword(result.message)
            }
        }
    }

    private fun askPassword(message: String?) {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            hint = "Contraseña maestra"
            // Que el propio servicio no intente autocompletar este campo
            importantForAutofill = android.view.View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle("Desbloquear savePoint")
            .setMessage(message)
            .setView(container)
            .setPositiveButton("Desbloquear", null)
            .setNegativeButton("Cancelar") { _, _ -> cancel() }
            .setOnCancelListener { cancel() }
            .create()

        dialog.setOnShowListener {
            val ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            ok.setOnClickListener {
                val pw = input.text.toString()
                if (pw.isEmpty()) return@setOnClickListener
                ok.isEnabled = false
                input.isEnabled = false
                worker.execute {
                    val error = try {
                        VaultStore.unlock(this, pw.toCharArray())
                        null
                    } catch (e: VaultException) {
                        if (e.code == "WRONG_PASSWORD") "Contraseña incorrecta" else e.message
                    } catch (e: Exception) {
                        "No se pudo desbloquear"
                    }
                    runOnUiThread {
                        if (error == null) {
                            input.text.clear()
                            dialog.dismiss()
                            deliver()
                        } else {
                            ok.isEnabled = true
                            input.isEnabled = true
                            input.text.clear()
                            input.error = error
                        }
                    }
                }
            }
        }
        dialog.show()
    }

    /** Arma las sugerencias y se las entrega a Android. */
    private fun deliver() {
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
        finished = true
        setResult(Activity.RESULT_OK, Intent().putExtra(AutofillManager.EXTRA_AUTHENTICATION_RESULT, response))
        finish()
    }

    private fun cancel() {
        if (finished) return
        finished = true
        setResult(Activity.RESULT_CANCELED)
        finish()
    }
}
