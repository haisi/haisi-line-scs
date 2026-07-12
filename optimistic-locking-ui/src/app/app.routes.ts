import { Routes } from '@angular/router';
import { LineDetailComponent } from './lines/line-detail/line-detail.component';
import { LinesListComponent } from './lines/lines-list/lines-list.component';

export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'lines' },
  { path: 'lines', component: LinesListComponent },
  { path: 'lines/:id', component: LineDetailComponent },
];
