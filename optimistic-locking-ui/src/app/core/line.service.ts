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
   * `by` is a delta relative to the point's *current* position, not the new absolute value --
   * mirroring `Line.moveLeft`/`LineCommand.MoveLeft` on the backend. No `Idempotency-Key` is sent:
   * the backend treats a reused key with a different `by` as a 422 (`IdempotencyKeyReused`), which
   * would misfire if a user corrects a rejected value and resubmits from the same dialog session.
   */
  moveLeft(id: string, etag: string, by: number): Observable<LineWithETag> {
    return this.http
      .put<Line>(`/lines/${id}/left`, { by }, { headers: { 'If-Match': etag }, observe: 'response' })
      .pipe(map((response) => toLineWithETag(response)));
  }

  /** See {@link moveLeft} for why `by` is a delta and no `Idempotency-Key` is sent. */
  moveRight(id: string, etag: string, by: number): Observable<LineWithETag> {
    return this.http
      .put<Line>(`/lines/${id}/right`, { by }, { headers: { 'If-Match': etag }, observe: 'response' })
      .pipe(map((response) => toLineWithETag(response)));
  }
}
