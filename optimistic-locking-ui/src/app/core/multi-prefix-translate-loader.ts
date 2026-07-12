import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { TranslateLoader, TranslationObject } from '@ngx-translate/core';
import { forkJoin, map, Observable } from 'rxjs';

/**
 * Merges this app's own translations (public/i18n/{lang}.json) with the framework's pre-baked
 * ones (copied from @quadrel-enterprise-ui/framework into assets/i18n/qd-ui/{lang}.json by
 * angular.json's assets config) into a single translation set per language, reimplemented
 * directly here since a single dependency-sized loader isn't worth adding for two JSON fetches.
 *
 * Both files are genuinely flat: quadrel ships translations as top-level keys that are
 * themselves full dotted strings (e.g. `"i18n.qd.table.pagination.of": "of"`), not nested
 * objects -- ngx-translate's key resolution (`getValue`) greedily re-accumulates dotted segments
 * against exactly this shape. A plain shallow merge is therefore correct; nesting either side
 * under a shared `"i18n"` object key would make that resolution walk into the wrong branch and
 * silently fail for every quadrel-internal string.
 */
@Injectable({ providedIn: 'root' })
export class MultiPrefixTranslateLoader implements TranslateLoader {
  private readonly http = inject(HttpClient);

  getTranslation(lang: string): Observable<TranslationObject> {
    return forkJoin([
      this.http.get<TranslationObject>(`./assets/i18n/${lang}.json`),
      this.http.get<TranslationObject>(`./assets/i18n/qd-ui/${lang}.json`),
    ]).pipe(map(([app, qdUi]) => ({ ...qdUi, ...app }) as TranslationObject));
  }
}
