import { HttpClient, HttpResponse } from '@angular/common/http';
import { inject, Injectable } from '@angular/core';
import { map, Observable } from 'rxjs';
import { Line, LinesPage, LineWithETag } from './line.model';

function toLineWithETag(response: HttpResponse<Line>): LineWithETag {
  if (response.body === null) {
    throw new Error(`${response.url ?? '(unknown URL)'} returned no body`);
  }
  return { line: response.body, etag: response.headers.get('ETag') ?? '' };
}

@Injectable({ providedIn: 'root' })
export class LineService {
  private readonly http = inject(HttpClient);

  list(page: number, size: number): Observable<LinesPage> {
    return this.http.get<LinesPage>('/lines', { params: { page, size } });
  }

  get(id: string): Observable<LineWithETag> {
    return this.http
      .get<Line>(`/lines/${id}`, { observe: 'response' })
      .pipe(map((response) => toLineWithETag(response)));
  }

  delete(id: string, etag: string): Observable<unknown> {
    return this.http.delete(`/lines/${id}`, {
      headers: { 'If-Match': etag },
    });
  }

  /**
   * `by` is always a positive magnitude now -- the URL (which point, move-left/move-right) fixes
   * the direction, mirroring `LineController`'s four move endpoints on the backend. No
   * `Idempotency-Key` is sent: the backend treats a reused key with a different `by` as a 422
   * (`IdempotencyKeyReused`), which would misfire if a user corrects a rejected value and
   * resubmits from the same dialog session.
   */
  private move(id: string, path: string, etag: string, by: number): Observable<LineWithETag> {
    return this.http
      .put<Line>(`/lines/${id}/${path}`, { by }, { headers: { 'If-Match': etag }, observe: 'response' })
      .pipe(map((response) => toLineWithETag(response)));
  }

  moveLeftPointLeft(id: string, etag: string, by: number): Observable<LineWithETag> {
    return this.move(id, 'left/move-left', etag, by);
  }

  moveLeftPointRight(id: string, etag: string, by: number): Observable<LineWithETag> {
    return this.move(id, 'left/move-right', etag, by);
  }

  moveRightPointLeft(id: string, etag: string, by: number): Observable<LineWithETag> {
    return this.move(id, 'right/move-left', etag, by);
  }

  moveRightPointRight(id: string, etag: string, by: number): Observable<LineWithETag> {
    return this.move(id, 'right/move-right', etag, by);
  }
}
