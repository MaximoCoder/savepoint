package com.savepoint.autofill

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.savepoint.vault.BioResult
import com.savepoint.vault.BiometricVault
import com.savepoint.vault.VaultException
import com.savepoint.vault.VaultStore
import java.util.concurrent.Executors

/**
 * Pantalla transparente que desbloquea la bóveda desde fuera de la app (huella o contraseña
 * maestra) y luego llama a [onUnlocked]. La usan el autocompletado y el guardado.
 */
abstract class BaseUnlockActivity : AppCompatActivity() {

    private val worker = Executors.newSingleThreadExecutor()
    private var finished = false

    /** Título del diálogo de huella/contraseña. */
    protected abstract val promptTitle: String

    /** Se llama en el hilo principal con la bóveda ya abierta. Debe terminar con [finishWith]. */
    protected abstract fun onUnlocked()

    /** Permite a la subclase abortar antes de pedir nada (por ejemplo, si faltan datos). */
    protected open fun canStart(): Boolean = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        if (!canStart()) return cancel()

        when {
            VaultStore.isUnlocked() -> onUnlocked()
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
                BioResult.Success -> onUnlocked()
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
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
        val container = FrameLayout(this).apply {
            setPadding(pad, pad / 2, pad, 0)
            addView(input)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(promptTitle)
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
                        when (e.code) {
                            "WRONG_PASSWORD" -> "Contraseña incorrecta"
                            else -> e.message // THROTTLED trae el tiempo de espera
                        }
                    } catch (e: Exception) {
                        "No se pudo desbloquear"
                    }
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        if (error == null) {
                            input.text.clear()
                            dialog.dismiss()
                            onUnlocked()
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

    protected fun finishWith(resultCode: Int, data: android.content.Intent? = null) {
        if (finished) return
        finished = true
        setResult(resultCode, data)
        finish()
    }

    protected fun cancel() = finishWith(Activity.RESULT_CANCELED)
}
