import { inject, Injectable } from '@angular/core';
import { QdTableDataResolver, QdTableDataResolverProps, QdTableResolvedData } from '@quadrel-enterprise-ui/framework';
import { map, Observable } from 'rxjs';
import { LineService } from '../../core/line.service';
import { LineColumn } from './lines-list.model';

@Injectable()
export class LinesTableResolver implements QdTableDataResolver<LineColumn> {
  private readonly lineService = inject(LineService);

  resolve(props: QdTableDataResolverProps<LineColumn>): Observable<QdTableResolvedData<LineColumn>> {
    const page = props.page ?? 0;
    const size = props.size ?? 10;
    return this.lineService.list(page, size).pipe(
      map((linesPage) => ({
        data: linesPage.content.map((line) => ({
          uid: line.id,
          id: line.id,
          left: line.left,
          right: line.right,
          businessPartnerId: line.businessPartnerId,
        })),
        page: linesPage.page.number,
        size: linesPage.page.size,
        totalElements: linesPage.page.totalElements,
      })),
    );
  }
}
