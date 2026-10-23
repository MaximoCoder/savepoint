import { Component, ElementRef, computed, inject, signal, viewChild } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ActivatedRoute } from '@angular/router';
import { IonIcon, NavController } from '../../core/ionic';
import { addIcons } from 'ionicons';
import {
  arrowBack,
  copyOutline,
  createOutline,
  eyeOffOutline,
  eyeOutline,
  globeOutline,
  optionsOutline,
  refreshOutline,
  sparklesOutline,
  trashOutline,
} from 'ionicons/icons';
import { Vault, VaultEntry } from '../../plugins/vault';
import { ToastService } from '../../core/toast.service';
import { VaultSession } from '../../core/vault-session.service';
import { displayHost, generatePassword, passwordStrength } from '../../core/password-tools';

type Mode = 'loading' | 'view' | 'edit';

@Component({
  selector: 'app-entry',
  templateUrl: './entry.page.html',
  imports: [FormsModule, IonIcon],
})
export class EntryPage {
  private readonly route = inject(ActivatedRoute);
  private readonly nav = inject(NavController);
  private readonly toast = inject(ToastService);
  private readonly session = inject(VaultSession);

  private readonly deleteDialog = viewChild<ElementRef<HTMLDialogElement>>('deleteDialog');

  readonly id = signal<string | null>(null);
  readonly mode = signal<Mode>('loading');
  readonly entry = signal<VaultEntry | null>(null);
  readonly showPassword = signal(false);
  readonly saving = signal(false);

  // Formulario
  readonly title = signal('');
  readonly username = signal('');
  readonly password = signal('');
  readonly url = signal('');
  readonly notes = signal('');

  // Generador
  readonly showGenerator = signal(false);
  readonly genLength = signal(20);
  readonly genDigits = signal(true);
  readonly genSymbols = signal(true);

  readonly isNew = computed(() => this.id() === null);
  readonly strength = computed(() => passwordStrength(this.password()));
  readonly canSave = computed(() => this.title().trim().length > 0 && !this.saving());
  readonly headerTitle = computed(() => {
    if (this.mode() === 'edit') return this.isNew() ? 'Nueva contraseña' : 'Editar';
    return this.entry()?.title ?? '';
  });

  constructor() {
    addIcons({
      arrowBack,
      copyOutline,
      createOutline,
      eyeOutline,
      eyeOffOutline,
      globeOutline,
      optionsOutline,
      refreshOutline,
      sparklesOutline,
      trashOutline,
    });
  }

  async ionViewWillEnter(): Promise<void> {
    const id = this.route.snapshot.paramMap.get('id');
    this.id.set(id);
    if (!id) {
      this.resetForm();
      this.mode.set('edit');
      return;
    }
    await this.load(id);
  }

  /** Al salir de la pantalla no dejamos la contraseña en memoria del componente. */
  ionViewWillLeave(): void {
    this.entry.set(null);
    this.resetForm();
    this.showPassword.set(false);
  }

  private async load(id: string): Promise<void> {
    try {
      const { entry } = await Vault.get({ id });
      this.entry.set(entry);
      this.mode.set('view');
    } catch (e) {
      await this.session.handleError(e, 'No se pudo abrir la entrada.');
      if (this.mode() === 'loading') this.back();
    }
  }

  private resetForm(): void {
    this.title.set('');
    this.username.set('');
    this.password.set('');
    this.url.set('');
    this.notes.set('');
    this.showGenerator.set(false);
  }

  initial(title: string): string {
    return (title.trim()[0] ?? '?').toUpperCase();
  }

  host(url: string): string {
    return displayHost(url);
  }

  back(): void {
    this.nav.navigateBack('/vault');
  }

  edit(): void {
    const e = this.entry();
    if (!e) return;
    this.title.set(e.title);
    this.username.set(e.username);
    this.password.set(e.password);
    this.url.set(e.url);
    this.notes.set(e.notes);
    this.showPassword.set(false);
    this.mode.set('edit');
  }

  cancel(): void {
    if (this.isNew()) {
      this.back();
      return;
    }
    this.resetForm();
    this.showPassword.set(false);
    this.mode.set('view');
  }

  generate(): void {
    this.password.set(
      generatePassword({ length: this.genLength(), digits: this.genDigits(), symbols: this.genSymbols() }),
    );
    this.showPassword.set(true);
  }

  async save(): Promise<void> {
    if (!this.canSave()) return;
    this.saving.set(true);
    try {
      const { id } = await Vault.save({
        entry: {
          id: this.id() ?? undefined,
          title: this.title().trim(),
          username: this.username().trim(),
          password: this.password(),
          url: this.url().trim(),
          notes: this.notes(),
        },
      });
      if (this.isNew()) {
        this.toast.show('Contraseña guardada', 'success');
        this.back();
        return;
      }
      this.toast.show('Cambios guardados', 'success');
      this.resetForm();
      await this.load(id);
    } catch (e) {
      await this.session.handleError(e, 'No se pudo guardar.');
    } finally {
      this.saving.set(false);
    }
  }

  async copy(field: 'username' | 'password'): Promise<void> {
    const id = this.id();
    if (!id) return;
    try {
      const { clearsInSeconds, protected: isProtected } = await Vault.copyField({ id, field });
      if (field === 'password' && isProtected) {
        // La sugerencia del teclado solo pegaría "••••••••"; hay que usar el menú Pegar del campo
        this.toast.show(`Contraseña copiada. Pégala manteniendo presionado el campo y tocando Pegar. Se borra en ${clearsInSeconds} s.`, 'success', 4500);
      } else {
        const label = field === 'password' ? 'Contraseña copiada' : 'Usuario copiado';
        this.toast.show(`${label}. Se borrará en ${clearsInSeconds} s.`, 'success');
      }
    } catch (e) {
      await this.session.handleError(e, 'No se pudo copiar.');
    }
  }

  askDelete(): void {
    this.deleteDialog()?.nativeElement.showModal();
  }

  closeDelete(): void {
    this.deleteDialog()?.nativeElement.close();
  }

  async confirmDelete(): Promise<void> {
    const id = this.id();
    if (!id) return;
    try {
      await Vault.delete({ id });
      this.closeDelete();
      this.toast.show('Contraseña eliminada', 'info');
      this.back();
    } catch (e) {
      this.closeDelete();
      await this.session.handleError(e, 'No se pudo eliminar.');
    }
  }

  formatDate(ms: number): string {
    if (!ms) return '';
    return new Intl.DateTimeFormat('es-MX', { dateStyle: 'medium', timeStyle: 'short' }).format(ms);
  }
}
