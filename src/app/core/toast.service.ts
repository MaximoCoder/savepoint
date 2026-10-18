import { Injectable, signal } from '@angular/core';

export type ToastKind = 'info' | 'success' | 'error';

export interface ToastMessage {
  text: string;
  kind: ToastKind;
}

@Injectable({ providedIn: 'root' })
export class ToastService {
  readonly message = signal<ToastMessage | null>(null);
  private timer?: ReturnType<typeof setTimeout>;

  show(text: string, kind: ToastKind = 'info', durationMs = 2600): void {
    clearTimeout(this.timer);
    this.message.set({ text, kind });
    this.timer = setTimeout(() => this.message.set(null), durationMs);
  }
}
