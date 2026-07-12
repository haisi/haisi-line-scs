import { Component } from '@angular/core';
import { QdIconModule, QdTextSectionModule } from '@quadrel-enterprise-ui/framework';

/**
 * Shown in place of a page's real content when the current demo identity (see
 * DemoIdentityService) doesn't hold the role that content requires -- e.g. no identity selected
 * at all, so the backend would 401 on the underlying request. Rendering this explicitly, instead
 * of just letting the failed request fall through, is what keeps a permission-gated view (like
 * the lines list) from sitting in a perpetual loading state with no feedback to the user.
 */
@Component({
  selector: 'app-permission-denied',
  imports: [QdIconModule, QdTextSectionModule],
  templateUrl: './permission-denied.component.html',
  styleUrl: './permission-denied.component.scss',
})
export class PermissionDeniedComponent {}
