package marksto.data.service.impl;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import marksto.data.config.properties.DataMappingProperties;
import marksto.data.schemas.DataMapping;
import marksto.data.service.DataMappingProvider;
import marksto.web.service.WebServerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;

@Service
public class DataMappingProviderImpl implements DataMappingProvider {

    private static final Logger LOG = LoggerFactory.getLogger(DataMappingProviderImpl.class);

    private static final String JSON_PARSING_ERROR_MSG = "Failed to parse 'data-mapping.json' file";

    // -------------------------------------------------------------------------

    private final WebServerService webServerService;

    private final DataMappingProperties dataMappingProperties;

    public DataMappingProviderImpl(WebServerService webServerService,
                                   DataMappingProperties dataMappingProperties) {
        this.webServerService = webServerService;
        this.dataMappingProperties = dataMappingProperties;
    }

    // -------------------------------------------------------------------------

    private static final String DEFAULT_DATA_MAPPING_PATH = "default-data-mapping.json";
    private static final String DATA_SOURCE_KEY = "dataSource";

    private static final Object SINGLE_DATA_MAPPING = new Object();

    private LoadingCache<Object, DataMapping> dataMappingCache;

    @PostConstruct
    public void setUp() {
        var cacheBuilder = Caffeine.newBuilder();
        var expiresAfter = dataMappingProperties.getExpireCacheEvery();
        if (expiresAfter != null) {
            cacheBuilder.expireAfterWrite(expiresAfter);
        }
        dataMappingCache = cacheBuilder.<Object, DataMapping>
                build(key -> key == SINGLE_DATA_MAPPING ? combineDataMapping() : null);
    }

    private DataMapping combineDataMapping() {
        final var dataMappingPath = dataMappingProperties.getPath();

        DataMapping dataMapping = loadDataMappingFile(dataMappingPath);
        DataMapping dataSourceMapping = loadDataMappingFile(DEFAULT_DATA_MAPPING_PATH);
        dataMapping.put(DATA_SOURCE_KEY, dataSourceMapping.get(DATA_SOURCE_KEY));

        return dataMapping;
    }

    private DataMapping loadDataMappingFile(String dataMappingPath) {
        return webServerService.getResourceAsText(dataMappingPath)
                .map(jsonStr -> DataMapping.load().fromJson(jsonStr))
                .onFailure(t -> LOG.error(JSON_PARSING_ERROR_MSG, t))
                .getOrElseThrow(ex -> new IllegalStateException(JSON_PARSING_ERROR_MSG, ex));
    }

    // -------------------------------------------------------------------------

    @Override
    public DataMapping retrieveDataMapping() {
        return dataMappingCache.get(SINGLE_DATA_MAPPING);
    }

    @Override
    public void reloadDataMapping() {
        dataMappingCache.refresh(SINGLE_DATA_MAPPING);
    }

}
