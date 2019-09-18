package marksto.data.service;

import marksto.data.mapping.dto.TypedObjects;
import marksto.data.model.DomainType;
import reactor.core.publisher.Flux;

/**
 * A service used for data retrieval from the remote <em>Data Sources</em>.
 *
 * @author marksto
 * @since 13.05.2019
 */
public interface DataRetrievalService {

    /**
     * Retrieves data from the registered <em>Data Sources</em> based on its type.
     *
     * @param domainTypes a set of {@link DomainType} objects (business entities)
     */
    Flux<TypedObjects> getDataFrom(DomainType<?>... domainTypes);

}
