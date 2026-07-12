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
      map((response) => {
        if (response.body === null) {
          throw new Error(`GET /lines/${id} returned no body`);
        }
        return { line: response.body, etag: response.headers.get('ETag') ?? '' };
      }),
    );
  }

  delete(id: string, etag: string): Observable<unknown> {
    return this.http.delete(`/lines/${id}`, {
      headers: { 'If-Match': etag },
    });
  }
}
