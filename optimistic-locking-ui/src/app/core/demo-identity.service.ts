import { HttpClient } from '@angular/common/http';
import { inject, Injectable, signal } from '@angular/core';
import { map, Observable, tap } from 'rxjs';

export type DemoIdentity = 'viewer' | 'administrator';

/**
 * Stands in for a real SSO login, which this teaching repo can't wire up: the backend's OAuth2
 * issuer is a documented placeholder (see application.properties), so there is no real IdP to log
 * in to. `POST /dev/login` -- only routable at all under the backend's "local" Spring profile --
 * mints a signed demo JWT for one of two fixed identities and hands it back here. Never call this
 * against anything but a local dev backend.
 */
@Injectable({ providedIn: 'root' })
export class DemoIdentityService {
  private readonly http = inject(HttpClient);

  private readonly identitySignal = signal<DemoIdentity | null>(null);
  private readonly tokenSignal = signal<string | null>(null);

  readonly identity = this.identitySignal.asReadonly();
  readonly token = this.tokenSignal.asReadonly();

  login(identity: DemoIdentity): Observable<void> {
    return this.http
      .post<{ token: string }>('/dev/login', null, { params: { identity } })
      .pipe(
        tap(({ token }) => {
          this.identitySignal.set(identity);
          this.tokenSignal.set(token);
        }),
        map(() => undefined),
      );
  }

  logout(): void {
    this.identitySignal.set(null);
    this.tokenSignal.set(null);
  }
}
