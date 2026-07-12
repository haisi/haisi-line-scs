import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { Component, inject } from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import {
  QdButtonModule,
  QdDialogModule,
  QdFormInputConfiguration,
  QdFormModule,
  QdNotificationsService,
} from '@quadrel-enterprise-ui/framework';
import { Observable, of, switchMap } from 'rxjs';
import { Line, LineWithETag } from '../../core/line.model';
import { LineService } from '../../core/line.service';
import { toProblemDetail } from '../../core/problem-detail';

export interface EditLineDialogData {
  line: Line;
  etag: string;
}

@Component({
  selector: 'app-edit-line-dialog',
  imports: [ReactiveFormsModule, QdDialogModule, QdFormModule, QdButtonModule],
  templateUrl: './edit-line-dialog.component.html',
})
export class EditLineDialogComponent {
  private readonly dialogRef = inject(DialogRef<boolean, EditLineDialogComponent>);
  private readonly data = inject<EditLineDialogData>(DIALOG_DATA);
  private readonly lineService = inject(LineService);
  private readonly notifications = inject(QdNotificationsService);

  private readonly canMoveLeft = !!this.data.line._links['move-left'];
  private readonly canMoveRight = !!this.data.line._links['move-right'];

  readonly leftConfig: QdFormInputConfiguration = {
    label: { i18n: 'i18n.lines.detail.editDialog.leftLabel' },
    inputType: 'number',
    disabled: !this.canMoveLeft,
  };
  readonly rightConfig: QdFormInputConfiguration = {
    label: { i18n: 'i18n.lines.detail.editDialog.rightLabel' },
    inputType: 'number',
    disabled: !this.canMoveRight,
  };

  /**
   * `QdFormInputConfiguration.disabled` alone (see leftConfig/rightConfig above) isn't enough --
   * the framework's own docs note an Angular FormControl's *own* disabled state takes priority
   * over the config when they disagree, so it has to be set here too.
   */
  readonly form = new FormGroup({
    left: new FormControl(
      { value: this.data.line.left, disabled: !this.canMoveLeft },
      { nonNullable: true, validators: [Validators.required] },
    ),
    right: new FormControl(
      { value: this.data.line.right, disabled: !this.canMoveRight },
      { nonNullable: true, validators: [Validators.required] },
    ),
  });

  onCancel(): void {
    this.dialogRef.close(false);
  }

  onSubmit(): void {
    if (this.form.invalid) {
      return;
    }

    const leftBy = this.form.controls.left.value - this.data.line.left;
    const rightBy = this.form.controls.right.value - this.data.line.right;
    if (leftBy === 0 && rightBy === 0) {
      this.dialogRef.close(false);
      return;
    }

    this.submitMoves(leftBy, rightBy).subscribe({
      next: () => {
        this.dialogRef.close(true);
      },
      error: (error: unknown) => {
        const problem = toProblemDetail(error);
        this.notifications.add('', {
          type: 'critical',
          i18n: problem.detail ?? 'i18n.lines.detail.editDialog.error',
          showAsSnackbar: true,
        });
      },
    });
  }

  /**
   * Left and right each need their own PUT, and a second call must carry the *first* call's
   * returned ETag, not the one the dialog opened with -- exactly the optimistic-locking lesson
   * this repo teaches: every write needs the version left by the write before it, not a stale one
   * captured before either happened.
   */
  private submitMoves(leftBy: number, rightBy: number): Observable<LineWithETag> {
    const id = this.data.line.id;
    let result$: Observable<LineWithETag> = of({ line: this.data.line, etag: this.data.etag });
    if (leftBy !== 0) {
      result$ = this.lineService.moveLeft(id, this.data.etag, leftBy);
    }
    if (rightBy !== 0) {
      result$ = result$.pipe(switchMap((result) => this.lineService.moveRight(id, result.etag, rightBy)));
    }
    return result$;
  }
}
