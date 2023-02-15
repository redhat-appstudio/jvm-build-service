package io.github.redhatappstudio.jvmbuild.cli;

import java.util.Arrays;
import java.util.List;

import io.quarkus.arc.Arc;
import io.quarkus.arc.InstanceHandle;
import picocli.CommandLine;
import picocli.CommandLine.IFactory;

/**
 * <p>
 * Can serve for {@link #create(Class)} from a list of given instances or
 * delegates to a {@link CommandLine#defaultFactory()} if no objects for class
 * available.
 * <p>
 * Usually this would be done with
 * <a href="https://picocli.info/#_dependency_injection">dependency injection</a>.
 *
 * @since 4.0
 * @see <a href="https://picocli.info/#_dependency_injection">https://picocli.info/#_dependency_injection</a>
 */
public class CustomFactory implements IFactory {

    private final IFactory factory = CommandLine.defaultFactory();
    private final List<Object> instances;

    public CustomFactory(Object... instances) {
        this.instances = Arrays.asList(instances);
    }

    public <K> K create(Class<K> cls) throws Exception {
        for (Object obj : instances) {
            if (cls.isAssignableFrom(obj.getClass())) {
                return cls.cast(obj);
            }
        }
        InstanceHandle<K> instance = Arc.container().instance(cls);
        if (instance.isAvailable()) {
            return instance.get();
        }
        return factory.create(cls);
    }
}
