import { HttpErrorResponse } from '@angular/common/http';

export interface ProblemDetailFieldError {
  field: string;
  message: string;
}

/** The RFC 9457 `application/problem+json` shape every error response in the backend uses. */
export interface ProblemDetail {
  title?: string;
  status?: number;
  detail?: string;
  instance?: string;
  errors?: ProblemDetailFieldError[];
}

function isProblemDetail(value: unknown): value is ProblemDetail {
  return typeof value === 'object' && value !== null && ('title' in value || 'detail' in value);
}

export function toProblemDetail(error: unknown): ProblemDetail {
  if (error instanceof HttpErrorResponse && isProblemDetail(error.error)) {
    return error.error;
  }
  return { detail: 'An unexpected error occurred.' };
}
