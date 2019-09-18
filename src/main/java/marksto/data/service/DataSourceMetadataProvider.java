package marksto.data.service;

import marksto.data.schemas.DataSource;
import marksto.data.mapping.dto.TypedObjects;
import reactor.core.publisher.Mono;

/**
 * Provides a complete metadata of the particular {@link DataSource}.
 *
 * @author marksto
 * @since 25.05.2019
 */
public interface DataSourceMetadataProvider {

    /**
     * Retrieves the remote metadata for a particular {@link DataSource}
     * (that might change over time, e.g. if a new dependency was added).
     *
     * @param dataSource a {@link DataSource} to provide metadata for
     */
    Mono<TypedObjects<DataSource>> provideMetadataFor(DataSource dataSource);

}
