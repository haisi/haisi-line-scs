package li.selman.optimisticlocking.line;

import jakarta.transaction.Transactional;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

/**
 * Gives the Angular app's lines list something to show on a fresh H2 database -- the schema
 * starts empty and this teaching repo has no {@code data.sql}. Package-local so it can use {@link
 * Line}'s own package-private constructor directly (the same access {@link LineService#create}
 * and the test-only {@code LineFixture} rely on), bypassing {@link LineAuthorization} entirely:
 * there's no authenticated caller yet at application startup to check a role against.
 */
@Component
@Profile("local")
class LocalDataSeeder implements ApplicationRunner {

    private static final String BUSINESS_PARTNER_ID = "acme";

    private final LineRepository repo;

    LocalDataSeeder(LineRepository repo) {
        this.repo = repo;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (repo.findAll(Pageable.ofSize(1)).hasContent()) {
            return; // already seeded (e.g. a prior run against a persistent, not in-memory, DB)
        }
        List.of(
                        new Line(new LineId(UUID.randomUUID()), 0, 5, BUSINESS_PARTNER_ID),
                        new Line(new LineId(UUID.randomUUID()), 1, 3, BUSINESS_PARTNER_ID),
                        new Line(new LineId(UUID.randomUUID()), 2, 8, BUSINESS_PARTNER_ID),
                        new Line(new LineId(UUID.randomUUID()), 0, 10, BUSINESS_PARTNER_ID),
                        new Line(new LineId(UUID.randomUUID()), 1, 10, BUSINESS_PARTNER_ID),
                        new Line(new LineId(UUID.randomUUID()), 50, 99, BUSINESS_PARTNER_ID),
                        new Line(new LineId(UUID.randomUUID()), 4, 4, BUSINESS_PARTNER_ID))
                .forEach(repo::save);
    }
}
