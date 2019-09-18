package marksto.data.mapping.dto;

import com.google.common.base.Preconditions;
import marksto.data.mapping.ValueRangesMapper;
import marksto.data.model.DomainType;

import java.util.List;

/**
 * DTO for use with {@link ValueRangesMapper}
 * as its {@code #map()} method return type.
 *
 * @param <T> type that corresponds to the {@code domainType#getClass()} one
 *
 * @author marksto
 * @since 21.04.2019
 */
public class TypedObjects<T> {

    private final DomainType<T> domainType;
    private final List<T> objectsOfType;

    public TypedObjects(DomainType<T> domainType, List<T> objectsOfType) {
        Preconditions.checkArgument(domainType != null);

        this.domainType = domainType;
        this.objectsOfType = objectsOfType;
    }

    public DomainType<T> getDomainType() {
        return domainType;
    }

    public List<T> getObjectsOfType() {
        return objectsOfType;
    }

}
