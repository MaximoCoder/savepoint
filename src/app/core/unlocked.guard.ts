import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';
import { Vault } from '../plugins/vault';

/** Solo deja entrar a las pantallas de la bóveda si está desbloqueada. */
export const unlockedGuard: CanActivateFn = async () => {
  const router = inject(Router);
  try {
    const { unlocked } = await Vault.status();
    return unlocked ? true : router.parseUrl('/lock');
  } catch {
    return router.parseUrl('/lock');
  }
};
