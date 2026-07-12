import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import {
  QdListModule,
  QdNotificationsService,
  QdPageConfig,
  QdPageModule,
  QdSectionModule,
} from '@quadrel-enterprise-ui/framework';
import { catchError, of } from 'rxjs';
import { LineService } from '../../core/line.service';
import { toProblemDetail } from '../../core/problem-detail';

function requireRouteId(route: ActivatedRoute): string {
  const id = route.snapshot.paramMap.get('id');
  if (id === null) {
    throw new Error('app-line-detail requires a route with an "id" param');
  }
  return id;
}

@Component({
  selector: 'app-line-detail',
  imports: [QdPageModule, QdSectionModule, QdListModule],
  templateUrl: './line-detail.component.html',
})
export class LineDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly lineService = inject(LineService);
  private readonly notifications = inject(QdNotificationsService);

  private readonly id = requireRouteId(this.route);

  /**
   * A single fetch, not a live subscription: `LineService.get` completes after its one HTTP
   * response, so this never re-emits on its own -- matching the old subscribe-once-in-constructor
   * behavior, just expressed as a signal instead of manual `.set()` calls. A failed fetch (e.g.
   * 404) is caught here rather than left to `toSignal`'s default of rethrowing on read, since we
   * want to navigate back to the list, not crash the detail page.
   */
  private readonly lineWithETag = toSignal(
    this.lineService.get(this.id).pipe(
      catchError(() => {
        this.goBackToList();
        return of();
      }),
    ),
  );

  readonly line = computed(() => {
    const result = this.lineWithETag();
    if (!result) {
      return null;
    }
    return {
      left: result.line.left,
      right: result.line.right,
      businessPartnerId: result.line.businessPartnerId,
      canDelete: !!result.line._links.delete,
    };
  });

  /**
   * `pageTypeConfig.delete` is only included when the fetched Line actually carries a `delete`
   * HATEOAS link -- the backend only adds that link when the caller holds `line_#delete`
   * (LineRepresentationModelProcessor). Omitting the key entirely, rather than disabling a
   * button, is what makes qd-page's own Delete action disappear -- a real server-computed
   * visibility gate.
   */
  readonly pageConfig = computed<QdPageConfig | null>(() => {
    const line = this.line();
    if (!line) {
      return null;
    }
    return {
      title: { i18n: 'i18n.lines.detail.title' },
      pageType: 'inspect',
      pageTypeConfig: {
        hideEdit: true,
        cancel: { handler: () => { this.goBackToList(); } },
        ...(line.canDelete ? { delete: { handler: () => { this.onDelete(); } } } : {}),
      },
    };
  });

  onDelete(): void {
    if (!globalThis.confirm('Delete this line? This cannot be undone.')) {
      return;
    }
    const etag = this.lineWithETag()?.etag ?? '';
    this.lineService.delete(this.id, etag).subscribe({
      next: () => {
        this.notifications.add('', { type: 'success', i18n: 'i18n.lines.detail.deleteSuccess', showAsSnackbar: true });
        this.goBackToList();
      },
      error: (error) => {
        const problem = toProblemDetail(error);
        this.notifications.add('', {
          type: 'critical',
          i18n: problem.detail ?? 'i18n.lines.detail.deleteError',
          showAsSnackbar: true,
        });
      },
    });
  }

  private goBackToList(): void {
    void this.router.navigate(['/lines']);
  }
}
