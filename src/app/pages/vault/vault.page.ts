import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonIcon, NavController } from '../../core/ionic';
import { addIcons } from 'ionicons';
import {
  add,
  chevronForward,
  close,
  closeCircle,
  fingerPrint,
  keyOutline,
  lockClosedOutline,
  searchOutline,
  settingsOutline,
  shieldCheckmarkOutline,
} from 'ionicons/icons';
import { Vault, VaultEntrySummary } from '../../plugins/vault';
import { VaultSession } from '../../core/vault-session.service';
import { displayHost, normalize } from '../../core/password-tools';

const BIO_TIP_KEY = 'savepoint.bioTipDismissed';
const BACKUP_SNOOZE_KEY = 'savepoint.backupTipSnoozedUntil';
const BACKUP_MAX_AGE_DAYS = 30;
const DAY_MS = 86_400_000;

function readFlag(key: string): boolean {
  try {
    return localStorage.getItem(key) === '1';
  } catch {
    return false;
  }
}

function readNumber(key: string): number {
  try {
    return Number(localStorage.getItem(key) ?? 0) || 0;
  } catch {
    return 0;
  }
}

function writeNumber(key: string, value: number): void {
  try {
    localStorage.setItem(key, String(value));
  } catch {
    /* sin almacenamiento: el recordatorio volverá a aparecer */
  }
}

function writeFlag(key: string): void {
  try {
    localStorage.setItem(key, '1');
  } catch {
    /* sin almacenamiento: la sugerencia volverá a aparecer, no pasa nada */
  }
}

@Component({
  selector: 'app-vault',
  templateUrl: './vault.page.html',
  imports: [FormsModule, IonIcon],
})
export class VaultPage {
  private readonly nav = inject(NavController);
  private readonly session = inject(VaultSession);

  readonly entries = signal<VaultEntrySummary[]>([]);
  readonly query = signal('');
  readonly loading = signal(true);
  /** Sugerencia única para activar la huella (se puede descartar). */
  readonly suggestBiometric = signal(false);
  /** Recordatorio de respaldo: nunca se ha hecho o tiene más de 30 días. */
  readonly suggestBackup = signal(false);
  readonly backupNever = signal(true);

  readonly filtered = computed(() => {
    const q = normalize(this.query());
    if (!q) return this.entries();
    return this.entries().filter((e) => normalize(`${e.title} ${e.username} ${e.url}`).includes(q));
  });

  constructor() {
    addIcons({
      add,
      chevronForward,
      close,
      closeCircle,
      fingerPrint,
      keyOutline,
      lockClosedOutline,
      searchOutline,
      settingsOutline,
      shieldCheckmarkOutline,
    });
  }

  async ionViewWillEnter(): Promise<void> {
    await this.load();
    await this.checkBiometricSuggestion();
    await this.checkBackupSuggestion();
  }

  private async checkBackupSuggestion(): Promise<void> {
    if (this.entries().length === 0 || Date.now() < readNumber(BACKUP_SNOOZE_KEY)) {
      this.suggestBackup.set(false);
      return;
    }
    try {
      const { lastBackupAt } = await Vault.backupStatus();
      this.backupNever.set(!lastBackupAt);
      this.suggestBackup.set(!lastBackupAt || Date.now() - lastBackupAt > BACKUP_MAX_AGE_DAYS * DAY_MS);
    } catch {
      this.suggestBackup.set(false);
    }
  }

  snoozeBackupTip(): void {
    writeNumber(BACKUP_SNOOZE_KEY, Date.now() + 7 * DAY_MS);
    this.suggestBackup.set(false);
  }

  goBackup(): void {
    this.nav.navigateForward('/settings');
  }

  private async checkBiometricSuggestion(): Promise<void> {
    if (readFlag(BIO_TIP_KEY)) return;
    try {
      const { available, enabled } = await Vault.biometricStatus();
      this.suggestBiometric.set(available && !enabled);
    } catch {
      this.suggestBiometric.set(false);
    }
  }

  dismissBiometricTip(): void {
    writeFlag(BIO_TIP_KEY);
    this.suggestBiometric.set(false);
  }

  openSettings(): void {
    this.dismissBiometricTip();
    this.nav.navigateForward('/settings');
  }

  async load(): Promise<void> {
    try {
      const { entries } = await Vault.list();
      this.entries.set(entries);
    } catch (e) {
      await this.session.handleError(e, 'No se pudo cargar la bóveda.');
    } finally {
      this.loading.set(false);
    }
  }

  subtitle(e: VaultEntrySummary): string {
    return e.username || displayHost(e.url) || 'Sin usuario';
  }

  initial(title: string): string {
    return (title.trim()[0] ?? '?').toUpperCase();
  }

  open(id: string): void {
    this.nav.navigateForward(['/entry', id]);
  }

  add(): void {
    this.nav.navigateForward('/entry/new');
  }

  lock(): void {
    this.session.lock();
  }
}
