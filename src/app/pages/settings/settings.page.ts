import { Component, inject, signal } from '@angular/core';
import { App } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { IonIcon, NavController } from '../../core/ionic';
import { addIcons } from 'ionicons';
import { arrowBack, fingerPrint, flashOutline, lockClosedOutline, openOutline, timeOutline } from 'ionicons/icons';
import { Vault } from '../../plugins/vault';
import { ToastService } from '../../core/toast.service';
import { VaultSession, vaultErrorCode } from '../../core/vault-session.service';

@Component({
  selector: 'app-settings',
  templateUrl: './settings.page.html',
  imports: [IonIcon],
})
export class SettingsPage {
  private readonly nav = inject(NavController);
  private readonly toast = inject(ToastService);
  private readonly session = inject(VaultSession);

  readonly loading = signal(true);
  readonly bioAvailable = signal(false);
  readonly bioEnabled = signal(false);
  readonly bioBusy = signal(false);
  readonly autofillSupported = signal(true);
  readonly autofillEnabled = signal(false);

  private resumeHandle?: PluginListenerHandle;

  constructor() {
    addIcons({ arrowBack, fingerPrint, flashOutline, lockClosedOutline, openOutline, timeOutline });
  }

  async ionViewWillEnter(): Promise<void> {
    await this.refresh();
    // Al volver de los ajustes del sistema, actualizar el estado del autocompletado
    this.resumeHandle = await App.addListener('resume', () => this.refresh());
  }

  ionViewWillLeave(): void {
    this.resumeHandle?.remove();
    this.resumeHandle = undefined;
  }

  private async refresh(): Promise<void> {
    try {
      const [bio, fill] = await Promise.all([Vault.biometricStatus(), Vault.autofillStatus()]);
      this.bioAvailable.set(bio.available);
      this.bioEnabled.set(bio.enabled);
      this.autofillSupported.set(fill.supported);
      this.autofillEnabled.set(fill.enabled);
    } catch (e) {
      await this.session.handleError(e, 'No se pudieron leer los ajustes.');
    } finally {
      this.loading.set(false);
    }
  }

  async toggleBiometric(): Promise<void> {
    if (this.bioBusy()) return;
    this.bioBusy.set(true);
    try {
      if (this.bioEnabled()) {
        await Vault.disableBiometric();
        this.bioEnabled.set(false);
        this.toast.show('Desbloqueo con huella desactivado', 'info');
      } else {
        await Vault.enableBiometric();
        this.bioEnabled.set(true);
        this.toast.show('Listo, ya puedes abrir savePoint con tu huella', 'success');
      }
    } catch (e) {
      const code = vaultErrorCode(e);
      if (code === 'BIO_CANCELLED') return;
      if (code === 'LOCKED') {
        await this.session.handleError(e);
        return;
      }
      this.toast.show(
        code === 'BIO_UNAVAILABLE'
          ? 'Primero registra una huella en los ajustes del teléfono'
          : 'No se pudo activar la huella',
        'error',
      );
    } finally {
      this.bioBusy.set(false);
    }
  }

  async openAutofill(): Promise<void> {
    try {
      await Vault.openAutofillSettings();
    } catch {
      this.toast.show('No se pudieron abrir los ajustes del sistema', 'error');
    }
  }

  back(): void {
    this.nav.navigateBack('/vault');
  }

  lockNow(): void {
    this.session.lock();
  }
}
