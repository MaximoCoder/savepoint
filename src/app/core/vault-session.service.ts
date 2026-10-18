import { Injectable, inject } from '@angular/core';
import { NavController } from './ionic';
import { Vault } from '../plugins/vault';
import { ToastService } from './toast.service';

/** Código de error que devuelve el plugin nativo (CapacitorException.code). */
export function vaultErrorCode(e: unknown): string {
  return (e as { code?: string } | null)?.code ?? 'INTERNAL';
}

/**
 * Manejo común de la sesión: bloquear, salir a la pantalla de bloqueo y
 * traducir errores del plugin a mensajes para el usuario.
 */
@Injectable({ providedIn: 'root' })
export class VaultSession {
  private readonly nav = inject(NavController);
  private readonly toast = inject(ToastService);

  async lock(): Promise<void> {
    try {
      await Vault.lock();
    } finally {
      await this.nav.navigateRoot('/lock', { animationDirection: 'back' });
    }
  }

  /** Si la bóveda se bloqueó (por inactividad), regresa a la pantalla de bloqueo. */
  async handleError(e: unknown, fallback = 'Algo salió mal. Inténtalo de nuevo.'): Promise<void> {
    const code = vaultErrorCode(e);
    if (code === 'LOCKED' || code === 'NO_VAULT') {
      this.toast.show('La bóveda se bloqueó por inactividad', 'info');
      await this.nav.navigateRoot('/lock', { animationDirection: 'back' });
      return;
    }
    this.toast.show(fallback, 'error');
  }
}
