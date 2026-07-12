import { HttpClient } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { Line, LinesPage, LineWithETag } from './line.model';

@Injectable({ providedIn: 'root' })
export class LineService {
  private readonly http = inject(HttpClient);

  list(page: number, size: number): Observable<LinesPage> {
    return this.http.get<LinesPage>('/lines', { params: { page, size } });
  }

  get(id: string): Observable<LineWithETag> {
    return this.http.get<Line>(`/lines/${id}`, { observe: 'response' }).pipe(
      map((response) => ({
        line: response.body as Line,
        etag: response.headers.get('ETag') ?? '',
      })),
    );
  }

  delete(id: string, etag: string): Observable<void> {
    return this.http.delete<void>(`/lines/${id}`, {
      headers: { 'If-Match': etag },
    });
  }
}
