import { TestBed } from '@angular/core/testing';
import { ActivatedRoute } from '@angular/router';
import { page } from '@vitest/browser/context';
import { of } from 'rxjs';
import { beforeEach, describe, expect, it } from 'vitest';
import { QdNotificationsService } from '@quadrel-enterprise-ui/framework';
import { appConfig } from '../../app.config';
import { LineService } from '../../core/line.service';
import { LineDetailComponent } from './line-detail.component';

const fakeLine = {
  id: '00000000-0000-0000-0000-000000000001',
  businessPartnerId: 'acme',
  _links: {
    self: { href: 'http://localhost/lines/00000000-0000-0000-0000-000000000001' },
    delete: { href: 'http://localhost/lines/00000000-0000-0000-0000-000000000001/delete' },
  },
  _embedded: {
    leftPoint: {
      id: 1,
      position: 2,
      numberOfUpdates: 1,
      _links: {},
    },
    rightPoint: {
      id: 2,
      position: 8,
      numberOfUpdates: 0,
      _links: {
        moveRight: { href: 'http://localhost/lines/00000000-0000-0000-0000-000000000001/right/move-right' },
      },
    },
    operations: [
      {
        operation: 'MoveRight',
        detail: 'right point moved by 1',
        performedBy: 'demo-administrator',
        performedAt: '2026-07-12T18:20:00Z',
      },
    ],
  },
};

/**
 * "Filling the component with fake data" the idiomatic Angular way: TestBed provider overrides,
 * not an HTTP-level mock -- LineService never makes a real call here, so this needs no backend
 * (and no server, no script) at all. Runs in a real browser (Vitest Browser Mode, see
 * angular.json's "test" target), so this also doubles as the fastest way to develop this
 * component against fixed data: `npm test` watches and re-renders on every save.
 */
describe('LineDetailComponent', () => {
  beforeEach(async () => {
    await TestBed.configureTestingModule({
      imports: [LineDetailComponent],
      providers: [
        // Same baseline providers the real app bootstraps with (Router, ngrx StoreModule,
        // translations, QdUiModule.forRoot) -- quadrel's own components (qd-page et al.) need
        // these to initialize regardless of what this test itself exercises. Our fakes below
        // override ActivatedRoute/LineService/QdNotificationsService on top.
        ...appConfig.providers,
        {
          provide: ActivatedRoute,
          useValue: { snapshot: { paramMap: { get: () => fakeLine.id } } },
        },
        {
          provide: LineService,
          useValue: {
            get: () => of({ line: fakeLine, etag: '"1"' }),
            delete: () => of(),
          },
        },
        { provide: QdNotificationsService, useValue: { add: () => {} } },
      ],
    }).compileComponents();
  });

  it('renders the fetched line and its permission-gated delete action, and screenshots it', async () => {
    const fixture = TestBed.createComponent(LineDetailComponent);
    fixture.detectChanges();

    await expect.element(page.getByText('acme')).toBeVisible();
    // exact: true -- the new Activity section's formatted `performedAt` timestamp also contains
    // "8" as a substring (e.g. "8:20:00 PM"), which would otherwise make this match ambiguous.
    await expect.element(page.getByText('8', { exact: true })).toBeVisible();

    // qd-page's own delete icon button has no accessible name, only quadrel's own
    // data-test-id="delete-button" -- matches the attribute LineRepresentationModelProcessor's
    // permission gating (a present-or-absent _links.delete, mocked above) is ultimately about.
    const deleteButtonElement = document.querySelector('[data-test-id="delete-button"]');
    if (deleteButtonElement === null) {
      throw new Error('delete button not found');
    }
    const deleteButton = page.elementLocator(deleteButtonElement);
    await expect.element(deleteButton).toBeVisible();

    // The custom "Edit" action is likewise gated on a HATEOAS link -- here `move-right`, mocked
    // above -- and rendered by the framework as a single plain button (not a menu) since there's
    // only one custom action configured.
    const editButtonElement = document.querySelector('[data-test-id="custom-button"]');
    if (editButtonElement === null) {
      throw new Error('edit action button not found');
    }
    await expect.element(page.elementLocator(editButtonElement)).toBeVisible();

    // The Activity section renders the line's operations audit trail (see LineModelAssembler's
    // `operations` relation, mocked above).
    const activityTable = document.querySelector('[data-test-id="activity-table"]');
    if (activityTable === null) {
      throw new Error('activity table not found');
    }
    await expect.element(page.getByText('right point moved by 1')).toBeVisible();

    // Default test iframe viewport is mobile-sized; widen it to match the desktop screenshot
    // already used for the overview page (see screenshot-overview.mjs) before capturing this one.
    await page.viewport(1280, 800);

    // No explicit `path`: Vitest resolves it against its own ephemeral build output dir, not this
    // source file, so an explicit relative path here just trips Vite's server.fs.strict sandbox.
    // take-component-screenshots.sh (optimistic-locking-web/) locates the default output instead.
    await page.screenshot();
  });
});
