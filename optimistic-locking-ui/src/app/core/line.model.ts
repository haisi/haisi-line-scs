export interface LineLink {
  href: string;
}

/** `delete` is a HATEOAS affordance the backend only includes when the caller holds `line_#delete` for this line. */
export interface LineLinks {
  self: LineLink;
  delete?: LineLink;
}

/**
 * `moveLeft`/`moveRight` are HATEOAS affordances the backend only includes when the caller holds
 * the matching edit role *and* the move would currently succeed for this point/direction -- see
 * LineModelAssembler. Gating a move button on these links' presence is a true server-computed
 * visibility gate, not a client-side re-implementation of the domain invariants.
 */
export interface PointLinks {
  moveLeft?: LineLink;
  moveRight?: LineLink;
}

/**
 * `_links` itself is absent, not just empty, when a point has no move affordances at all (e.g. the
 * update budget is fully spent) -- Spring HATEOAS's HAL serialization omits an empty `_links` object
 * entirely rather than writing `{}`. Every reader must treat it as optional, not assume its presence.
 */
export interface Point {
  id: number;
  position: number;
  numberOfUpdates: number;
  _links?: PointLinks;
}

/**
 * One entry of a line's audit trail. Generic (an `operation` name plus a free-text `detail`), not
 * move-specific -- `LineService` is currently the only writer, but the shape doesn't assume that.
 */
export interface ManualOperation {
  operation: string;
  detail: string;
  performedBy: string;
  performedAt: string;
}

/** The single-`Line` representation returned by `GET/PUT /lines/{id}...` -- each point is its own embedded resource. */
export interface Line {
  id: string;
  businessPartnerId: string;
  _links: LineLinks;
  _embedded: {
    leftPoint: Point;
    rightPoint: Point;
    operations: ManualOperation[];
  };
}

/** The flat shape `GET /lines` still returns per row -- no per-point move affordances needed in a list. */
export interface LineSummary {
  id: string;
  left: number;
  right: number;
  businessPartnerId: string;
}

export interface LinesPage {
  content: LineSummary[];
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
