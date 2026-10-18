package com.savepoint;

import android.os.Bundle;
import android.view.WindowManager;

import com.getcapacitor.BridgeActivity;
import com.savepoint.vault.VaultPlugin;

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
}