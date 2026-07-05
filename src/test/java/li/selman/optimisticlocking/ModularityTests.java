package li.selman.optimisticlocking;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.docs.Documenter;

/**
 * Verifies the module structure declared via {@code package-info.java} (see {@code line} and
 * {@code shared}) and regenerates the Spring Modulith documentation (component diagram + one
 * canvas per module) under {@code target/spring-modulith-docs} on every build.
 */
class ModularityTests {

    private final ApplicationModules modules = ApplicationModules.of(OptimisticLockingApplication.class);

    @Test
    void verifiesModularStructure() {
        modules.verify();
    }

    @Test
    void createModuleDocumentation() {
        new Documenter(modules).writeDocumentation();
    }
}
