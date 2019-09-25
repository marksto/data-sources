package marksto.data.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Collections;
import java.util.List;

/**
 * @author marksto
 * @since 03.06.2019
 */
@ConfigurationProperties("app.data.sources")
public class DataSourcesProperties {

    /**
     * A set of <em>Google Spreadsheets</em> IDs for Data Sources
     * that need to be instantiated on the application startup.
     */
    private List<String> sheetsIds = Collections.emptyList();

    /**
     * A default Data Source name prefix used for auto-generated names.
     */
    private String defaultNamePrefix;

    /**
     * An automatic Data Source re-initialization attempt timeout.<br/>
     * The default value is {@code 10} second.
     */
    @DurationUnit(ChronoUnit.SECONDS)
    private Duration autoReInitIn = Duration.ofSeconds(10);

    /**
     * Number of automatic Data Source re-initialization attempts.<br/>
     * The default value is {@code 1} attempt.
     */
    private int reInitRetriesNum = 1;

    /**
     * Relative path to the Data Sources metadata JSON file.
     */
    private String path;

    public List<String> getSheetsIds() {
        return sheetsIds;
    }

    public void setSheetsIds(List<String> sheetsIds) {
        this.sheetsIds = sheetsIds;
    }

    public String getDefaultNamePrefix() {
        return defaultNamePrefix;
    }

    public void setDefaultNamePrefix(String defaultNamePrefix) {
        this.defaultNamePrefix = defaultNamePrefix;
    }

    public Duration getAutoReInitIn() {
        return autoReInitIn;
    }

    public void setAutoReInitIn(Duration autoReInitIn) {
        this.autoReInitIn = autoReInitIn;
    }

    public int getReInitRetriesNum() {
        return reInitRetriesNum;
    }

    public void setReInitRetriesNum(int reInitRetriesNum) {
        this.reInitRetriesNum = reInitRetriesNum;
    }

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

}
