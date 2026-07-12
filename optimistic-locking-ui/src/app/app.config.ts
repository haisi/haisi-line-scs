import { provideHttpClient, withInterceptors } from '@angular/common/http';
import {
  ApplicationConfig,
  importProvidersFrom,
  provideBrowserGlobalErrorListeners,
  provideZoneChangeDetection,
} from '@angular/core';
import { provideRouter, withHashLocation } from '@angular/router';
import { StoreModule } from '@ngrx/store';
import { provideTranslateLoader, provideTranslateService } from '@ngx-translate/core';
import { QdUiModule } from '@quadrel-enterprise-ui/framework';
import { authInterceptor } from './core/auth.interceptor';
import { MultiPrefixTranslateLoader } from './core/multi-prefix-translate-loader';
import { appEnvironment } from '../environments/environment';
import { routes } from './app.routes';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideZoneChangeDetection({ eventCoalescing: true }),
    // Hash-based routing is deliberate, not a style choice: the backend's REST API already owns
    // the path "/lines/**" (LineController), so a path-based Angular route at "/lines/:id" would
    // collide with the server's real GET /lines/{id} on a hard refresh/deep link. With hash
    // routing the browser only ever requests "/" for navigation, sidestepping that collision
    // entirely.
    provideRouter(routes, withHashLocation()),
    provideHttpClient(withInterceptors([authInterceptor])),
    // @quadrel-enterprise-ui/framework hard-imports @ngrx/store and @ngx-translate/core
    // internally (verified against its compiled bundle), so both need a real provider even
    // though this app doesn't otherwise use ngrx state or ship its own translations. Must be
    // the classic NgModule-style StoreModule.forRoot(), not the newer functional provideStore():
    // the framework internally registers its own state via StoreModule.forFeature(), which
    // asserts the NgModule-only `_StoreRootModule` marker exists -- provideStore() alone doesn't
    // set that marker and fails with NG0201 ("No provider found for _StoreRootModule").
    importProvidersFrom(StoreModule.forRoot({})),
    provideTranslateService({
      loader: provideTranslateLoader(MultiPrefixTranslateLoader),
      fallbackLang: 'en',
      lang: 'en',
    }),
    importProvidersFrom(QdUiModule.forRoot(appEnvironment)),
  ],
};
