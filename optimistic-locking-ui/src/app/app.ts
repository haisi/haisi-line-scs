import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { NavigationEnd, Router } from '@angular/router';
import { QdNotificationsModule, QdShellConfig, QdShellModule } from '@quadrel-enterprise-ui/framework';
import { filter, map } from 'rxjs';
import { DemoIdentity, DemoIdentityService } from './core/demo-identity.service';

@Component({
  selector: 'app-root',
  // <qd-shell> renders its own internal <router-outlet> (confirmed against the framework's
  // compiled bundle) -- routed pages don't need one declared here.
  imports: [QdShellModule, QdNotificationsModule],
  templateUrl: './app.html',
  styleUrl: './app.scss',
})
export class App {
  private readonly router = inject(Router);
  private readonly demoIdentityService = inject(DemoIdentityService);

  readonly identity = this.demoIdentityService.identity;

  private readonly url = toSignal(
    this.router.events.pipe(
      filter((event) => event instanceof NavigationEnd),
      map(() => this.router.url),
    ),
    { initialValue: this.router.url },
  );

  private readonly isOnDetailRoute = computed(() => /^\/lines\/.+/.test(this.url()));

  /**
   * qd-shell has no content-projection slot for arbitrary page content (only `qd-comments`), and
   * no built-in "back" concept of its own -- so a back affordance that lives in the shell itself,
   * rather than in each page's own template, has to be a `toolbar` item: the one config-driven,
   * clickable icon slot the shell's header exposes. Only shown while on a line's detail route.
   */
  readonly shellConfig = computed<QdShellConfig>(() => ({
    title: { i18n: 'i18n.app.title' },
    navigation: [
      {
        i18n: 'i18n.nav.lines',
        icon: 'home',
        isCurrent: true,
        handler: () => {
          void this.router.navigate(['/lines']);
        },
      },
    ],
    ...(this.isOnDetailRoute()
      ? {
          toolbar: {
            items: [
              {
                qdIcon: 'arrowLeft',
                handler: () => {
                  void this.router.navigate(['/lines']);
                },
              },
            ],
          },
        }
      : {}),
  }));

  onIdentityChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value as DemoIdentity | '';
    if (value) {
      this.demoIdentityService.login(value).subscribe();
    } else {
      this.demoIdentityService.logout();
    }
  }
}
