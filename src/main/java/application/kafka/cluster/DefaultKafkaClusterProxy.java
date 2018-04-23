package application.kafka.cluster;

import application.constants.ApplicationConstants;
import application.exceptions.ClusterConfigurationError;
import application.kafka.dto.AssignedConsumerInfo;
import application.kafka.dto.ClusterNodeInfo;
import application.kafka.dto.TopicAggregatedSummary;
import application.kafka.dto.TopicToAdd;
import application.kafka.dto.UnassignedConsumerInfo;
import application.logging.Logger;
import application.utils.AppUtils;
import application.utils.HostPortValue;
import application.utils.HostnameUtils;
import kafka.admin.AdminClient;
import kafka.coordinator.group.GroupOverview;
import org.apache.commons.lang3.StringUtils;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.DescribeConfigsResult;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.protocol.ApiKeys;
import org.apache.kafka.common.requests.ApiVersionsResponse;
import org.apache.kafka.common.serialization.StringDeserializer;
import scala.collection.JavaConversions;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

import static application.constants.ApplicationConstants.APPLICATION_NAME;
import static java.util.Collections.singleton;
import static scala.collection.JavaConversions.seqAsJavaList;

public class DefaultKafkaClusterProxy implements KafkaClusterProxy {

    public static final String OFFSET_NOT_FOUND = "NOT_FOUND";
    private final HostPortValue hostPort;
    private final ClusterStateSummary clusterSummary = new ClusterStateSummary();
    private final ClusterNodesProperties clusterNodesProperties = new ClusterNodesProperties();
    private final Map<Node, NodeApiVersionsInfo> brokerApiVersions = new HashMap<>();
    private final org.apache.kafka.clients.admin.AdminClient kafkaClientsAdminClient;
    private final kafka.admin.AdminClient kafkaAdminClient;
    private TopicAdmin topicAdmin;


    public DefaultKafkaClusterProxy(HostPortValue hostPort,
                                    TopicAdmin topicAdmin,
                                    org.apache.kafka.clients.admin.AdminClient kafkaClientsAdminClient,
                                    kafka.admin.AdminClient kafkaAdminClient
    ) throws ClusterConfigurationError, InterruptedException, ExecutionException, TimeoutException {
        Logger.trace("New DefaultKafkaClusterProxy: real Hash : " + AppUtils.realHash(this));
        this.hostPort = hostPort;
        this.topicAdmin = topicAdmin;
        this.kafkaClientsAdminClient = kafkaClientsAdminClient;
        this.kafkaAdminClient = kafkaAdminClient;
        throwIfInvalidConfigMakesClusterUnusable();
        fetchClusterStateSummary();

    }


    @Override
    public void reportInvalidClusterConfigurationTo(Consumer<String> problemReporter) {
        StringBuilder builder = new StringBuilder();
        getInconsistentBrokerPropertiesErrorMessage().ifPresent(builder::append);
        final String errorMsg = builder.toString();
        if (!StringUtils.isBlank(errorMsg)) {
            problemReporter.accept(errorMsg);
        }
    }

