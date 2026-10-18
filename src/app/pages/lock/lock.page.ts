import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonIcon, NavController } from '../../core/ionic';
import { addIcons } from 'ionicons';
import { cloudDownloadOutline, eyeOffOutline, eyeOutline, fingerPrint, lockClosed, warningOutline } from 'ionicons/icons';
import { BackupInfo, Vault } from '../../plugins/vault';
import { vaultErrorCode } from '../../core/vault-session.service';
import { passwordStrength } from '../../core/password-tools';

const MIN_LENGTH = 12;

@Component({
  selector: 'app-lock',
  templateUrl: './lock.page.html',
  imports: [FormsModule, IonIcon],
})
export class LockPage {
  private readonly nav = inject(NavController);

  readonly minLength = MIN_LENGTH;
  readonly mode = signal<'loading' | 'create' | 'unlock' | 'restore'>('loading');
  /** Respaldo elegido en la primera pantalla, a la espera de su contraseña. */
  readonly backup = signal<BackupInfo | null>(null);
  readonly password = signal('');
  readonly confirm = signal('');
  readonly showPassword = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');
  /** La huella está activada y disponible en este teléfono. */
  readonly biometric = signal(false);

  readonly strength = computed(() => passwordStrength(this.password()));
  readonly mismatch = computed(() => this.confirm().length > 0 && this.confirm() !== this.password());
  readonly canSubmit = computed(() => {
    if (this.busy()) return false;
    if (this.mode() === 'unlock' || this.mode() === 'restore') return this.password().length > 0;
    return this.password().length >= MIN_LENGTH && this.password() === this.confirm();
  });

  constructor() {
    addIcons({ cloudDownloadOutline, lockClosed, eyeOutline, eyeOffOutline, fingerPrint, warningOutline });
  }

  async ionViewWillEnter(): Promise<void> {
    this.password.set('');
    this.confirm.set('');
    this.error.set('');
    this.showPassword.set(false);
    try {
      const { exists, unlocked } = await Vault.status();
      if (unlocked) {
        await this.nav.navigateRoot('/vault');
        return;
      }
      this.mode.set(exists ? 'unlock' : 'create');
      if (exists) await this.checkBiometric();
    } catch {
      this.mode.set('unlock');
      this.error.set('No se pudo leer la bóveda.');
    }
  }

  private async checkBiometric(): Promise<void> {
    try {
      const { available, enabled } = await Vault.biometricStatus();
      this.biometric.set(available && enabled);
      // Si está activada, se ofrece de inmediato; la contraseña queda como alternativa
      if (available && enabled) await this.useBiometric();
    } catch {
      this.biometric.set(false);
    }
  }

  async useBiometric(): Promise<void> {
    if (this.busy()) return;
    this.busy.set(true);
    this.error.set('');
    try {
      await Vault.unlockWithBiometric();
      await this.nav.navigateRoot('/vault');
    } catch (e) {
      const code = vaultErrorCode(e);
      if (code === 'BIO_INVALIDATED') {
        this.biometric.set(false);
        this.error.set('Las huellas del teléfono cambiaron. Por seguridad, entra con tu contraseña y vuelve a activar la huella en Ajustes.');
      } else if (code === 'BIO_FAILED') {
        this.error.set('No se pudo usar la huella. Entra con tu contraseña maestra.');
      }
      // BIO_CANCELLED: el usuario eligió la contraseña o cerró el diálogo; no hace falta mensaje
    } finally {
      this.busy.set(false);
    }
  }

  /** Primera vez en este teléfono: en lugar de crear una bóveda, recuperar una desde un respaldo. */
  async startRestore(): Promise<void> {
    this.error.set('');
    try {
      const info = await Vault.pickBackup();
      this.backup.set(info);
      this.password.set('');
      this.confirm.set('');
      this.mode.set('restore');
    } catch (e) {
      const code = vaultErrorCode(e);
      if (code === 'BACKUP_CANCELLED') return;
      this.error.set(code === 'INVALID_BACKUP' ? 'Ese archivo no es un respaldo de savePoint.' : 'No se pudo leer el archivo.');
    }
  }

  cancelRestore(): void {
    Vault.clearPendingBackup().catch(() => undefined);
    this.backup.set(null);
    this.password.set('');
    this.error.set('');
    this.mode.set('create');
  }

  formatDate(ms: number): string {
    if (!ms) return 'fecha desconocida';
    return new Intl.DateTimeFormat('es-MX', { dateStyle: 'medium' }).format(ms);
  }

  private async restore(): Promise<void> {
    this.busy.set(true);
    this.error.set('');
    try {
      await Vault.restoreBackup({ masterPassword: this.password() });
      this.password.set('');
      this.backup.set(null);
      await this.nav.navigateRoot('/vault');
    } catch (e) {
      const code = vaultErrorCode(e);
      this.error.set(
        code === 'WRONG_PASSWORD'
          ? 'Esa contraseña no abre el respaldo. Usa la contraseña maestra que tenías cuando lo exportaste.'
          : code === 'INVALID_BACKUP'
            ? 'El respaldo está dañado o no es válido.'
            : 'No se pudo restaurar el respaldo.',
      );
      this.password.set('');
    } finally {
      this.busy.set(false);
    }
  }

  async submit(): Promise<void> {
    if (!this.canSubmit()) return;
    if (this.mode() === 'restore') return this.restore();
    this.busy.set(true);
    this.error.set('');
    try {
      const masterPassword = this.password();
      if (this.mode() === 'create') {
        await Vault.create({ masterPassword });
      } else {
        await Vault.unlock({ masterPassword });
      }
      this.password.set('');
      this.confirm.set('');
      await this.nav.navigateRoot('/vault');
    } catch (e) {
      const code = vaultErrorCode(e);
      this.error.set(
        code === 'WRONG_PASSWORD'
          ? 'Contraseña incorrecta. Inténtalo de nuevo.'
          : code === 'WEAK_PASSWORD'
            ? `Usa al menos ${MIN_LENGTH} caracteres.`
            : 'Algo salió mal. Inténtalo de nuevo.',
      );
      if (code === 'WRONG_PASSWORD') this.password.set('');
    } finally {
      this.busy.set(false);
    }
  }
}
