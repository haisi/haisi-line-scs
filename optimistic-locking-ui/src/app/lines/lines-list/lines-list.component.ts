import { Component, computed, effect, inject } from '@angular/core';
import { Router } from '@angular/router';
import {
  QD_TABLE_DATA_RESOLVER_TOKEN,
  QdPageConfig,
  QdPageModule,
  QdSectionConfig,
  QdSectionModule,
  QdTableConfig,
  QdTableModule,
} from '@quadrel-enterprise-ui/framework';
import { Subject } from 'rxjs';
import { DemoIdentityService } from '../../core/demo-identity.service';
import { PermissionDeniedComponent } from '../../shared/permission-denied/permission-denied.component';
import { LineColumn } from './lines-list.model';
import { LinesTableResolver } from './lines-table.resolver';

@Component({
  selector: 'app-lines-list',
  imports: [QdPageModule, QdSectionModule, QdTableModule, PermissionDeniedComponent],
  providers: [{ provide: QD_TABLE_DATA_RESOLVER_TOKEN, useClass: LinesTableResolver }],
  templateUrl: './lines-list.component.html',
})
export class LinesListComponent {
  private readonly router = inject(Router);
  private readonly identityService = inject(DemoIdentityService);

  /**
   * `GET /lines` requires a caller holding `line_#read` for the named partner -- with no demo
   * identity selected yet, that request is a 401, and `<qd-table>` has no built-in "access
   * denied" state of its own, so without this it would just sit there loading forever. Checking
   * this ourselves and swapping the whole table out for an explicit message is what actually
   * surfaces that to the user.
   */
  readonly hasIdentity = computed(() => this.identityService.identity() !== null);

  /**
   * `<qd-table>` only calls its resolver once on init. Selecting a demo identity happens
   * *after* that first (necessarily unauthenticated, 401) fetch, so without this, choosing an
   * identity would never actually reload the table -- the auth token would exist, but nothing
   * would re-request `GET /lines` with it.
   */
  private readonly refresh = new Subject<number | undefined>();

  readonly pageConfig: QdPageConfig = {
    title: { i18n: 'i18n.lines.overview.title' },
    pageType: 'overview',
  };

  readonly sectionConfig: QdSectionConfig = {
    title: { i18n: 'i18n.lines.overview.title' },
  };

  readonly tableConfig: QdTableConfig<LineColumn> = {
    i18ns: 'i18n.lines.overview.table',
    columns: [
      { column: 'id', type: 'text' },
      { column: 'left', type: 'integer' },
      { column: 'right', type: 'integer' },
      { column: 'businessPartnerId', type: 'text' },
    ],
    pagination: { pageSizeDefault: 10, pageSizes: [10, 20, 50] },
    primaryAction: {
      handler: ({ rowData }) => {
        void this.router.navigate(['/lines', rowData.id as string]);
      },
    },
    refresh: this.refresh.asObservable(),
  };

  constructor() {
    effect(() => {
      this.identityService.identity();
      this.refresh.next(undefined);
    });
  }
}
