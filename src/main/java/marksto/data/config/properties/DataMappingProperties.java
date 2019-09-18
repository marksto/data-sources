package marksto.data.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * @author marksto
 * @since 03.06.2019
 */
@ConfigurationProperties("app.data.mapping")
public class DataMappingProperties {

    /**
     * Relative path to the Data Mapping metadata JSON file.
     */
    private String path;

    /**
     * Sets the expiration (eviction) period for cached <em>Data Mapping</em>.<br/>
     * The default is to never expire the cached entry.
     */
    @DurationUnit(ChronoUnit.HOURS)
    private Duration expireCacheEvery;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public Duration getExpireCacheEvery() {
        return expireCacheEvery;
    }

    public void setExpireCacheEvery(Duration expireCacheEvery) {
        this.expireCacheEvery = expireCacheEvery;
    }

}
