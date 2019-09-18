package marksto.data.mapping;

import java.util.Map;

/**
 * A contract for entity involved in any mapping-related work.
 *
 * Takes nested properties into account, i.e. allows to set them with "values[0].codes.str".
 *
 * Supports multilevel nested structure that allows for a (deep) copying for the ease of schema multi-instantiation.
 *
 * @author marksto
 * @since 20.04.2019
 */
public interface MappingSchema {

    boolean exists(String name);

    void add(String name, Class<?> type);

    Object get(String name);
    Object get(String name, int index);
    Object get(String name, String key);

    void set(String name, Object value);
    void set(String name, int index, Object value);
    void set(String name, String key, Object value);

    MappingSchema createCopy();

    Map<String, Object> asMap();

}
