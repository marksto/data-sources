package marksto.data.config;

import marksto.data.config.properties.*;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/**
 * @author marksto
 * @since 17.09.2019
 */
@Configuration
@ComponentScan({
        "marksto.data.service",
        "marksto.data.events.service",
        "marksto.data.integration.service"
})
@EnableConfigurationProperties({
        SheetsProperties.class,
        DataSourcesProperties.class,
        DataMappingProperties.class,
        DataProvidersProperties.class
})
public class ServiceConfiguration {

    @Bean
    public Scheduler remoteCallsScheduler() {
        return Schedulers.newElastic("remote-calls", 60, true);
    }

}
