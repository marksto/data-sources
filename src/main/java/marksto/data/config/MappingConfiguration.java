package marksto.data.config;

import com.fasterxml.jackson.databind.introspect.Annotated;
import com.fasterxml.jackson.databind.introspect.JacksonAnnotationIntrospector;
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter;
import com.fasterxml.jackson.databind.ser.PropertyWriter;
import com.fasterxml.jackson.databind.ser.impl.SimpleBeanPropertyFilter;
import com.fasterxml.jackson.databind.ser.impl.SimpleFilterProvider;
import com.google.common.collect.Lists;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import marksto.common.util.GeneralUtils;
import marksto.data.config.properties.DataMappingProperties;
import marksto.web.mapping.JsonResourcesMapper;
import org.apache.commons.beanutils.ConvertUtils;
import org.apache.commons.beanutils.converters.DoubleConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

/**
 * Configuration with everything related to mapping:
 * <ul>
 *     <li>JSON mapping</li>
 *     <li>domain objects to DTO</li>
 *     <li>MapStruct setup (if required)</li>
 * </ul>
 *
 * @author marksto
 * @since 18.02.2019
 */
@Configuration
@ComponentScan({
        "marksto.data.mapping"
})
@Import(marksto.data.config.KryoConfiguration.class)
@EnableConfigurationProperties({
        DataMappingProperties.class
})
@SuppressWarnings({"unused", "squid:S1118", "squid:S1172", "SpringFacetCodeInspection"})
public class MappingConfiguration {

    private static final String MAPPING_THREAD_NAME = "mapper-%d";

    @Bean
    public Scheduler mappingScheduler(DataMappingProperties dataMappingProperties) {
        final var mappingSchedulerThreadPoolSize = dataMappingProperties.getThreadPoolSize();
        return Schedulers.fromExecutor(Executors.newFixedThreadPool(mappingSchedulerThreadPoolSize,
                new ThreadFactoryBuilder().setDaemon(true).setNameFormat(MAPPING_THREAD_NAME).build())
        );
    }

    // -------------------------------------------------------------------------

    private static final String FILTER_NAME = "map-keys-excluding-filter";

    @Bean(name = "dataSourceMapper")
    public JsonResourcesMapper dataSourceMapper() {
        final List<String> keysToExclude = Lists.newArrayList(
                "name", "state", "status"
        );

        final var filterProvider = new SimpleFilterProvider();
        filterProvider.addFilter(FILTER_NAME, new SimpleBeanPropertyFilter() {
            @Override
            protected boolean include(BeanPropertyWriter writer) {
                return true;
            }
            @Override
            protected boolean include(PropertyWriter writer) {
                String key = writer.getName();
                return !keysToExclude.contains(key);
            }
        });

        // NOTE: Enables mapping introspection for 'java.util.Map' members.
        final var introspector = new JacksonAnnotationIntrospector() {
            @Override
            public Object findFilterId(Annotated a) {
                if (Map.class.isAssignableFrom(a.getRawType())) {
                    return FILTER_NAME;
                }
                return super.findFilterId(a);
            }
        };

        return new JsonResourcesMapper(objectMapper -> {
            objectMapper.setAnnotationIntrospector(introspector);
            objectMapper.setFilterProvider(filterProvider);
        });
    }

    // -------------------------------------------------------------------------

    @PostConstruct
    public void setUp() {
        // NOTE: We have to reset the default one for proper JSON 'number' type conversion.
        DoubleConverter doubleConverter = new DoubleConverter(0.0);
        doubleConverter.setUseLocaleFormat(true);
        doubleConverter.setLocale(GeneralUtils.RUSSIAN);

        ConvertUtils.register(doubleConverter, Double.TYPE);
        ConvertUtils.register(doubleConverter, Double.class);
    }

}
