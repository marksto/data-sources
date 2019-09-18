package marksto.data.events.dto;

/**
 * DTO for {@link marksto.data.schemas.DataSource}-related information.
 * <p>
 * IMPLEMENTATION NOTE: Used mostly for better frontend semantics with
 *                      the underlying {@link java.util.EventObject}.
 *                      Now, client code receives JSON with an obvious
 *                      {@code data.source.name} field.
 */
public class DataSourceInfo {

    private final String name;

    public DataSourceInfo(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

}
