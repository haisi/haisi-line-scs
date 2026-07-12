import { Component, inject } from '@angular/core';
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
  private readonly demoIdentityService = inject(DemoIdentityService);

  readonly identity = this.demoIdentityService.identity;

  readonly shellConfig: QdShellConfig = {
    title: { i18n: 'i18n.app.title' },
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
