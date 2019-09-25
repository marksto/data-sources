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

    private static final String NO_DATA_MAPPING = "No 'data-mapping.json' file provided, only the default one is used";

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
    private static final String DATA_SOURCES_MAPPING_KEY = "dataSources";

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

        if (dataMapping != null) {
            dataMapping.put(DATA_SOURCES_MAPPING_KEY, dataSourceMapping.get(DATA_SOURCES_MAPPING_KEY));
        } else {
            dataMapping = dataSourceMapping;
        }

        return dataMapping;
    }

    private DataMapping loadDataMappingFile(String dataMappingPath) {
        return webServerService.getResourceAsText(dataMappingPath)
                .map(jsonStr -> DataMapping.load().fromJson(jsonStr))
                .onFailure(t -> LOG.warn(NO_DATA_MAPPING))
                .getOrElse((DataMapping) null);
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
