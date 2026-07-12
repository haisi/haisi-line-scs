import { Component, computed, inject, signal } from '@angular/core';
import { ActivatedRoute, Router } from '@angular/router';
import {
  QdButtonModule,
  QdIconModule,
  QdListModule,
  QdNotificationsService,
  QdPageConfig,
  QdPageModule,
  QdSectionModule,
} from '@quadrel-enterprise-ui/framework';
import { LineService } from '../../core/line.service';
import { toProblemDetail } from '../../core/problem-detail';

@Component({
  selector: 'app-line-detail',
  imports: [QdButtonModule, QdIconModule, QdPageModule, QdSectionModule, QdListModule],
  templateUrl: './line-detail.component.html',
  styles: `
    .back-button {
      margin: 1rem 0 0 1rem;
    }
  `,
})
export class LineDetailComponent {
  private readonly route = inject(ActivatedRoute);
  private readonly router = inject(Router);
  private readonly lineService = inject(LineService);
  private readonly notifications = inject(QdNotificationsService);

  private readonly id = this.route.snapshot.paramMap.get('id') as string;
  private readonly etag = signal('');

  readonly line = signal<{ left: number; right: number; businessPartnerId: string; canDelete: boolean } | null>(
    null,
  );

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
        cancel: { handler: () => this.goBackToList() },
        ...(line.canDelete ? { delete: { handler: () => this.onDelete() } } : {}),
      },
    };
  });

  constructor() {
    this.lineService.get(this.id).subscribe({
      next: ({ line, etag }) => {
        this.etag.set(etag);
        this.line.set({
          left: line.left,
          right: line.right,
          businessPartnerId: line.businessPartnerId,
          canDelete: !!line._links.delete,
        });
      },
      error: () => this.goBackToList(),
    });
  }

  onDelete(): void {
    if (!window.confirm('Delete this line? This cannot be undone.')) {
      return;
    }
    this.lineService.delete(this.id, this.etag()).subscribe({
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

  goBackToList(): void {
    void this.router.navigate(['/lines']);
  }
}
