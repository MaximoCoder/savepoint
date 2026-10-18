import { Component, ElementRef, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { App } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { IonIcon, NavController } from '../../core/ionic';
import { addIcons } from 'ionicons';
import {
  arrowBack,
  cloudDownloadOutline,
  cloudUploadOutline,
  fingerPrint,
  flashOutline,
  lockClosedOutline,
  openOutline,
  shieldCheckmarkOutline,
  timeOutline,
} from 'ionicons/icons';
import { BackupInfo, Vault } from '../../plugins/vault';
import { ToastService } from '../../core/toast.service';
import { VaultSession, vaultErrorCode } from '../../core/vault-session.service';

@Component({
  selector: 'app-settings',
  templateUrl: './settings.page.html',
  imports: [FormsModule, IonIcon],
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

  // Respaldo
  readonly lastBackupAt = signal(0);
  readonly exporting = signal(false);
  readonly importInfo = signal<BackupInfo | null>(null);
  readonly importPassword = signal('');
  readonly importError = signal('');
  readonly importing = signal(false);
  private readonly importDialog = viewChild<ElementRef<HTMLDialogElement>>('importDialog');

  private resumeHandle?: PluginListenerHandle;

  constructor() {
    addIcons({
      arrowBack,
      cloudDownloadOutline,
      cloudUploadOutline,
      fingerPrint,
      flashOutline,
      lockClosedOutline,
      openOutline,
      shieldCheckmarkOutline,
      timeOutline,
    });
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
      const [bio, fill, backup] = await Promise.all([
        Vault.biometricStatus(),
        Vault.autofillStatus(),
        Vault.backupStatus(),
      ]);
      this.lastBackupAt.set(backup.lastBackupAt);
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

  // ---------- Respaldo ----------

  lastBackupLabel(): string {
    const at = this.lastBackupAt();
    if (!at) return 'Nunca has exportado un respaldo.';
    const days = Math.floor((Date.now() - at) / 86_400_000);
    const when = days === 0 ? 'hoy' : days === 1 ? 'ayer' : `hace ${days} días`;
    return `Último respaldo: ${when}.`;
  }

  async exportBackup(): Promise<void> {
    if (this.exporting()) return;
    this.exporting.set(true);
    try {
      const { entryCount } = await Vault.exportBackup();
      this.lastBackupAt.set(Date.now());
      this.toast.show(`Respaldo guardado con ${entryCount} ${entryCount === 1 ? 'contraseña' : 'contraseñas'}`, 'success');
    } catch (e) {
      const code = vaultErrorCode(e);
      if (code === 'BACKUP_CANCELLED') return;
      await this.session.handleError(e, 'No se pudo guardar el respaldo.');
    } finally {
      this.exporting.set(false);
    }
  }

  async pickBackup(): Promise<void> {
    try {
      const info = await Vault.pickBackup();
      this.importInfo.set(info);
      this.importPassword.set('');
      this.importError.set('');
      this.importDialog()?.nativeElement.showModal();
    } catch (e) {
      const code = vaultErrorCode(e);
      if (code === 'BACKUP_CANCELLED') return;
      this.toast.show(code === 'INVALID_BACKUP' ? 'Ese archivo no es un respaldo de savePoint' : 'No se pudo leer el archivo', 'error');
    }
  }

  async confirmImport(): Promise<void> {
    if (this.importing() || !this.importPassword()) return;
    this.importing.set(true);
    this.importError.set('');
    try {
      const r = await Vault.restoreBackup({ masterPassword: this.importPassword() });
      this.closeImport(false);
      const parts: string[] = [];
      if (r.added) parts.push(`${r.added} ${r.added === 1 ? 'nueva' : 'nuevas'}`);
      if (r.updated) parts.push(`${r.updated} ${r.updated === 1 ? 'actualizada' : 'actualizadas'}`);
      this.toast.show(parts.length ? `Respaldo importado: ${parts.join(', ')}` : 'Tu bóveda ya tenía todo lo del respaldo', 'success', 3500);
    } catch (e) {
      const code = vaultErrorCode(e);
      if (code === 'WRONG_PASSWORD') {
        this.importError.set('Esa contraseña no abre el respaldo. Usa la contraseña maestra que tenías cuando lo exportaste.');
        this.importPassword.set('');
      } else if (code === 'INVALID_BACKUP') {
        this.importError.set('El respaldo está dañado o no es válido.');
      } else {
        this.closeImport();
        await this.session.handleError(e, 'No se pudo importar el respaldo.');
      }
    } finally {
      this.importing.set(false);
    }
  }

  closeImport(clear = true): void {
    this.importDialog()?.nativeElement.close();
    this.importPassword.set('');
    this.importInfo.set(null);
    if (clear) Vault.clearPendingBackup().catch(() => undefined);
  }

  formatDate(ms: number): string {
    if (!ms) return 'fecha desconocida';
    return new Intl.DateTimeFormat('es-MX', { dateStyle: 'medium', timeStyle: 'short' }).format(ms);
  }

  back(): void {
    this.nav.navigateBack('/vault');
  }

  lockNow(): void {
    this.session.lock();
  }
}
