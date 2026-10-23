import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { App } from '@capacitor/app';
import type { PluginListenerHandle } from '@capacitor/core';
import { IonIcon, NavController } from '../../core/ionic';
import { addIcons } from 'ionicons';
import {
  arrowBack,
  chevronForward,
  clipboardOutline,
  cloudDownloadOutline,
  cloudUploadOutline,
  fingerPrint,
  flashOutline,
  keyOutline,
  lockClosedOutline,
  openOutline,
  shieldCheckmarkOutline,
  timeOutline,
} from 'ionicons/icons';
import { BackupInfo, Vault } from '../../plugins/vault';
import { ToastService } from '../../core/toast.service';
import { VaultSession, vaultErrorCode } from '../../core/vault-session.service';
import { passwordStrength } from '../../core/password-tools';

const MIN_LENGTH = 12;

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
  readonly protectedCopy = signal(true);

  // Bloqueo automático
  readonly autoLockOptions = [
    { minutes: 0, label: 'Al salir' },
    { minutes: 1, label: '1 min' },
    { minutes: 5, label: '5 min' },
    { minutes: 15, label: '15 min' },
  ];
  readonly autoLockMinutes = signal(5);

  // Cambio de contraseña maestra
  readonly minLength = MIN_LENGTH;
  readonly currentPassword = signal('');
  readonly newPassword = signal('');
  readonly confirmPassword = signal('');
  readonly pwBusy = signal(false);
  readonly pwError = signal<'' | 'current' | 'other'>('');
  readonly pwErrorText = signal('');
  readonly newStrength = computed(() => passwordStrength(this.newPassword()));
  readonly pwMismatch = computed(() => this.confirmPassword().length > 0 && this.confirmPassword() !== this.newPassword());
  readonly canChangePassword = computed(
    () =>
      !this.pwBusy() &&
      this.currentPassword().length > 0 &&
      this.newPassword().length >= MIN_LENGTH &&
      this.newPassword() === this.confirmPassword(),
  );
  private readonly passwordDialog = viewChild<ElementRef<HTMLDialogElement>>('passwordDialog');

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
      chevronForward,
  clipboardOutline,
      cloudDownloadOutline,
      cloudUploadOutline,
      fingerPrint,
      flashOutline,
      keyOutline,
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
      const [bio, fill, backup, lock, clip] = await Promise.all([
        Vault.biometricStatus(),
        Vault.autofillStatus(),
        Vault.backupStatus(),
        Vault.getLockSettings(),
        Vault.getClipboardSettings(),
      ]);
      this.protectedCopy.set(clip.protectedCopy);
      this.lastBackupAt.set(backup.lastBackupAt);
      this.autoLockMinutes.set(lock.autoLockMinutes);
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

  async toggleProtectedCopy(): Promise<void> {
    const next = !this.protectedCopy();
    this.protectedCopy.set(next);
    try {
      await Vault.setProtectedCopy({ enabled: next });
    } catch {
      this.protectedCopy.set(!next);
      this.toast.show('No se pudo cambiar la copia protegida', 'error');
    }
  }

  // ---------- Bloqueo automático ----------

  autoLockHint(): string {
    const m = this.autoLockMinutes();
    if (m === 0) return 'La bóveda se cierra en cuanto sales de la app o apagas la pantalla.';
    return `La bóveda se cierra sola tras ${m} ${m === 1 ? 'minuto' : 'minutos'} sin uso.`;
  }

  async setAutoLock(minutes: number): Promise<void> {
    const previous = this.autoLockMinutes();
    if (minutes === previous) return;
    this.autoLockMinutes.set(minutes);
    try {
      await Vault.setAutoLock({ minutes });
    } catch {
      this.autoLockMinutes.set(previous);
      this.toast.show('No se pudo cambiar el bloqueo automático', 'error');
    }
  }

  // ---------- Contraseña maestra ----------

  openChangePassword(): void {
    this.resetPasswordForm();
    this.passwordDialog()?.nativeElement.showModal();
  }

  closeChangePassword(): void {
    this.passwordDialog()?.nativeElement.close();
    this.resetPasswordForm();
  }

  private resetPasswordForm(): void {
    this.currentPassword.set('');
    this.newPassword.set('');
    this.confirmPassword.set('');
    this.pwError.set('');
    this.pwErrorText.set('');
  }

  async changePassword(): Promise<void> {
    if (!this.canChangePassword()) return;
    if (this.newPassword() === this.currentPassword()) {
      this.pwError.set('other');
      this.pwErrorText.set('La contraseña nueva es igual a la actual.');
      return;
    }
    this.pwBusy.set(true);
    this.pwError.set('');
    this.pwErrorText.set('');
    try {
      await Vault.changeMasterPassword({ currentPassword: this.currentPassword(), newPassword: this.newPassword() });
      this.closeChangePassword();
      this.toast.show('Contraseña maestra cambiada. Exporta un respaldo nuevo.', 'success', 3500);
    } catch (e) {
      const code = vaultErrorCode(e);
      if (code === 'WRONG_PASSWORD') {
        this.pwError.set('current');
        this.pwErrorText.set('La contraseña actual no es correcta.');
        this.currentPassword.set('');
      } else if (code === 'WEAK_PASSWORD') {
        this.pwError.set('other');
        this.pwErrorText.set(`La contraseña nueva debe tener al menos ${MIN_LENGTH} caracteres.`);
      } else {
        this.closeChangePassword();
        await this.session.handleError(e, 'No se pudo cambiar la contraseña maestra.');
      }
    } finally {
      this.pwBusy.set(false);
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
