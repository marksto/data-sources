package marksto.data.config;

import marksto.data.config.properties.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * @author marksto
 * @since 17.09.2019
 */
@Configuration
@Import(marksto.web.config.ServiceConfiguration.class)
@ComponentScan({
        "marksto.data.service",
        "marksto.data.events.service",
        "marksto.data.integration.service"
})
@EnableConfigurationProperties({
        SheetsProperties.class,
        DataSourcesProperties.class,
        DataProvidersProperties.class
})
@SuppressWarnings("SpringFacetCodeInspection")
public class ServiceConfiguration {

    @Bean
    public Scheduler remoteCallsScheduler() {
        return Schedulers.newElastic("remote-calls", 60, true);
    }

}
