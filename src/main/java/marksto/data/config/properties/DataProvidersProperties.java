package marksto.data.config.properties;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.convert.DurationUnit;

import java.time.Duration;
import java.time.temporal.ChronoUnit;

/**
 * @author marksto
 * @since 17.09.2019
 */
@ConfigurationProperties("app.data.providers")
public class DataProvidersProperties {

    /**
     * The configuration of a "retry with exponential backoff" strategy for the
     * <em>remote Data Source metadata retrieval</em> operation.<br/>
     * The defaults are: {@code 3} retries with an exponential delay
     * of {@code 500} milliseconds.
     */
    private long retrieveMetadataRetriesNum = 3;
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration retrieveMetadata1stBackoff = Duration.ofMillis(500);

    /**
     * The configuration of a "retry with exponential backoff" strategy for the
     * <em>remote Data Source's Data Structure retrieval</em> operation.<br/>
     * The defaults are: {@code 3} retries with an exponential delay
     * of {@code 500} milliseconds.
     */
    private long retrieveDataStructureRetriesNum = 3;
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration retrieveDataStructure1stBackoff = Duration.ofMillis(500);

    /**
     * The configuration of a "retry with exponential backoff" strategy for the
     * <em>remote Data Source data retrieval</em> operation.<br/>
     * The defaults are: {@code 3} retries with an exponential delay
     * of {@code 500} milliseconds.
     */
    private long retrieveDataRetriesNum = 3;
    @DurationUnit(ChronoUnit.MILLIS)
    private Duration retrieveData1stBackoff = Duration.ofMillis(500);

    public long getRetrieveMetadataRetriesNum() {
        return retrieveMetadataRetriesNum;
    }

    public void setRetrieveMetadataRetriesNum(long retrieveMetadataRetriesNum) {
        this.retrieveMetadataRetriesNum = retrieveMetadataRetriesNum;
    }

    public Duration getRetrieveMetadata1stBackoff() {
        return retrieveMetadata1stBackoff;
    }

    public void setRetrieveMetadata1stBackoff(Duration retrieveMetadata1stBackoff) {
        this.retrieveMetadata1stBackoff = retrieveMetadata1stBackoff;
    }

    public long getRetrieveDataStructureRetriesNum() {
        return retrieveDataStructureRetriesNum;
    }

    public void setRetrieveDataStructureRetriesNum(long retrieveDataStructureRetriesNum) {
        this.retrieveDataStructureRetriesNum = retrieveDataStructureRetriesNum;
    }

    public Duration getRetrieveDataStructure1stBackoff() {
        return retrieveDataStructure1stBackoff;
    }

    public void setRetrieveDataStructure1stBackoff(Duration retrieveDataStructure1stBackoff) {
        this.retrieveDataStructure1stBackoff = retrieveDataStructure1stBackoff;
    }

    public long getRetrieveDataRetriesNum() {
        return retrieveDataRetriesNum;
    }

    public void setRetrieveDataRetriesNum(long retrieveDataRetriesNum) {
        this.retrieveDataRetriesNum = retrieveDataRetriesNum;
    }

    public Duration getRetrieveData1stBackoff() {
        return retrieveData1stBackoff;
    }

    public void setRetrieveData1stBackoff(Duration retrieveData1stBackoff) {
        this.retrieveData1stBackoff = retrieveData1stBackoff;
    }

}
