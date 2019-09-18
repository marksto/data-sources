package marksto.data.service;

import marksto.data.schemas.DataSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Service for high-level {@link DataSource}s manipulations: registration, retrieval, and synchronization.
 *
 * @author marksto
 * @since 03.03.2019
 */
public interface DataSourcesService {

    /**
     * Registers a new {@link DataSource} object by the corresponding <em>Google Spreadsheet</em> ID.
     *
     * @param spreadsheetId to register as a new {@link DataSource} object
     */
    Mono<DataSource> registerNewDataSource(String spreadsheetId);

    /**
     * Registers a new {@link DataSource} object.
     *
     * @param dataSource to register (must have a non-{@code null} {@code spreadsheetId})
     */
    Mono<DataSource> registerNewDataSource(DataSource dataSource);

    // -------------------------------------------------------------------------

    /**
     * Retrieves a particular {@link DataSource}, invalidating the cached value if {@code forceRemote}.
     *
     * @param name a unique identifier of the {@link DataSource} to retrieve
     * @param forceRemote either return a remote data if {@code true}, or cached otherwise
     */
    Mono<DataSource> retrieveDataSource(String name, boolean forceRemote);

    /**
     * Retrieves all registered {@link DataSource}s, invalidating the cached values if {@code forceRemote}.
     *
     * @param forceRemote either return a remote data if {@code true}, or cached otherwise
     */
    Flux<DataSource> retrieveDataSources(boolean forceRemote);

    // -------------------------------------------------------------------------

    /**
     * Synchronizes {@link DataSource}s, i.e. updates the dependent data between them and, possibly, their
     * dependent sub-graph.
     *
     * @param dependencyName a unique identifier of the dependency {@link DataSource} (i.e. where to "paste")
     * @param dependentName a unique identifier of the dependent {@link DataSource} (i.e. from where to "copy");
     *                      may be {@code null}, in which case all dependants of the {@code dependencyName}
     *                      will be synchronized
     * @param andSubGraph whether to run a cascading updates for all dependent {@link DataSource}s or not
     */
    Mono<Void> synchronizeDataBetween(String dependencyName, String dependentName, boolean andSubGraph);

    /**
     * Starts an overall data synchronization process for all registered {@link DataSource}s.
     * <br/>
     * <b>NOTE:</b> This usually takes a few minutes in case of large data sets.
     */
    Mono<Void> synchronizeData();

}
