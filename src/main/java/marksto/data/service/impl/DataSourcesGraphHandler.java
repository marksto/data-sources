package marksto.data.service.impl;

import com.google.common.collect.Streams;
import com.google.common.graph.*;
import marksto.data.schemas.DataSource;
import org.apache.commons.lang3.NotImplementedException;
import org.apache.commons.lang3.tuple.Pair;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;

import static marksto.data.schemas.DataSource.DataSourceDependency;
import static marksto.data.service.impl.DataSourceHelper.*;
import static marksto.common.util.LogUtils.lazyTrace;
import static java.util.Comparator.comparingInt;
import static java.util.stream.Collectors.*;

@SuppressWarnings({"UnstableApiUsage", "Convert2MethodRef"})
class DataSourcesGraphHandler {

    private static final Logger LOG = LoggerFactory.getLogger(DataSourcesGraphHandler.class);

    // Error Messages
    private static final String TRAVERSAL_TYPE_IS_NOT_IMPLEMENTED = "Traversal type with no independent nodes is not implemented yet";

    public static final String MAIN_TRAVERSAL_ORDER = "The main traversal order of data sources: '{}'";
    public static final String FINAL_TRAVERSAL_ORDER = "Final traversal order of data sources: '{}' with '{}'";
    public static final String ACTUAL_SYNC_ORDER = "Actual sync order: '{}' with '{}'";

    // -------------------------------------------------------------------------

    private static class DSGraphNode {

        final DataSource dataSource;

        public DSGraphNode(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        public DataSource getDataSource() {
            return dataSource;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null || getClass() != obj.getClass()) {
                return false;
            }
            DSGraphNode that = (DSGraphNode) obj;
            return Objects.equals(getDataSource(), that.getDataSource());
        }

        @Override
        public int hashCode() {
            return Objects.hash(getDataSource().getName());
        }
    }

    private static class DSGraphEdgeValue {

        final int weight;
        final DataSourceDependency dependency;

        private DSGraphEdgeValue(int weight, DataSourceDependency dependency) {
            this.weight = weight;
            this.dependency = dependency;
        }

        public int getWeight() {
            return weight;
        }

        public DataSourceDependency getDependency() {
            return dependency;
        }
    }

    // -------------------------------------------------------------------------

    private final AtomicReference<ValueGraph<DSGraphNode, DSGraphEdgeValue>> dataSourceGraphRef;

    DataSourcesGraphHandler() {
        this.dataSourceGraphRef = new AtomicReference<>();
    }

    private ValueGraph<DSGraphNode, DSGraphEdgeValue> getFullGraph() {
        return dataSourceGraphRef.get();
    }

    // -------------------------------------------------------------------------

    void constructGraph(final Map<String, DataSource> dataSourcesMap) {
        MutableValueGraph<DSGraphNode, DSGraphEdgeValue> tmpGraph = ValueGraphBuilder.directed().build();

        for (Map.Entry<String, DataSource> entry : dataSourcesMap.entrySet()) {
            var target = entry.getValue();
            tmpGraph.addNode(new DSGraphNode(target));
            getDependenciesStream(target)
                    .forEach(dep -> addEdge(dataSourcesMap, tmpGraph, target, dep));
        }

        var graph = ImmutableValueGraph.copyOf(tmpGraph);
        dataSourceGraphRef.set(graph);

        // NOTE: Updating Data Sources here to keep the 'dataSourcesMap' and 'graph' consistent.
        //       As well, this serves as an extra validation of the previous step result.
        initDependants(graph, dataSourcesMap);
    }

    private void addEdge(final Map<String, DataSource> dataSourcesMap,
                         final MutableValueGraph<DSGraphNode, DSGraphEdgeValue> tmpGraph,
                         final DataSource target, DataSourceDependency dependency) {
        var source = dataSourcesMap.get(dependency.getSource());
        if (source == null) {
            return; // no Data Source with such a name provided (yet)
        }

        var weight = weightFn.apply(source, dataSourcesMap);
        var edgeValue = new DSGraphEdgeValue(weight, dependency);
        tmpGraph.putEdgeValue(new DSGraphNode(source), new DSGraphNode(target), edgeValue);
    }

    void initDependants(final ValueGraph<DSGraphNode, DSGraphEdgeValue> graph,
                        final Map<String, DataSource> dataSourcesMap) {
        for (Map.Entry<String, DataSource> entry : dataSourcesMap.entrySet()) {
            var source = entry.getValue();
            var successors = graph.successors(new DSGraphNode(source));
            source.setDependants(successors.stream()
                    .map(node -> node.getDataSource().getName())
                    .collect(toList()));
        }
    }

    // -------------------------------------------------------------------------

    Mono<Void> traverse(final BiFunction<DataSource, DataSource, Mono<Void>> workload,
                        final Consumer<Iterable<DataSource>> startDataSourcesReporter) {
        var startNodes = getIndependentNodes();
        if (startNodes.isEmpty()) {
            throw new NotImplementedException(TRAVERSAL_TYPE_IS_NOT_IMPLEMENTED);
        }
        startDataSourcesReporter.accept(startNodes);
        return traverse(startNodes, workload);
    }

