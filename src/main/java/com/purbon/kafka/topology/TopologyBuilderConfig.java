package com.purbon.kafka.topology;

import static com.purbon.kafka.topology.BuilderCLI.ADMIN_CLIENT_CONFIG_OPTION;
import static com.purbon.kafka.topology.BuilderCLI.DRY_RUN_OPTION;

import com.purbon.kafka.topology.exceptions.ConfigurationException;
import com.purbon.kafka.topology.model.Project;
import com.purbon.kafka.topology.model.Topic;
import com.purbon.kafka.topology.model.Topology;
import com.purbon.kafka.topology.serdes.TopologySerdes.FileType;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.kafka.clients.admin.AdminClientConfig;

public class TopologyBuilderConfig {

  public static final String KAFKA_INTERNAL_TOPIC_PREFIXES = "kafka.internal.topic.prefixes";
  public static final String ACCESS_CONTROL_IMPLEMENTATION_CLASS =
      "topology.builder.access.control.class";

  public static final String ACCESS_CONTROL_DEFAULT_CLASS =
      "com.purbon.kafka.topology.roles.SimpleAclsProvider";
  public static final String RBAC_ACCESS_CONTROL_CLASS =
      "com.purbon.kafka.topology.roles.RBACProvider";

  public static final String STATE_PROCESSOR_IMPLEMENTATION_CLASS =
      "topology.builder.state.processor.class";

  public static final String STATE_PROCESSOR_DEFAULT_CLASS =
      "com.purbon.kafka.topology.backend.FileBackend";
  public static final String REDIS_STATE_PROCESSOR_CLASS =
      "com.purbon.kafka.topology.backend.RedisBackend";

  static final String REDIS_HOST_CONFIG = "topology.builder.redis.host";
  static final String REDIS_PORT_CONFIG = "topology.builder.redis.port";

  public static final String MDS_SERVER = "topology.builder.mds.server";
  static final String MDS_USER_CONFIG = "topology.builder.mds.user";
  static final String MDS_PASSWORD_CONFIG = "topology.builder.mds.password";
  public static final String MDS_KAFKA_CLUSTER_ID_CONFIG = "topology.builder.mds.kafka.cluster.id";
  public static final String MDS_SR_CLUSTER_ID_CONFIG =
      "topology.builder.mds.schema.registry.cluster.id";
  public static final String MDS_KC_CLUSTER_ID_CONFIG =
      "topology.builder.mds.kafka.connect.cluster.id";

  static final String CONFLUENT_SCHEMA_REGISTRY_URL_CONFIG = "schema.registry.url";
  private static final String CONFLUENT_MONITORING_TOPIC_CONFIG = "confluent.monitoring.topic";
  private static final String CONFLUENT_COMMAND_TOPIC_CONFIG = "confluent.command.topic";
  private static final String CONFLUENT_METRICS_TOPIC_CONFIG = "confluent.metrics.topic";
  static final String TOPIC_PREFIX_FORMAT_CONFIG = "topology.topic.prefix.format";
  static final String PROJECT_PREFIX_FORMAT_CONFIG = "topology.project.prefix.format";
  static final String TOPIC_PREFIX_SEPARATOR_CONFIG = "topology.topic.prefix.separator";
  static final String TOPOLOGY_VALIDATIONS_CONFIG = "topology.validations";

  static final String TOPOLOGY_FILE_TYPE = "topology.file.type";

  static final String OPTIMIZED_ACLS_CONFIG = "topology.acls.optimized";

  private final Map<String, String> cliParams;
  private Config config;

  public TopologyBuilderConfig() {
    this(new HashMap<>(), ConfigFactory.load());
  }

  public static TopologyBuilderConfig build(Map<String, String> cliParams) {
    return build(cliParams, cliParams.get(ADMIN_CLIENT_CONFIG_OPTION));
  }

