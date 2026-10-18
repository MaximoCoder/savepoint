import { registerPlugin } from '@capacitor/core';

export interface VaultEntrySummary {
  id: string;
  title: string;
  username: string;
  url: string;
  updatedAt: number;
}

export interface VaultEntry extends VaultEntrySummary {
  password: string;
  notes: string;
}

/** Para guardar: sin id crea una entrada nueva, con id la actualiza. */
export type VaultEntryInput = Omit<VaultEntry, 'id' | 'updatedAt'> & { id?: string };

/**
 * Códigos de error que puede devolver el plugin (error.code):
 * INVALID, ALREADY_EXISTS, WEAK_PASSWORD, WRONG_PASSWORD, NO_VAULT, LOCKED, NOT_FOUND, INTERNAL
 */
export interface VaultPlugin {
  status(): Promise<{ exists: boolean; unlocked: boolean }>;
  create(options: { masterPassword: string }): Promise<void>;
  unlock(options: { masterPassword: string }): Promise<void>;
  lock(): Promise<void>;
  list(): Promise<{ entries: VaultEntrySummary[] }>;
  get(options: { id: string }): Promise<{ entry: VaultEntry }>;
  save(options: { entry: VaultEntryInput }): Promise<{ id: string }>;
  delete(options: { id: string }): Promise<void>;
}

export const Vault = registerPlugin<VaultPlugin>('Vault');