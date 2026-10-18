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
 * INVALID, ALREADY_EXISTS, WEAK_PASSWORD, WRONG_PASSWORD, NO_VAULT, LOCKED, NOT_FOUND, INTERNAL,
 * BIO_UNAVAILABLE, BIO_NOT_ENABLED, BIO_CANCELLED, BIO_INVALIDATED, BIO_FAILED, UNSUPPORTED,
 * BACKUP_CANCELLED, INVALID_BACKUP, NO_BACKUP
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
  /** Copia desde el lado nativo, marcado como sensible y con borrado automático. */
  copyField(options: { id: string; field: 'username' | 'password' }): Promise<{ clearsInSeconds: number }>;

  /** available: el teléfono tiene huella "fuerte" registrada. enabled: savePoint ya la usa. */
  biometricStatus(): Promise<{ available: boolean; enabled: boolean }>;
  /** Muestra el diálogo de huella y la vincula. Requiere la bóveda desbloqueada. */
  enableBiometric(): Promise<void>;
  disableBiometric(): Promise<void>;
  /** Muestra el diálogo de huella y desbloquea. */
  unlockWithBiometric(): Promise<void>;

  /** enabled: savePoint es el servicio de autocompletado activo del teléfono. */
  autofillStatus(): Promise<{ supported: boolean; enabled: boolean }>;
  /** Abre el selector del sistema para elegir savePoint como autocompletado. */
  openAutofillSettings(): Promise<void>;

  /** Fecha (ms) del último respaldo exportado desde este teléfono; 0 si nunca. */
  backupStatus(): Promise<{ lastBackupAt: number }>;
  /** Pide al usuario dónde guardar y escribe el respaldo cifrado. */
  exportBackup(): Promise<{ entryCount: number }>;
  /** Pide al usuario un archivo de respaldo y lo valida, sin aplicarlo todavía. */
  pickBackup(): Promise<BackupInfo>;
  /** Aplica el respaldo elegido: lo instala si no hay bóveda, o lo combina con la actual. */
  restoreBackup(options: { masterPassword: string }): Promise<RestoreResult>;
  clearPendingBackup(): Promise<void>;
}

export interface BackupInfo {
  exportedAt: number;
  entryCount: number;
  /** Ya hay una bóveda en el teléfono (el respaldo se combinará con ella). */
  hasVault: boolean;
}

export interface RestoreResult {
  mode: 'restored' | 'merged';
  added: number;
  updated: number;
  unchanged: number;
}

export const Vault = registerPlugin<VaultPlugin>('Vault');