    @Override
    public void close() {
        Logger.trace("Closing kafka admin proxy");
        kafkaClientsAdminClient.close(ApplicationConstants.CLOSE_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        kafkaAdminClient.close();
        Logger.trace("Closing done");
    }

    @Override
    public Set<AssignedConsumerInfo> getConsumersForTopic(String topicName) {
        return clusterSummary.getConsumersForTopic(topicName);
    }

    @Override
    public Set<UnassignedConsumerInfo> getUnassignedConsumersInfo() {
        return clusterSummary.getUnassignedConsumersInfo();
    }

    @Override
    public Set<ClusterNodeInfo> getNodesInfo() {
        return clusterSummary.getNodesInfo();
    }

    @Override
    public Set<TopicAggregatedSummary> getAggregatedTopicSummary() {
        return clusterSummary.getAggregatedTopicSummary();
    }

    @Override
    public int partitionsForTopic(String topicName) {
        return clusterSummary.partitionsForTopic(topicName);
    }

    @Override
    public TriStateConfigEntryValue isTopicAutoCreationEnabled() {
        if (clusterNodesProperties.isEmpty()) {
            return TriStateConfigEntryValue.False;
        }
        return clusterNodesProperties.topicAutoCreationEnabled();
    }

    @Override
    public TriStateConfigEntryValue isTopicDeletionEnabled() {
        if (clusterNodesProperties.isEmpty()) {
            return TriStateConfigEntryValue.False;
        }
        return clusterNodesProperties.topicDeletionEnabled();
    }

    @Override
    public boolean hasTopic(String topicName) {
        return clusterSummary.hasTopic(topicName);
    }

    @Override
    public Set<ConfigEntry> getTopicProperties(String topicName) {
        return clusterSummary.getTopicProperties(topicName);
    }


    @Override
    public void createTopic(TopicToAdd topicToAdd) throws Exception {
        topicAdmin.createNewTopic(topicToAdd);
    }

    @Override
    public void deleteTopic(String topicName) throws Exception {
        topicAdmin.deleteTopic(topicName);
    }

    private boolean doesNodeSupportDescribeConfigApi(Node node) {
        final NodeApiVersionsInfo info = brokerApiVersions.getOrDefault(node, null);
        return info != null && info.doesApiSupportDescribeConfig();
    }

    private void fetchClusterStateSummary() throws InterruptedException, ExecutionException, TimeoutException {
        clearClusterSummary();
        describeCluster();
        describeTopics();
        describeConsumers();
        fillNodesConfigProperties();
    }

    private void describeTopics() throws InterruptedException, ExecutionException, TimeoutException {
        topicAdmin.describeTopics().forEach(clusterSummary::addTopicInfo);
    }

    private void clearClusterSummary() {
        clusterSummary.clear();
    }

    private Optional<String> getInconsistentBrokerPropertiesErrorMessage() {
        final Set<String> misconfiguredProperties = clusterNodesProperties.getAllPropertiesThatDiffersBetweenNodes();
        if (misconfiguredProperties.size() > 0) {
            final String msg = String.format("Cluster configuration is inconsistent!%n" +
                                                 "Below properties are different between nodes but should be the same:%n%n" +
                                                 "[%s] ", String.join(", ",
                                                                      misconfiguredProperties.stream()
                                                                          .map(e -> String.format("%s", e))
                                                                          .toArray(String[]::new)));
            return Optional.of(msg);
        }
        return Optional.empty();
    }

    private void throwIfInvalidConfigMakesClusterUnusable() throws ClusterConfigurationError {
        try {
            Logger.trace("calling kafkaAdminClient.findAllBrokers() ");
            final List<Node> nodes = seqAsJavaList(kafkaAdminClient.findAllBrokers());
            final List<String> advertisedListeners = new ArrayList<>();
            for (Node node : nodes) {
                final String host1 = node.host();
                final int port = node.port();
                final String advertisedListener = String.format("%s:%d", host1, port);
                Logger.debug("Found advertised listener: " + advertisedListener);
                advertisedListeners.add(advertisedListener);

                Logger.trace(String.format("Checking if advertised listener '%s' is reachable", host1));
                if (HostnameUtils.isHostnameReachable(host1, ApplicationConstants.HOSTNAME_REACHABLE_TIMEOUT_MS)) {
                    Logger.trace("Yes");
                    return;
                }
                Logger.trace("No");
            }
            final String msg = String.format("Cluster config for 'advertised.listeners' is invalid.%n%n" +
                                                 "* None of advertised listeners '%s' are reachable from outside world.%n" +
                                                 "* Producers/consumers will be unable to use this kafka cluster " +
                                                 "(e.g. will not connect properly).%n" +
                                                 "* This application (%s) cannot fetch broker configuration", advertisedListeners,
                                             APPLICATION_NAME);
            throw new ClusterConfigurationError(msg);
        } catch (RuntimeException e) {
            Logger.trace(e);
            e.printStackTrace();
        }
    }

    private void fillNodesConfigProperties() {
        clusterNodesProperties.clear();
        clusterSummary.getNodesInfo().forEach(nodeInfo -> {
            nodeInfo.getEntries().forEach(entry -> {
                clusterNodesProperties.addConfigEntry(entry.name(), entry.value());
            });
        });
    }

    private List<String> getConsumerGroupIds() {
        List<String> groupIds = new ArrayList<>();
        final List<GroupOverview> groupOverviews = seqAsJavaList(kafkaAdminClient.listAllConsumerGroupsFlattened());
        groupOverviews.forEach(overview -> groupIds.add(overview.groupId()));
        return groupIds;
    }

    private Map<TopicPartition, Object> getPartitionsForConsumerGroup(String consumerGroup) {
        final scala.collection.immutable.Map<TopicPartition, Object> abc = kafkaAdminClient.listGroupOffsets(consumerGroup);
        final Map<TopicPartition, Object> paritionsForConsumerGroup = JavaConversions.mapAsJavaMap(abc);
        Logger.debug(String.format("Fetched partitions for consumer group '%s' -> '%s'", consumerGroup, paritionsForConsumerGroup));
        return paritionsForConsumerGroup;
    }

    private String getOffsetForPartition(Map<TopicPartition, Object> offsets, TopicPartition topicPartition) {
        Logger.trace(String.format("Searching for offset for %s in %s", topicPartition, offsets));
        if (!offsets.containsKey(topicPartition)) {
            Logger.trace("Offset not found");
            return OFFSET_NOT_FOUND;
        }
        final Object offset = offsets.get(topicPartition);
        final String offsetAsString = String.valueOf(offset);
        Logger.trace(String.format("Found : %s", offsetAsString));
        return offsetAsString;
    }

    private void describeConsumers() {

        final List<String> consumerGroupIds = getConsumerGroupIds();

        consumerGroupIds.forEach(consumerGroupId -> {


            final Map<TopicPartition, Object> offsetForPartition = getPartitionsForConsumerGroup(consumerGroupId);
            final List<TopicsOffsetInfo> topicOffsetInfo = getTopicOffsetsFor(consumerGroupIds, offsetForPartition.keySet());

            System.out.println(topicOffsetInfo);

            final AdminClient.ConsumerGroupSummary consumerGroupSummary = kafkaAdminClient
                .describeConsumerGroup(consumerGroupId,
                                       ApplicationConstants.DESCRIBE_CONSUMER_METEADATA_TIMEOUT_MS);

            final List<AdminClient.ConsumerSummary> summaries = seqAsJavaList(consumerGroupSummary.consumers().get());

            summaries.forEach(consumerSummary -> {
                Logger.debug("Consumer summary " + consumerSummary);

                final List<TopicPartition> topicPartitions = seqAsJavaList(consumerSummary.assignment());
                if (topicPartitions.isEmpty()) {
                    final UnassignedConsumerInfo consumerInfo = getUnassignedConsumerInfo(consumerGroupId, consumerSummary);
                    clusterSummary.addUnassignedConsumerInfo(consumerInfo);
                } else {
                    topicPartitions.forEach(topicPartition -> {
                        final AssignedConsumerInfo consumerInfo = getAssignedConsumerInfo(consumerGroupId,
                                                                                          offsetForPartition,
                                                                                          consumerSummary,
                                                                                          topicPartition);

                        clusterSummary.addAssignedConsumerInfo(consumerInfo);

                    });
                }
            });
        });
    }

    private List<TopicsOffsetInfo> getTopicOffsetsFor(List<String> consumerGroupIds,
                                                      Set<TopicPartition> topicPartitions) {
        List<TopicsOffsetInfo> result = new ArrayList<>();

        consumerGroupIds.forEach(consumerGroupId -> {
            final KafkaConsumer<String, String> consumer = createOffsetInfoConsumerFor(consumerGroupId);
            final Map<TopicPartition, Long> beggingOffsets = consumer.beginningOffsets(topicPartitions);
            final Map<TopicPartition, Long> endOffsets = consumer.endOffsets(topicPartitions);

            if (!beggingOffsets.keySet().equals(endOffsets.keySet())) {
                Logger.error(String.format("Inconsistent response from broker. While fetching beginOffset and " +
                                               "endOffset for topicPartitions:%s " +
                                               "endOffsets topic-partitions (%s) are different than " +
                                               "beggingOffsets topic-partitions (%s)", topicPartitions,
                                           endOffsets.keySet(),
                                           beggingOffsets.keySet()));
            }

            for (Map.Entry<TopicPartition, Long> entry : beggingOffsets.entrySet()) {
                if (!endOffsets.containsKey(entry.getKey())) {
                    continue;
                }
                final String topicName = entry.getKey().topic();
                final int partition = entry.getKey().partition();
                final String beggingOffset = String.valueOf(entry.getValue());
                final String endOffset = String.valueOf(endOffsets.get(entry.getKey()));

                final TopicsOffsetInfo topicsOffsetInfo = new TopicsOffsetInfo(topicName,
                                                                               beggingOffset,
                                                                               endOffset,
                                                                               consumerGroupId,
                                                                               partition);
                result.add(topicsOffsetInfo);
            }
        });


        return result;

    }

    private KafkaConsumer<String, String> createOffsetInfoConsumerFor(String consumerGroupIds) {
        final Properties props = new Properties();
        props.setProperty(ConsumerConfig.GROUP_ID_CONFIG, consumerGroupIds);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, hostPort.toHostString());
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        return new KafkaConsumer<>(props);
    }


