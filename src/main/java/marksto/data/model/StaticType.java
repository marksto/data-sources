package marksto.data.model;

import manifold.api.json.IJsonBindingsBacked;

/**
 * The one that is pre-compiled and thus known beforehand in runtime.
 *
 * @since 20.06.2019
 * @author marksto
 */
public class StaticType<T extends IJsonBindingsBacked> implements DomainType<T> {

    private final String name;
    private final Class<T> typeClass;

    public StaticType(String name, Class<T> typeClass) {
        this.name = name;
        this.typeClass = typeClass;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Class<T> getTypeClass() {
        return typeClass;
    }

    @Override
    public String toString() {
        return getName();
    }

}
