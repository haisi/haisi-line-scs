package li.selman.optimisticlocking.line.web;

import li.selman.optimisticlocking.line.LineId;

/** Top-level fields of a {@code Line}'s HAL representation -- see {@link LineModelAssembler}. */
record LineSummary(LineId id, String businessPartnerId) {}
