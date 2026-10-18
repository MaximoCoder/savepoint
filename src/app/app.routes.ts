import { Routes } from '@angular/router';
import { unlockedGuard } from './core/unlocked.guard';

export const routes: Routes = [
  { path: '', redirectTo: 'vault', pathMatch: 'full' },
  {
    path: 'lock',
    loadComponent: () => import('./pages/lock/lock.page').then((m) => m.LockPage),
  },
  {
    path: 'vault',
    canActivate: [unlockedGuard],
    loadComponent: () => import('./pages/vault/vault.page').then((m) => m.VaultPage),
  },
  {
    path: 'entry/new',
    canActivate: [unlockedGuard],
    loadComponent: () => import('./pages/entry/entry.page').then((m) => m.EntryPage),
  },
  {
    path: 'entry/:id',
    canActivate: [unlockedGuard],
    loadComponent: () => import('./pages/entry/entry.page').then((m) => m.EntryPage),
  },
  {
    path: 'settings',
    canActivate: [unlockedGuard],
    loadComponent: () => import('./pages/settings/settings.page').then((m) => m.SettingsPage),
  },
  { path: '**', redirectTo: 'vault' },
];
