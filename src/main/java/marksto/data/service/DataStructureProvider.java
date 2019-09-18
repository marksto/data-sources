package marksto.data.service;

import marksto.data.schemas.DataSource;
import marksto.data.schemas.DataStructure;
import reactor.core.publisher.Mono;

/**
 * Provides a {@link DataStructure} of the particular {@link DataSource}.
 *
 * @author marksto
 * @since 25.05.2019
 */
public interface DataStructureProvider {

    /**
     * Retrieves the remote {@link DataStructure} for a particular {@link DataSource}
     * (that might change over time, e.g. if a new named value ranges were added).
     *
     * @param dataSource a {@link DataSource} to provide {@link DataStructure} for
     */
    Mono<DataStructure> retrieveDataStructure(DataSource dataSource);

}
