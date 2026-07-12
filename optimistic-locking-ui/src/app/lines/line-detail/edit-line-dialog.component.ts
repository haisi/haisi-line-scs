import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { Component, computed, inject, signal } from '@angular/core';
import { QdButtonModule, QdDialogModule, QdNotificationsService } from '@quadrel-enterprise-ui/framework';
import { Observable } from 'rxjs';
import { Line, LineWithETag } from '../../core/line.model';
import { LineService } from '../../core/line.service';
import { toProblemDetail } from '../../core/problem-detail';

export interface EditLineDialogData {
  line: Line;
  etag: string;
}

/**
 * Four nudge buttons, one per point x direction, each firing its own PUT immediately (no separate
 * submit step) -- replaces the earlier free-form left/right number inputs. Holds the fetched line
 * as local state so a successful move updates positions and button availability in place, without
 * closing the dialog; each button is disabled exactly when its HATEOAS link (`leftPoint`/
 * `rightPoint`'s `moveLeft`/`moveRight`, see LineModelAssembler) is absent from the current state.
 */
@Component({
  selector: 'app-edit-line-dialog',
  imports: [QdDialogModule, QdButtonModule],
  templateUrl: './edit-line-dialog.component.html',
})
export class EditLineDialogComponent {
  private readonly dialogRef = inject(DialogRef<void, EditLineDialogComponent>);
  private readonly data = inject<EditLineDialogData>(DIALOG_DATA);
  private readonly lineService = inject(LineService);
  private readonly notifications = inject(QdNotificationsService);

  private readonly state = signal<LineWithETag>({ line: this.data.line, etag: this.data.etag });

  readonly leftPoint = computed(() => this.state().line._embedded.leftPoint);
  readonly rightPoint = computed(() => this.state().line._embedded.rightPoint);

  /** Disables every button while a move is in flight, so a second click can't race the first. */
  readonly pending = signal(false);

  onClose(): void {
    this.dialogRef.close();
  }

  moveLeftPointLeft(): void {
    this.move(this.lineService.moveLeftPointLeft(this.state().line.id, this.state().etag, 1));
  }

  moveLeftPointRight(): void {
    this.move(this.lineService.moveLeftPointRight(this.state().line.id, this.state().etag, 1));
  }

  moveRightPointLeft(): void {
    this.move(this.lineService.moveRightPointLeft(this.state().line.id, this.state().etag, 1));
  }

  moveRightPointRight(): void {
    this.move(this.lineService.moveRightPointRight(this.state().line.id, this.state().etag, 1));
  }

  private move(result$: Observable<LineWithETag>): void {
    this.pending.set(true);
    result$.subscribe({
      next: (result) => {
        this.state.set(result);
        this.pending.set(false);
      },
      error: (error: unknown) => {
        this.pending.set(false);
        const problem = toProblemDetail(error);
        this.notifications.add('', {
          type: 'critical',
          i18n: problem.detail ?? 'i18n.lines.detail.editDialog.error',
          showAsSnackbar: true,
        });
      },
    });
  }
}
