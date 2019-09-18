package marksto.data.model;

import java.util.Arrays;

import static java.util.stream.Collectors.toList;

/**
 * The type of a particular <em>business entity</em>.
 */
public interface DomainType<T> {

    String getName();

    Class<T> getTypeClass();

    static String[] getNames(DomainType... domainTypes) {
        return Arrays.stream(domainTypes)
            .map(DomainType::getName)
            .collect(toList())
            .toArray(new String[domainTypes.length]);
    }

}
