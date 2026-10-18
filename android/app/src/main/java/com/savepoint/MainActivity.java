package com.savepoint;

import android.os.Bundle;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;
import com.savepoint.vault.SecureClipboard;
import com.savepoint.vault.VaultPlugin;
import com.savepoint.vault.VaultStore;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // Los plugins propios se registran antes de super.onCreate
        registerPlugin(VaultPlugin.class);
        super.onCreate(savedInstanceState);

        // Bloquea capturas de pantalla, grabación y la vista previa en "apps recientes"
        getWindow().setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        );
    }

    @Override
    public void onResume() {
        super.onResume();
        VaultStore.INSTANCE.setExternalActivityExpected(false);
        // Respaldo: si una contraseña copiada ya venció y no se borró, se borra al volver
        SecureClipboard.INSTANCE.clearIfExpired(this);
    }

    @Override
    public void onStop() {
        super.onStop();
        // "Bloquear al salir": la bóveda se cierra en cuanto la app deja de verse,
        // salvo que nosotros mismos hayamos abierto una pantalla del sistema (selector de archivos, ajustes).
        if (!isChangingConfigurations() && VaultStore.INSTANCE.shouldLockOnLeave()) {
            VaultStore.INSTANCE.lock();
        }
    }
}
