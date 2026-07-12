import { DatePipe } from '@angular/common';
import { Component, computed, inject } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { ActivatedRoute, Router } from '@angular/router';
import {
  QdDialogService,
  QdDialogSize,
  QdListModule,
  QdNotificationsService,
  QdPageConfig,
  QdPageModule,
  QdSectionModule,
} from '@quadrel-enterprise-ui/framework';
import { catchError, of, Subject, startWith, switchMap } from 'rxjs';
import { LineService } from '../../core/line.service';
import { toProblemDetail } from '../../core/problem-detail';
import { EditLineDialogComponent } from './edit-line-dialog.component';

function requireRouteId(route: ActivatedRoute): string {
  const id = route.snapshot.paramMap.get('id');
  if (id === null) {
    throw new Error('app-line-detail requires a route with an "id" param');
  }
  return id;
}

@Component({
  selector: 'app-line-detail',
  imports: [QdPageModule, QdSectionModule, QdListModule, DatePipe],
  templateUrl: './line-detail.component.html',
})
export class LineDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly lineService = inject(LineService);
  private readonly notifications = inject(QdNotificationsService);
  private readonly dialogService = inject(QdDialogService);

  private readonly id = requireRouteId(this.route);

  /**
   * `refresh` re-triggers the fetch below (e.g. after a successful edit); `startWith` makes the
   * initial fetch happen without anyone having to call `.next()` first. A failed fetch (e.g. 404)
   * is caught here rather than left to `toSignal`'s default of rethrowing on read, since we want
   * to navigate back to the list, not crash the detail page.
   */
  private readonly refresh = new Subject<void>();

  private readonly lineWithETag = toSignal(
    this.refresh.pipe(
      // Genuinely needed: startWith() with zero arguments emits nothing, so the initial fetch
      // would never fire without an explicit (void-typed) placeholder value here.
      // eslint-disable-next-line unicorn/no-useless-undefined
      startWith(undefined),
      switchMap(() =>
        this.lineService.get(this.id).pipe(
          catchError(() => {
            this.goBackToList();
            return of();
          }),
        ),
      ),
    ),
  );

  readonly line = computed(() => {
    const result = this.lineWithETag();
    if (!result) {
      return null;
    }
    const { leftPoint, rightPoint, operations } = result.line._embedded;
    return {
      left: leftPoint.position,
      right: rightPoint.position,
      businessPartnerId: result.line.businessPartnerId,
      operations,
      canDelete: !!result.line._links.delete,
      // "all moves used up" (see edit-line-dialog) means none of the four per-point/direction
      // links are offered -- the whole Edit action disappears in that case, not just one button.
      canEdit: !!(
        leftPoint._links?.moveLeft ??
        leftPoint._links?.moveRight ??
        rightPoint._links?.moveLeft ??
        rightPoint._links?.moveRight
      ),
    };
  });

  /**
   * `pageTypeConfig.delete`/`customActions` are only included when the fetched Line actually
   * carries the matching HATEOAS link (`delete`, or any of the four per-point move links) -- the
   * backend only adds those when the caller holds the matching role for this specific line (see
   * LineModelAssembler). Omitting the key entirely, rather than disabling a button, is what makes
   * qd-page's own actions disappear -- a real server-computed visibility gate.
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
        ...(line.canEdit
          ? {
              customActions: [
                { label: { i18n: 'i18n.lines.detail.edit' }, handler: () => { this.onEdit(); } },
              ],
            }
          : {}),
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

  onEdit(): void {
    const current = this.lineWithETag();
    if (!current) {
      return;
    }
    // Unlike the old single-submit dialog, every button click inside EditLineDialogComponent
    // already mutates server state immediately -- there's no "was anything edited" result to
    // check, so this always refreshes (a harmless extra GET if nothing was actually moved).
    this.dialogService
      .open(EditLineDialogComponent, {
        title: { i18n: 'i18n.lines.detail.editDialog.title' },
        dialogSize: QdDialogSize.Small,
        data: { line: current.line, etag: current.etag },
      })
      .closed.subscribe(() => {
        this.refresh.next();
      });
  }

  private goBackToList(): void {
    void this.router.navigate(['/lines']);
  }
}
