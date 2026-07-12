/**
 * Local-development-only substitutes for the real IdP/SSO login this teaching repo has no
 * infrastructure for (see {@code application.properties}' placeholder OAuth2 issuer). Every class
 * here is gated behind {@code @Profile("local")} and must never be reachable outside a developer
 * running {@code -Dspring-boot.run.profiles=local} on their own machine.
 */
@InfrastructureRing
@NullMarked
package li.selman.optimisticlocking.shared.web.localdev;

import org.jmolecules.architecture.onion.simplified.InfrastructureRing;
import org.jspecify.annotations.NullMarked;
