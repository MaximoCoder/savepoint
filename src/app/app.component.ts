import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { IonApp, IonRouterOutlet, NavController } from './core/ionic';
import { Capacitor } from '@capacitor/core';
import { App } from '@capacitor/app';
import { StatusBar, Style } from '@capacitor/status-bar';
import { ToastService } from './core/toast.service';
import { Vault } from './plugins/vault';

@Component({
  selector: 'app-root',
  imports: [IonApp, IonRouterOutlet],
  template: `
    <ion-app>
      <ion-router-outlet></ion-router-outlet>

      @if (toast.message(); as msg) {
        <div class="toast toast-center z-[1000] w-full max-w-sm px-4" style="bottom: calc(var(--safe-area-inset-bottom, env(safe-area-inset-bottom, 0px)) + 6rem)">
          <div
            role="status"
            class="alert alert-soft shadow-lg"
            [class.alert-success]="msg.kind === 'success'"
            [class.alert-error]="msg.kind === 'error'"
            [class.alert-info]="msg.kind === 'info'"
          >
            <span>{{ msg.text }}</span>
          </div>
        </div>
      }
    </ion-app>
  `,
})
export class AppComponent {
  readonly toast = inject(ToastService);
  private readonly router = inject(Router);
  private readonly nav = inject(NavController);

  constructor() {
    if (Capacitor.isNativePlatform()) {
      StatusBar.setStyle({ style: Style.Dark }).catch(() => undefined);
      StatusBar.setBackgroundColor({ color: '#030712' }).catch(() => undefined);

      // Al volver a la app, si la bóveda se bloqueó por inactividad, regresar a la pantalla de bloqueo
      App.addListener('resume', () => this.checkLock());
    }
  }

  private async checkLock(): Promise<void> {
    if (this.router.url.startsWith('/lock')) return;
    try {
      const { unlocked } = await Vault.status();
      if (!unlocked) await this.nav.navigateRoot('/lock', { animationDirection: 'back' });
    } catch {
      await this.nav.navigateRoot('/lock', { animationDirection: 'back' });
    }
  }
}
