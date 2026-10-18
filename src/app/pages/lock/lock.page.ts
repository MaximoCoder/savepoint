import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonIcon, NavController } from '../../core/ionic';
import { addIcons } from 'ionicons';
import { eyeOffOutline, eyeOutline, lockClosed, warningOutline } from 'ionicons/icons';
import { Vault } from '../../plugins/vault';
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
  readonly mode = signal<'loading' | 'create' | 'unlock'>('loading');
  readonly password = signal('');
  readonly confirm = signal('');
  readonly showPassword = signal(false);
  readonly busy = signal(false);
  readonly error = signal('');

  readonly strength = computed(() => passwordStrength(this.password()));
  readonly mismatch = computed(() => this.confirm().length > 0 && this.confirm() !== this.password());
  readonly canSubmit = computed(() => {
    if (this.busy()) return false;
    if (this.mode() === 'unlock') return this.password().length > 0;
    return this.password().length >= MIN_LENGTH && this.password() === this.confirm();
  });

  constructor() {
    addIcons({ lockClosed, eyeOutline, eyeOffOutline, warningOutline });
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
    } catch {
      this.mode.set('unlock');
      this.error.set('No se pudo leer la bóveda.');
    }
  }

  async submit(): Promise<void> {
    if (!this.canSubmit()) return;
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
