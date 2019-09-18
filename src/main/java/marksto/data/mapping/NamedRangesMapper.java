package marksto.data.mapping;

import com.google.api.services.sheets.v4.model.NamedRange;
import marksto.data.schemas.DataStructure;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collector;

import static java.util.stream.Collectors.groupingBy;

@Service
public class NamedRangesMapper {

    // TODO: May require to re-think this design, e.g. with the help of Manifold's @Structural interface.
    private static final Collector<String, ?, Map<String, List<String>>> INTERNAL_COLLECTOR
            = groupingBy(NamedRangesMapper::getDataRangesGroup);

    private static String getDataRangesGroup(String dataRange) {
        int idx = dataRange.indexOf('_');
        return idx > 0 ? dataRange.substring(0, idx) : dataRange;
    }

    public DataStructure map(List<NamedRange> response) {
        return (DataStructure) response.stream()
                .map(NamedRange::getName)
                .sorted() // natural (lexicographical) order
                .collect(INTERNAL_COLLECTOR);
    }

}
