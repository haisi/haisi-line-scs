package li.selman.optimisticlocking.line.web;

/** Fields of an embedded {@code leftPoint}/{@code rightPoint} sub-resource -- see {@link LineModelAssembler}. */
record PointRepresentation(Long id, int position, int numberOfUpdates) {}
