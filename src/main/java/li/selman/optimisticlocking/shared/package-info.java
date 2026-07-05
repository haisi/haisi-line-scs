/**
 * Shared kernel: cross-cutting concurrency/idempotency vocabulary ({@code IfMatch}/{@code
 * IfNoneMatch}, the {@code 409}/{@code 412}/{@code 422}/{@code 428} exception types, and the
 * {@code idempotency} sub-package) that {@code line} depends on. Declared {@code OPEN} because it
 * has no dependencies of its own on other modules and is meant to be freely reusable by every
 * application module, including its {@code idempotency}/{@code web} internals.
 */
@ApplicationModule(displayName = "Shared Kernel", type = ApplicationModule.Type.OPEN)
@DomainRing
@NullMarked
package li.selman.optimisticlocking.shared;

import org.jmolecules.architecture.onion.simplified.DomainRing;
import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.ApplicationModule;