    private AssignedConsumerInfo getAssignedConsumerInfo(String consumerGroupId,
                                                         Map<TopicPartition, Object> offsetForPartition,
                                                         AdminClient.ConsumerSummary consumerSummary,
                                                         TopicPartition topicPartition) {
        return AssignedConsumerInfo.builder()
            .consumerGroupId(consumerGroupId)
            .consumerId(consumerSummary.consumerId())
            .clientId(consumerSummary.clientId())
            .host(consumerSummary.host())
            .topic(topicPartition.topic())
            .partition(topicPartition.partition())
            .offset(getOffsetForPartition(offsetForPartition, topicPartition))
            .build();
    }

    private UnassignedConsumerInfo getUnassignedConsumerInfo(String consumerGroupId,
                                                             AdminClient.ConsumerSummary consumerSummary) {
        return UnassignedConsumerInfo.builder()
            .consumerGroupId(consumerGroupId)
            .consumerId(consumerSummary.consumerId())
            .clientId(consumerSummary.clientId())
            .host(consumerSummary.host())
            .build();
    }

    private void describeCluster() throws InterruptedException, ExecutionException, TimeoutException {
        final DescribeClusterResult describeClusterResult = kafkaClientsAdminClient.describeCluster();
        final KafkaFuture<String> stringKafkaFuture = describeClusterResult.clusterId();
        clusterSummary.setClusterId(stringKafkaFuture.get(ApplicationConstants.FUTURE_GET_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        final int controllerNodeId = getControllerNodeId(describeClusterResult);
        final Collection<Node> nodes = describeClusterResult.nodes().get(ApplicationConstants.FUTURE_GET_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        describeNodes(nodes, controllerNodeId);
    }

    private int getControllerNodeId(DescribeClusterResult describeClusterResult) throws InterruptedException,
                                                                                        ExecutionException,
                                                                                        TimeoutException {
        final KafkaFuture<Node> controller = describeClusterResult.controller();
        return controller.get(ApplicationConstants.FUTURE_GET_TIMEOUT_MS, TimeUnit.MILLISECONDS).id();
    }

    private void describeNodes(Collection<Node> nodes, int controllerNodeId) throws InterruptedException,
                                                                                    ExecutionException {

        for (Node node : nodes) {
            saveApiVersionsForNodes(node);
        }
        for (Node node : nodes) {
            describeNodeConfig(controllerNodeId, node);
        }
    }

    private void describeNodeConfig(int controllerNodeId, Node node) throws InterruptedException, ExecutionException {
        if (!doesNodeSupportDescribeConfigApi(node)) {
            Logger.warn(String.format("Node '%s' does not support describeConfig api. Cannot show cluster properties", node));
            return;
        }

        DescribeConfigsResult configs = kafkaClientsAdminClient.describeConfigs(
            singleton(new ConfigResource(ConfigResource.Type.BROKER, String.valueOf(node.id()))));
        final Map<ConfigResource, Config> configResourceConfigMap = configs.all().get();
        configResourceConfigMap.forEach((configResource, config) ->
                                            clusterSummary.addNodeInfo(new ClusterNodeInfo(node.id() == controllerNodeId,
                                                                                           node.idString(),
                                                                                           new HashSet<>(config.entries()))));
    }

    private void saveApiVersionsForNodes(Node node) {
        final List<ApiVersionsResponse.ApiVersion> apiVersions = JavaConversions.seqAsJavaList(kafkaAdminClient.getApiVersions(node));
        brokerApiVersions.put(node, new NodeApiVersionsInfo(apiVersions));
        printApiVersionForNode(node, apiVersions);
    }

    private void printApiVersionForNode(Node node, List<ApiVersionsResponse.ApiVersion> apiVersions) {
        StringBuilder builder = new StringBuilder();
        builder.append(String.format("%n### Api version for node %s ###%n", node));
        apiVersions.forEach(version -> {
            builder.append(String.format("ApiKey '%s', min:%d .. max:%d%n", ApiKeys.forId(version.apiKey),
                                         version.minVersion,
                                         version.maxVersion));
        });
        Logger.debug(builder.toString());
    }
}