  public static TopologyBuilderConfig build(Map<String, String> cliParams, String configFile) {
    if (!configFile.isEmpty()) {
      System.setProperty("config.file", configFile);
    }
    ConfigFactory.invalidateCaches();
    Config config = ConfigFactory.load();
    return new TopologyBuilderConfig(cliParams, config);
  }

  public TopologyBuilderConfig(Map<String, String> cliParams, Properties props) {
    this(cliParams, (Map) props);
  }

  public TopologyBuilderConfig(Map<String, String> cliParams, Map<String, Object> props) {
    this(cliParams, ConfigFactory.parseMap(props).withFallback(ConfigFactory.load()));
  }

  public TopologyBuilderConfig(Map<String, String> cliParams, Config config) {
    this.cliParams = cliParams;
    this.config = config;
  }

  public Map<String, ?> asMap() {
    return asMap("");
  }

  public Map<String, ?> asMap(String filter) {
    Map<String, Object> map = new HashMap<>();
    config.entrySet().stream()
        .filter(entry -> filter.isEmpty() || entry.getKey().startsWith(filter))
        .forEach(entry -> map.put(entry.getKey(), entry.getValue().unwrapped()));
    return map;
  }

  public Properties asProperties() {
    Properties props = new Properties();
    config.entrySet().forEach(entry -> props.put(entry.getKey(), entry.getValue().unwrapped()));
    if (cliParams.get(BuilderCLI.BROKERS_OPTION) != null) {
      props.put(
          AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, cliParams.get(BuilderCLI.BROKERS_OPTION));
    }
    props.put(AdminClientConfig.RETRIES_CONFIG, Integer.MAX_VALUE);
    return props;
  }

  public void validateWith(Topology topology) throws ConfigurationException {
    validateGeneralConfiguration(topology);
    boolean isRBAC = this.getAccessControlClassName().equalsIgnoreCase(RBAC_ACCESS_CONTROL_CLASS);
    if (isRBAC) {
      validateRBACConfiguration(topology);
    }
  }

  public void validateRBACConfiguration(Topology topology) throws ConfigurationException {
    raiseIfNull(MDS_SERVER, MDS_USER_CONFIG, MDS_PASSWORD_CONFIG);
    raiseIfNull(MDS_KAFKA_CLUSTER_ID_CONFIG);

    final boolean hasSchemaRegistry = !topology.getPlatform().getSchemaRegistry().isEmpty();
    final boolean hasKafkaConnect =
        !topology.getProjects().stream().allMatch(project -> project.getConnectors().isEmpty());

    if (hasSchemaRegistry) {
      raiseIfNull(MDS_SR_CLUSTER_ID_CONFIG);
    } else if (hasKafkaConnect && config.getString(MDS_KC_CLUSTER_ID_CONFIG) == null) {
      raiseIfNull(MDS_KC_CLUSTER_ID_CONFIG);
    }
  }

  private void validateGeneralConfiguration(Topology topology) throws ConfigurationException {
    if (countOfSchemas(topology) > 0) {
      raiseIfDefault(CONFLUENT_SCHEMA_REGISTRY_URL_CONFIG, "mock://");
    }

    validateBrokersConfig();

    boolean topicPrefixDefinedButNotProjectPrefix =
        !getTopicPrefixFormat().equals("default") && getProjectPrefixFormat().equals("default");

    boolean projectPrefixDefinedButNotTopicPrefix =
        getTopicPrefixFormat().equals("default") && !getProjectPrefixFormat().equals("default");

    if (topicPrefixDefinedButNotProjectPrefix || projectPrefixDefinedButNotTopicPrefix) {
      throw new ConfigurationException(
          TOPIC_PREFIX_FORMAT_CONFIG
              + " and "
              + PROJECT_PREFIX_FORMAT_CONFIG
              + " need to be defined together.");
    }

    if (!getTopicPrefixFormat().startsWith(getProjectPrefixFormat())) {
      throw new ConfigurationException(
          TOPIC_PREFIX_FORMAT_CONFIG + "should start by" + PROJECT_PREFIX_FORMAT_CONFIG);
    }
  }

