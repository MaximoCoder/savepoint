import { Component, computed, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { IonIcon, NavController } from '../../core/ionic';
import { addIcons } from 'ionicons';
import { add, chevronForward, closeCircle, keyOutline, lockClosedOutline, searchOutline } from 'ionicons/icons';
import { Vault, VaultEntrySummary } from '../../plugins/vault';
import { VaultSession } from '../../core/vault-session.service';
import { displayHost, normalize } from '../../core/password-tools';

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

  readonly filtered = computed(() => {
    const q = normalize(this.query());
    if (!q) return this.entries();
    return this.entries().filter((e) => normalize(`${e.title} ${e.username} ${e.url}`).includes(q));
  });

  constructor() {
    addIcons({ add, chevronForward, closeCircle, keyOutline, lockClosedOutline, searchOutline });
  }

  async ionViewWillEnter(): Promise<void> {
    await this.load();
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
