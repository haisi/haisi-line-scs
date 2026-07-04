package li.selman.optimisticlocking;

import static org.assertj.core.api.Assertions.assertThat;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import java.io.IOException;
import java.util.List;
import li.selman.nullmarkeder.PackageInfoGenerator;
import org.jspecify.annotations.NullMarked;

@AnalyzeClasses(packages = "li.selman", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    private static final String ROOT_PACKAGE = "li.selman";

    /** Enforce that all packages contain a `package-info.java` annotated with `@NullMarked`.
     * Run {@link PackageInfoGenerator} to fix. */
    @ArchTest
    void packagesShouldBeAnnotated(JavaClasses classes) throws IOException {
        var rootPackage = classes.getPackage(ROOT_PACKAGE);
        List<String> violations = rootPackage.getSubpackagesInTree().stream()
                .filter(pkg -> !pkg.isAnnotatedWith(NullMarked.class))
                .map(pkg -> pkg.getDescription() + " is not annotated with @" + NullMarked.class.getSimpleName())
                .toList();

        if (!violations.isEmpty()) {
            PackageInfoGenerator.main(ROOT_PACKAGE);
        }

        assertThat(violations)
                .as("Not all packages contain a package-info.java file with the required nullability annotations. "
                        + "Ran PackageInfoGenerator to fix - re-run the build.")
                .isEmpty();
    }
}
