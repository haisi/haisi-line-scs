package li.selman.optimisticlocking.shared.web;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Opts a controller method (or every method of a controller class) into {@link
 * IdempotencyFilter}'s {@code Idempotency-Key} handling. An endpoint without this annotation
 * ignores the header entirely, even if a client sends it -- matching Stripe's own stance that
 * already-idempotent/safe methods (GET, DELETE) shouldn't participate at all, and failing safe for
 * any future endpoint nobody has deliberately opted in.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface Idempotent {}