  private void validateBrokersConfig() throws ConfigurationException {
    boolean existServersAsConfig;
    try {
      config.getString(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
      existServersAsConfig = true;
    } catch (Exception ex) {
      existServersAsConfig = false;
    }

    if (cliParams.get(BuilderCLI.BROKERS_OPTION) == null && !existServersAsConfig) {
      String msg =
          String.format(
              "Either the CLI option %s or the configuration %s should be specified",
              BuilderCLI.BROKERS_OPTION, AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG);
      throw new ConfigurationException(msg);
    }
  }

  private static long countOfSchemas(Topology topology) {
    return topology.getProjects().stream()
        .flatMap((Function<Project, Stream<Topic>>) project -> project.getTopics().stream())
        .map(Topic::getSchemas)
        .filter(Objects::nonNull)
        .count();
  }

  private void raiseIfDefault(String key, String _default) throws ConfigurationException {
    if (config.getString(key).equals(_default)) {
      throw new ConfigurationException(
          "Configuration key " + key + " should not have the default value " + _default);
    }
  }

  private void raiseIfNull(String... keys) throws ConfigurationException {
    try {
      for (String key : keys) {
        config.getString(key);
      }
    } catch (Exception ex) {
      throw new ConfigurationException(ex.getMessage());
    }
  }

  public String getProperty(String key) {
    return config.getString(key);
  }

  public List<String> getKafkaInternalTopicPrefixes() {
    return config.getStringList(KAFKA_INTERNAL_TOPIC_PREFIXES).stream()
        .map(String::trim)
        .collect(Collectors.toList());
  }

  public String getConfluentSchemaRegistryUrl() {
    return config.getString(CONFLUENT_SCHEMA_REGISTRY_URL_CONFIG);
  }

  public String getConfluentMonitoringTopic() {
    return config.getString(CONFLUENT_MONITORING_TOPIC_CONFIG);
  }

  public String getConfluentCommandTopic() {
    return config.getString(CONFLUENT_COMMAND_TOPIC_CONFIG);
  }

  public String getConfluentMetricsTopic() {
    return config.getString(CONFLUENT_METRICS_TOPIC_CONFIG);
  }

  public String getAccessControlClassName() {
    return config.getString(ACCESS_CONTROL_IMPLEMENTATION_CLASS);
  }

  public String getStateProcessorImplementationClassName() {
    return config.getString(STATE_PROCESSOR_IMPLEMENTATION_CLASS);
  }

  public String getTopicPrefixFormat() {
    return config.getString(TOPIC_PREFIX_FORMAT_CONFIG);
  }

  public String getProjectPrefixFormat() {
    return config.getString(PROJECT_PREFIX_FORMAT_CONFIG);
  }

  public String getTopicPrefixSeparator() {
    return config.getString(TOPIC_PREFIX_SEPARATOR_CONFIG);
  }

  public Boolean shouldOptimizeAcls() {
    return config.getBoolean(OPTIMIZED_ACLS_CONFIG);
  }

  public List<String> getTopologyValidations() {
    List<String> classes = config.getStringList(TOPOLOGY_VALIDATIONS_CONFIG);
    return classes.stream().map(String::trim).collect(Collectors.toList());
  }

  public boolean allowDelete() {
    return Boolean.parseBoolean(cliParams.getOrDefault(BuilderCLI.ALLOW_DELETE_OPTION, "true"));
  }

  public boolean isQuiet() {
    return Boolean.parseBoolean(cliParams.getOrDefault(BuilderCLI.QUIET_OPTION, "false"));
  }

  public boolean isDryRun() {
    return Boolean.parseBoolean(cliParams.getOrDefault(DRY_RUN_OPTION, "false"));
  }

  public FileType getTopologyFileType() {
    return config.getEnum(FileType.class, TOPOLOGY_FILE_TYPE);
  }
}
