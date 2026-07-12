import { DIALOG_DATA, DialogRef } from '@angular/cdk/dialog';
import { TestBed } from '@angular/core/testing';
import { page } from '@vitest/browser/context';
import { NEVER, of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { QdNotificationsService } from '@quadrel-enterprise-ui/framework';
import { appConfig } from '../../app.config';
import { Line, LineWithETag } from '../../core/line.model';
import { LineService } from '../../core/line.service';
import { EditLineDialogComponent } from './edit-line-dialog.component';

/**
 * Left point has no move affordances at all (both buttons must be disabled) while the right point
 * has both -- deliberately a mix, so the screenshot shows two disabled buttons next to two enabled
 * ones, not four identical buttons. `leftPoint` also deliberately omits `_links` entirely (not just
 * an empty object) -- that's the real shape Spring HATEOAS's HAL serialization produces for a point
 * with zero links, e.g. once the update budget is fully spent, and the component must not throw on it.
 */
const fakeLine: Line = {
  id: '00000000-0000-0000-0000-000000000001',
  businessPartnerId: 'acme',
  _links: {
    self: { href: 'http://localhost/lines/00000000-0000-0000-0000-000000000001' },
  },
  _embedded: {
    leftPoint: {
      id: 1,
      position: 5,
      numberOfUpdates: 5,
    },
    rightPoint: {
      id: 2,
      position: 8,
      numberOfUpdates: 1,
      _links: {
        moveLeft: { href: 'http://localhost/lines/00000000-0000-0000-0000-000000000001/right/move-left' },
        moveRight: { href: 'http://localhost/lines/00000000-0000-0000-0000-000000000001/right/move-right' },
      },
    },
    operations: [],
  },
};

/**
 * Same TestBed-provider-override pattern as line-detail.component.spec.ts -- no HTTP mocking, no
 * backend, no separate script. DialogRef/DIALOG_DATA are the `@angular/cdk/dialog` tokens the
 * component already injects directly, so they're overridden the same way LineService is.
 */
describe('EditLineDialogComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [EditLineDialogComponent],
      providers: [
        ...appConfig.providers,
        // `QdDialogComponent` (rendered inside this component's own template) also injects
        // `DialogRef` -- its `ngOnInit` reads `dialogRef.config` and binds the Esc key via
        // `dialogRef.keydownEvents.pipe(...)`, so the fake needs those too, not just `close()`.
        { provide: DialogRef, useValue: { close: (): void => {}, keydownEvents: NEVER, config: {} } },
        { provide: DIALOG_DATA, useValue: { line: fakeLine, etag: '"1"' } },
        {
          provide: LineService,
          useValue: {
            moveLeftPointLeft: () => of<LineWithETag>({ line: fakeLine, etag: '"2"' }),
            moveLeftPointRight: () => of<LineWithETag>({ line: fakeLine, etag: '"2"' }),
            moveRightPointLeft: () => of<LineWithETag>({ line: fakeLine, etag: '"2"' }),
            moveRightPointRight: () => of<LineWithETag>({ line: fakeLine, etag: '"2"' }),
          },
        },
        { provide: QdNotificationsService, useValue: { add: (): void => {} } },
      ],
    }).compileComponents();
  });

  it('renders a nudge button per point/direction, disabled exactly where its link is absent, and screenshots it', async () => {
    const fixture = TestBed.createComponent(EditLineDialogComponent);
    fixture.detectChanges();

    const moveLeftPointLeftButton = document.querySelector<HTMLButtonElement>('[data-test-id="move-left-point-left"]');
    const moveLeftPointRightButton = document.querySelector<HTMLButtonElement>(
      '[data-test-id="move-left-point-right"]',
    );
    const moveRightPointLeftButton = document.querySelector<HTMLButtonElement>(
      '[data-test-id="move-right-point-left"]',
    );
    const moveRightPointRightButton = document.querySelector<HTMLButtonElement>(
      '[data-test-id="move-right-point-right"]',
    );
    if (
      moveLeftPointLeftButton === null ||
      moveLeftPointRightButton === null ||
      moveRightPointLeftButton === null ||
      moveRightPointRightButton === null
    ) {
      throw new Error('one or more move buttons not found');
    }

    await expect.element(page.elementLocator(moveLeftPointLeftButton)).toBeVisible();

    // the left point has no `_links` at all (both its buttons disabled, and rendering it must not
    // throw); the right point's links are both present, so neither of its buttons is disabled.
    expect(moveLeftPointLeftButton.disabled).toBe(true);
    expect(moveLeftPointRightButton.disabled).toBe(true);
    expect(moveRightPointLeftButton.disabled).toBe(false);
    expect(moveRightPointRightButton.disabled).toBe(false);

    // Default test iframe viewport is mobile-sized; widen it to match the other screenshots (see
    // line-detail.component.spec.ts) before capturing this one.
    await page.viewport(1280, 800);

    // No explicit `path`: Vitest resolves it against its own ephemeral build output dir, not this
    // source file -- take-component-screenshots.sh (optimistic-locking-web/) locates the output.
    await page.screenshot();
  });
});
