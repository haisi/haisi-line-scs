package li.selman.optimisticlocking.line.web;

import java.time.Instant;

/** One audit-trail entry, embedded under a line's HAL representation -- see {@link LineModelAssembler}. */
record ManualOperationRepresentation(String operation, String detail, String performedBy, Instant performedAt) {}
