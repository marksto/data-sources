package marksto.data.config;

import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import marksto.data.mapping.MappingSchemaBean;
import org.apache.commons.beanutils.LazyDynaMap;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;

import static marksto.data.mapping.MappingSchemaBean.MappingSchemaBeanSerializer;

/**
 * @author marksto
 */
@Configuration
@SuppressWarnings("SpringFacetCodeInspection")
public class KryoConfiguration {

    private static class LazyDynaMapSerializer extends Serializer<LazyDynaMap> {
        @Override
        public void write(Kryo kryo, Output output, LazyDynaMap object) {
            throw new UnsupportedOperationException("No need in write, only copy");
        }

        @Override
        public LazyDynaMap read(Kryo kryo, Input input, Class<? extends LazyDynaMap> type) {
            throw new UnsupportedOperationException("No need in read, only copy");
        }

        @Override
        public LazyDynaMap copy(Kryo kryo, LazyDynaMap original) {
            LazyDynaMap copy = new LazyDynaMap();
            kryo.reference(copy);
            copy.setMap(kryo.copy(original.getMap()));
            return copy;
        }
    }

    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    @Bean
    public Kryo kryo() {
        final Kryo kryo = new Kryo();
        kryo.setRegistrationRequired(false);
        kryo.register(LazyDynaMap.class, new LazyDynaMapSerializer());
        kryo.register(MappingSchemaBean.class, new MappingSchemaBeanSerializer());
        return kryo;
    }

    /**
     * IMPORTANT NOTICE: The {@link Kryo} itself is not thread-safe.
     */
    @Bean
    public KryoThreadLocal kryoThreadLocal() {
        return new KryoThreadLocal();
    }

    public static class KryoThreadLocal extends ThreadLocal<Kryo> implements ApplicationContextAware {

        private ApplicationContext applicationContext;

        @Override
        protected Kryo initialValue() {
            return applicationContext.getBean(Kryo.class);
        }

        @Override
        public void setApplicationContext(@NonNull ApplicationContext applicationContext) {
            this.applicationContext = applicationContext;
        }
    }

}
