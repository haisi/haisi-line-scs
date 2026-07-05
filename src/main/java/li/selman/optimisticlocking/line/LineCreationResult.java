package li.selman.optimisticlocking.line;

import org.jmolecules.architecture.onion.simplified.DomainRing;

/**
 * Result of a PUT-create: {@code created} tells the controller whether to answer 201 (a new
 * line was persisted) or 200 (the id already held identical content, so the request was a
 * no-op replay of an earlier create).
 */
@DomainRing
public record LineCreationResult(Line line, boolean created) {}