    private List<DataSource> getIndependentNodes() {
        return getFullGraph().nodes().stream()
                .map(node -> node.getDataSource())
                .filter(isIndependent)
                .collect(toList());
    }

    // -------------------------------------------------------------------------

    Mono<Void> traverse(final Iterable<DataSource> startDataSources,
                        final BiFunction<DataSource, DataSource, Mono<Void>> workload) {
        var graph = getFullGraph();
        var startNodes = Streams.stream(startDataSources)
                .map(dataSource -> new DSGraphNode(dataSource))
                .collect(toList());
        var subGraphNodes = reachableNodes(startNodes, graph);
        var orderedNodes = orderedNodesSet(subGraphNodes, graph);

        lazyTrace(LOG, MAIN_TRAVERSAL_ORDER, () -> print(orderedNodes.stream()
                .map(node -> node.getDataSource())
                .collect(toList())));

        return Flux.fromIterable(orderedNodes)
                .flatMapSequential(targetNode -> orderedSourceNodes(graph, subGraphNodes, targetNode))
                .doOnNext(ep -> lazyTrace(LOG, FINAL_TRAVERSAL_ORDER,
                        () -> ep.target().getDataSource().getName(),
                        () -> ep.source().getDataSource().getName()))
                .concatMap(ep -> runWorkloadFor(ep, workload))
                .then();
    }

    // -------------------------------------------------------------------------

    private final Comparator<Pair<DSGraphNode, Set<DSGraphNode>>> reachableNodesCountComparator
            = comparingInt((Pair<DSGraphNode, Set<DSGraphNode>> p) -> p.getRight().size()).reversed();

    private LinkedHashSet<DSGraphNode> orderedNodesSet(final Set<DSGraphNode> subGraphNodes,
                                                       final ValueGraph<DSGraphNode, DSGraphEdgeValue> graph) {
        return subGraphNodes.stream()
                .map(sourceNode -> Pair.of(sourceNode, reachableNodes(List.of(sourceNode), graph)))
                .sorted(reachableNodesCountComparator)
                .map(Pair::getLeft)
                .collect(toCollection(LinkedHashSet::new));
    }

    // -------------------------------------------------------------------------

    private Set<DSGraphNode> reachableNodes(final Iterable<DSGraphNode> startNodes,
                                            final ValueGraph<DSGraphNode, DSGraphEdgeValue> graph) {
        for (DSGraphNode startNode : startNodes) {
            checkThatNodeIsInGraph(startNode, graph);
        }

        Set<DSGraphNode> visited = new LinkedHashSet<>();
        Queue<DSGraphNode> queue = new ArrayDeque<>();

        // add all roots to the queue, skipping duplicates
        for (DSGraphNode node : startNodes) {
            if (visited.add(node)) {
                queue.add(node);
            }
        }

        // perform a breadth-first traversal rooted at the input node
        while (!queue.isEmpty()) {
            DSGraphNode currentNode = queue.remove();
            for (DSGraphNode successor : graph.successors(currentNode)) {
                if (!isHistorical(currentNode, successor, graph)
                        && visited.add(successor)) {
                    queue.add(successor);
                }
            }
        }

        return Collections.unmodifiableSet(visited);
    }

    private void checkThatNodeIsInGraph(final DSGraphNode node,
                                        final SuccessorsFunction<DSGraphNode> graph) {
        // NOTE: Per javadoc, throws an IllegalArgumentException
        //       for nodes that are not an element of the graph.
        graph.successors(node);
    }

    private boolean isHistorical(final DSGraphNode sourceNode, final DSGraphNode targetNode,
                                 final ValueGraph<DSGraphNode, DSGraphEdgeValue> graph) {
        return graph.edgeValue(sourceNode, targetNode)
                .orElseThrow() // it can't fail here
                .getDependency().getHistorical();
    }

    // -------------------------------------------------------------------------

    private Flux<EndpointPair<DSGraphNode>> orderedSourceNodes(final ValueGraph<DSGraphNode, DSGraphEdgeValue> graph,
                                                               final Set<DSGraphNode> subGraphNodes,
                                                               final DSGraphNode targetNode) {
        return Flux.fromIterable(graph.predecessors(targetNode))
                .filter(sourceNode -> subGraphNodes.contains(sourceNode))
                .map(sourceNode -> EndpointPair.ordered(sourceNode, targetNode))
                .map(ep -> Pair.of(ep, graph.edgeValue(ep).orElseThrow()))
                .filter(pair -> !pair.getRight().getDependency().getHistorical())
                .sort(comparingInt(pair -> pair.getRight().getWeight()))
                .map(Pair::getLeft);
    }

    private Mono<Void> runWorkloadFor(final EndpointPair<DSGraphNode> ep,
                                      final BiFunction<DataSource, DataSource, Mono<Void>> workload) {
        return workload.apply(ep.nodeV().getDataSource(), ep.nodeU().getDataSource())
                .doOnSuccess(res -> lazyTrace(LOG, ACTUAL_SYNC_ORDER,
                        () -> ep.target().getDataSource().getName(),
                        () -> ep.source().getDataSource().getName()));
    }

}
