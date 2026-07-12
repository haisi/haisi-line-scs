import { Component, inject } from '@angular/core';
import { Router } from '@angular/router';
import { QdNotificationsModule, QdShellConfig, QdShellModule } from '@quadrel-enterprise-ui/framework';
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

  // qd-shell renders `navigation` as its side navigation panel. There's only one feature area in
  // this app, so it's always the current item -- still worth wiring for real (rather than
  // leaving the shell without a navigation config) since it's how a user gets back to the list
  // from anywhere in the app, not just via the detail page's own back button.
  readonly shellConfig: QdShellConfig = {
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
  };

  onIdentityChange(event: Event): void {
    const value = (event.target as HTMLSelectElement).value as DemoIdentity | '';
    if (value) {
      this.demoIdentityService.login(value).subscribe();
    } else {
      this.demoIdentityService.logout();
    }
  }
}
