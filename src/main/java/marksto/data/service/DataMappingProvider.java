package marksto.data.service;

import marksto.data.schemas.DataMapping;

/**
 * Service for lower-level {@link DataMapping} entities manipulations: retrieval, caching, and eviction.
 *
 * @author marksto
 * @since 13.05.2019
 */
@SuppressWarnings("unused")
public interface DataMappingProvider {

    /**
     * Retrieves the {@link DataMapping} that is currently used.
     */
    DataMapping retrieveDataMapping();

    void reloadDataMapping();

}
