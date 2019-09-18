package marksto.data.mapping;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import org.apache.commons.beanutils.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

import static marksto.common.util.FunctionalUtils.emptyAsNullWith;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

/**
 * A {@link LazyDynaMap}-based implementation.
 *
 * @author marksto
 * @since 20.04.2019
 */
public class MappingSchemaBean implements MappingSchema, DynaBean {

    private static final Logger LOG = LoggerFactory.getLogger(MappingSchemaBean.class);

    // -------------------------------------------------------------------------

    public static class MappingSchemaBeanSerializer extends Serializer<MappingSchemaBean> {
        @Override
        public void write(Kryo kryo, Output output, MappingSchemaBean object) {
            throw new UnsupportedOperationException("No need in write, only copy");
        }

        @Override
        public MappingSchemaBean read(Kryo kryo, Input input, Class<? extends MappingSchemaBean> type) {
            throw new UnsupportedOperationException("No need in read, only copy");
        }

        @Override
        public MappingSchemaBean copy(Kryo kryo, MappingSchemaBean original) {
            LazyDynaMap lazyDynaMapCopy = kryo.copy(original.lazyDynaMap);
            Map<String, Class<?>> propTypeMap = kryo.copy(original.propTypeMap);
            MappingSchemaBean copy = new MappingSchemaBean(lazyDynaMapCopy, propTypeMap, kryo);
            kryo.reference(copy);
            return copy;
        }
    }

    // -------------------------------------------------------------------------

    private final LazyDynaMap lazyDynaMap;
    private final Kryo kryo;

    private final Map<String, Class<?>> propTypeMap;

    public MappingSchemaBean(Kryo kryo) {
        this(new LazyDynaMap(), new HashMap<>(), kryo);
    }
    private MappingSchemaBean(LazyDynaMap lazyDynaMap, Map<String, Class<?>> propTypeMap, Kryo kryo) {
        this.lazyDynaMap = lazyDynaMap;
        this.propTypeMap = propTypeMap;
        this.kryo = kryo;
    }

    @Override
    public DynaClass getDynaClass() {
        return lazyDynaMap.getDynaClass();
    }

    @Override
    public boolean exists(String name) {
        // NOTE: Uses the fact that the 'lazyDynaMap' won't create an ad-hoc
        //       property (this is what requires the "setReturnNull(true)").
        boolean tmp = lazyDynaMap.isReturnNull();
        try {
            lazyDynaMap.setReturnNull(true);
            return lazyDynaMap.getDynaProperty(name) != null;
        } finally {
            lazyDynaMap.setReturnNull(tmp);
        }
    }

    @Override
    public void add(String name, Class<?> type) {
        // NOTE: Method 'add' auto-tests property for existence.
        lazyDynaMap.add(name, type);
        propTypeMap.put(name, type);
    }

    @Override
    public Object get(String name) {
        return lazyDynaMap.get(name);
    }

    @Override
    public Object get(String name, int index) {
        return lazyDynaMap.get(name, index);
    }

    @Override
    public Object get(String name, String key) {
        return lazyDynaMap.get(name, key);
    }

    @Override
    public void set(String name, Object value) {
        try {
            Object converted = convertTypeIfNecessary(name, value);
            PropertyUtils.setProperty(lazyDynaMap, name, converted);
        } catch (ReflectiveOperationException roe) {
            LOG.warn("Failed to set simple property '{}'", name);
        }
    }

    @Override
    public void set(String name, int index, Object value) {
        try {
            Object converted = convertTypeIfNecessary(name, value);
            PropertyUtils.setIndexedProperty(lazyDynaMap, name, index, converted);
        } catch (ReflectiveOperationException roe) {
            LOG.warn("Failed to set indexed property '{}'", name);
        }
    }

    @Override
    public void set(String name, String key, Object value) {
        try {
            Object converted = convertTypeIfNecessary(name, value);
            PropertyUtils.setMappedProperty(lazyDynaMap, name, key, converted);
        } catch (ReflectiveOperationException roe) {
            LOG.warn("Failed to set mapped property '{}'", name);
        }
    }

    @SuppressWarnings("unchecked")
    private Object convertTypeIfNecessary(String name, Object value) {
        Class<?> requiredType = propTypeMap.get(name);

        if (requiredType == null) {
            // NOTE: We get here in two different cases:
            //       - the call happened on a non-leaf property
            //       - there is no information on property type
            //       In both cases the type conversion is to be
            //       dropped out, so we return the value as is.
            return value;
        }

        // collection property value case
        if (value instanceof Collection) {
            return ((Collection) value).parallelStream()
                .map(item -> ConvertUtils.convert(item, requiredType))
                .collect(toList());
        }

        // map property value case
        if (value instanceof Map) {
            return ((Map<String, Object>) value).entrySet().parallelStream()
                .map(entry -> Map.entry(entry.getKey(),
                    ConvertUtils.convert(entry.getValue(), requiredType)))
                .collect(toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        return ConvertUtils.convert(value, requiredType);
    }

    @Override
    public boolean contains(String name, String key) {
        return lazyDynaMap.contains(name, key);
    }

    @Override
    public void remove(String name, String key) {
        lazyDynaMap.remove(name, key);
    }

    @Override
    public MappingSchema createCopy() {
        return kryo.copy(this);
    }

    @Override
    public Map<String, Object> asMap() {
        return asMap_processMap(lazyDynaMap.getMap());
    }

    @SuppressWarnings("squid:S00100")
    private Map<String, Object> asMap_processMap(Map<String, Object> map) {
        return map.entrySet().stream()
                .peek(entry -> entry.setValue(asMap_processEntry(entry.getValue())))
                .filter(entry -> entry.getValue() != null) // there is no need in shallow entries
                .collect(emptyAsNullWith(toMap(Map.Entry<String, Object>::getKey, Map.Entry::getValue), Map::isEmpty));
    }

    @SuppressWarnings({"unchecked", "squid:S00100"})
    private Object asMap_processEntry(Object object) {
        Object result = object;
        if (object instanceof MappingSchemaBean) {
            result = ((MappingSchemaBean) object).asMap();
        } else if (object instanceof Collection) {
            // collection property value case
            result = ((Collection) object).stream()
                .map(this::asMap_processEntry)
                .filter(Objects::nonNull)
                .collect(toList());
        } else if (object instanceof Map) {
            // map property value case
            result = asMap_processMap((Map) object);
        }
        return result;
    }

}
