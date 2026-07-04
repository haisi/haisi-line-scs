package li.selman.optimisticlocking.shared;

import org.jmolecules.ddd.types.AggregateRoot;
import org.springframework.hateoas.server.core.Relation;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * Helper class to work with commands of a given aggregate.
 * @param <A> the type of the aggregate
 * @param <C> the type of the aggregate command parent interface
 * @author <a href="https://github.com/sth77">Stefan Heinzer</a>
 */
public final class AggregateCommands<A extends AggregateRoot<?, ?>, C extends Command> {

    private final Class<A> aggregateType;
    private final Class<C> commandType;

    public AggregateCommands(Class<A> aggregateType, Class<C> commandType) {
        this.aggregateType = aggregateType;
        this.commandType = commandType;
    }

    @SuppressWarnings("unchecked")
    public List<Class<C>> getCommands() {
        return Arrays.stream(commandType.getPermittedSubclasses())
                .map(it -> (Class<C>) it)
                .toList();
    }

    public String getRel(Class<? extends C> commandType) {
        var annotatedRel = Optional.ofNullable(commandType.getAnnotation(Relation.class));
        return annotatedRel
                .map(Relation::value)
                .orElseGet(() -> extractRelFromCommandType(commandType));
    }

    private String extractRelFromCommandType(Class<? extends C> commandType) {
        var strippedCommandName = commandType.getSimpleName().replace(aggregateType.getSimpleName(), "");
        return firstCharToLowerCase(!strippedCommandName.isEmpty()
                ? strippedCommandName
                : commandType.getSimpleName());
    }

    private String firstCharToLowerCase(String str) {
        return str.substring(0, 1).toLowerCase() + str.substring(1);
    }

}
