export interface LineLink {
  href: string;
}

/**
 * `delete`, `move-left`, and `move-right` are HATEOAS affordances the backend only includes when
 * the caller actually holds the matching role for this specific line -- see
 * LineRepresentationModelProcessor. Gating the delete/edit actions on these links' presence is a
 * true server-computed visibility gate, not a client-side permission re-implementation.
 */
export interface LineLinks {
  self: LineLink;
  delete?: LineLink;
  'move-left'?: LineLink;
  'move-right'?: LineLink;
}

export interface Line {
  id: string;
  left: number;
  right: number;
  businessPartnerId: string;
  _links: LineLinks;
}

export interface LinesPage {
  content: Line[];
  page: {
    size: number;
    number: number;
    totalElements: number;
    totalPages: number;
  };
}

export interface LineWithETag {
  line: Line;
  etag: string;
}
