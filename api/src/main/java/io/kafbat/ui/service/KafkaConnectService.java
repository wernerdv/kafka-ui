package io.kafbat.ui.service;


import com.github.benmanes.caffeine.cache.AsyncCache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.kafbat.ui.config.ClustersProperties;
import io.kafbat.ui.connect.api.KafkaConnectClientApi;
import io.kafbat.ui.connect.model.ConnectorStatus;
import io.kafbat.ui.connect.model.ConnectorStatusConnector;
import io.kafbat.ui.connect.model.ConnectorTopics;
import io.kafbat.ui.connect.model.TaskStatus;
import io.kafbat.ui.exception.ConnectorOffsetsResetException;
import io.kafbat.ui.exception.NotFoundException;
import io.kafbat.ui.exception.ValidationException;
import io.kafbat.ui.mapper.ClusterMapper;
import io.kafbat.ui.mapper.KafkaConnectMapper;
import io.kafbat.ui.model.ConnectDTO;
import io.kafbat.ui.model.ConnectorActionDTO;
import io.kafbat.ui.model.ConnectorDTO;
import io.kafbat.ui.model.ConnectorPluginConfigValidationResponseDTO;
import io.kafbat.ui.model.ConnectorPluginDTO;
import io.kafbat.ui.model.ConnectorStateDTO;
import io.kafbat.ui.model.ConnectorTaskStatusDTO;
import io.kafbat.ui.model.FullConnectorInfoDTO;
import io.kafbat.ui.model.KafkaCluster;
import io.kafbat.ui.model.NewConnectorDTO;
import io.kafbat.ui.model.TaskDTO;
import io.kafbat.ui.model.connect.InternalConnectorInfo;
import io.kafbat.ui.util.ReactiveFailover;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Stream;
import javax.annotation.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Service
@Slf4j
public class KafkaConnectService {
  private final ClusterMapper clusterMapper;
  private final KafkaConnectMapper kafkaConnectMapper;
  private final KafkaConfigSanitizer kafkaConfigSanitizer;
  private final ClustersProperties clustersProperties;

  private final AsyncCache<ConnectCacheKey, List<InternalConnectorInfo>> cachedConnectors;

  public KafkaConnectService(ClusterMapper clusterMapper, KafkaConnectMapper kafkaConnectMapper,
                             KafkaConfigSanitizer kafkaConfigSanitizer,
                             ClustersProperties clustersProperties) {
    this.clusterMapper = clusterMapper;
    this.kafkaConnectMapper = kafkaConnectMapper;
    this.kafkaConfigSanitizer = kafkaConfigSanitizer;
    this.clustersProperties = clustersProperties;
    this.cachedConnectors = Caffeine.newBuilder()
        .expireAfterWrite(clustersProperties.getCache().getConnectCacheExpiry())
        .buildAsync();
  }

  public Flux<ConnectDTO> getConnects(KafkaCluster cluster, boolean withStats) {
    Optional<List<ClustersProperties.@Valid ConnectCluster>> connectClusters =
        Optional.ofNullable(cluster.getOriginalProperties().getKafkaConnect());
    if (withStats) {
      return connectClusters.map(connects ->
              Flux.fromIterable(connects).flatMap(connect -> (
                  getConnectConnectorsFromCache(new ConnectCacheKey(cluster, connect), withStats).map(
                      connectors -> kafkaConnectMapper.toKafkaConnect(connect, connectors, withStats)
                  )
              )
          )
      ).orElse(Flux.fromIterable(List.of()));
    } else {
      return Flux.fromIterable(connectClusters.map(connects ->
          connects.stream().map(c -> kafkaConnectMapper.toKafkaConnect(c, List.of(), withStats)).toList()
      ).orElse(List.of()));
    }
  }

  private Mono<List<InternalConnectorInfo>> getConnectConnectorsFromCache(ConnectCacheKey key, boolean withStats) {
    if (clustersProperties.getCache().isEnabled()) {
      return Mono.fromFuture(
          cachedConnectors.get(key, (t, e) ->
              getConnectConnectors(t.cluster(), t.connect()).collectList().toFuture()
          )
      );
    } else {
      return getConnectConnectors(key.cluster(), key.connect()).collectList();
    }
  }

  private Flux<InternalConnectorInfo> getConnectConnectors(
      KafkaCluster cluster,
      ClustersProperties.ConnectCluster connect) {
    return getConnectorNamesWithErrorsSuppress(cluster, connect.getName()).flatMap(connectorName ->
        Mono.zip(
            getConnector(cluster, connect.getName(), connectorName),
            getConnectorTasks(cluster, connect.getName(), connectorName).collectList()
        ).map(tuple ->
            InternalConnectorInfo.builder()
                .connector(tuple.getT1())
                .config(null)
                .tasks(tuple.getT2())
                .topics(null)
                .build()
        )
    );
  }

  public Flux<FullConnectorInfoDTO> getAllConnectors(final KafkaCluster cluster,
                                                     @Nullable final String search) {
    return getConnects(cluster, false)
        .flatMap(connect ->
            getConnectorNamesWithErrorsSuppress(cluster, connect.getName())
                .flatMap(connectorName ->
                    Mono.zip(
                        getConnector(cluster, connect.getName(), connectorName),
                        getConnectorConfig(cluster, connect.getName(), connectorName),
                        getConnectorTasks(cluster, connect.getName(), connectorName).collectList(),
                        getConnectorTopics(cluster, connect.getName(), connectorName)
                    ).map(tuple ->
                        InternalConnectorInfo.builder()
                            .connector(tuple.getT1())
                            .config(tuple.getT2())
                            .tasks(tuple.getT3())
                            .topics(tuple.getT4().getTopics())
                            .build())))
        .map(kafkaConnectMapper::fullConnectorInfo)
        .filter(matchesSearchTerm(search));
  }

  private Predicate<FullConnectorInfoDTO> matchesSearchTerm(@Nullable final String search) {
    if (search == null) {
      return c -> true;
    }
    return connector -> getStringsForSearch(connector)
        .anyMatch(string -> StringUtils.containsIgnoreCase(string, search));
  }

  private Stream<String> getStringsForSearch(FullConnectorInfoDTO fullConnectorInfo) {
    return Stream.of(
        fullConnectorInfo.getName(),
        fullConnectorInfo.getConnect(),
        fullConnectorInfo.getStatus().getState().getValue(),
        fullConnectorInfo.getType().getValue());
  }

  public Mono<ConnectorTopics> getConnectorTopics(KafkaCluster cluster, String connectClusterName,
                                                  String connectorName) {
    return api(cluster, connectClusterName)
        .mono(c -> c.getConnectorTopics(connectorName))
        .map(result -> result.get(connectorName))
        // old Connect API versions don't have this endpoint, setting empty list for
        // backward-compatibility
        .onErrorResume(Exception.class, e -> Mono.just(new ConnectorTopics().topics(List.of())));
  }

  public Flux<String> getConnectorNames(KafkaCluster cluster, String connectName) {
    return api(cluster, connectName)
        .mono(client -> client.getConnectors(null))
        .flatMapMany(Flux::fromIterable);
  }

  // returns empty flux if there was an error communicating with Connect
  public Flux<String> getConnectorNamesWithErrorsSuppress(KafkaCluster cluster, String connectName) {
    return getConnectorNames(cluster, connectName).onErrorComplete();
  }

  public Mono<ConnectorDTO> createConnector(KafkaCluster cluster, String connectName,
                                            Mono<NewConnectorDTO> connector) {
    return api(cluster, connectName)
        .mono(client ->
            connector
                .flatMap(c -> connectorExists(cluster, connectName, c.getName())
                    .map(exists -> {
                      if (Boolean.TRUE.equals(exists)) {
                        throw new ValidationException(
                            String.format("Connector with name %s already exists", c.getName()));
                      }
                      return c;
                    }))
                .map(kafkaConnectMapper::toClient)
                .flatMap(client::createConnector)
                .flatMap(c -> getConnector(cluster, connectName, c.getName()))
        );
  }

  private Mono<Boolean> connectorExists(KafkaCluster cluster, String connectName,
                                        String connectorName) {
    return getConnectorNames(cluster, connectName)
        .any(name -> name.equals(connectorName));
  }

  public Mono<ConnectorDTO> getConnector(KafkaCluster cluster, String connectName,
                                         String connectorName) {
    return api(cluster, connectName)
        .mono(client -> client.getConnector(connectorName)
            .map(kafkaConnectMapper::fromClient)
            .flatMap(connector ->
                client.getConnectorStatus(connector.getName())
                    // status request can return 404 if tasks not assigned yet
                    .onErrorResume(WebClientResponseException.NotFound.class,
                        e -> emptyStatus(connectorName))
                    .map(connectorStatus -> {
                      var status = connectorStatus.getConnector();
                      var sanitizedConfig = kafkaConfigSanitizer.sanitizeConnectorConfig(connector.getConfig());
                      ConnectorDTO result = new ConnectorDTO()
                          .connect(connectName)
                          .status(kafkaConnectMapper.fromClient(status))
                          .type(connector.getType())
                          .tasks(connector.getTasks())
                          .name(connector.getName())
                          .config(sanitizedConfig);

                      if (connectorStatus.getTasks() != null) {
                        boolean isAnyTaskFailed = connectorStatus.getTasks().stream()
                            .map(TaskStatus::getState)
                            .anyMatch(TaskStatus.StateEnum.FAILED::equals);

                        if (isAnyTaskFailed) {
                          result.getStatus().state(ConnectorStateDTO.TASK_FAILED);
                        }
                      }
                      return result;
                    })
            )
        );
  }

  private Mono<ConnectorStatus> emptyStatus(String connectorName) {
    return Mono.just(new ConnectorStatus()
        .name(connectorName)
        .tasks(List.of())
        .connector(new ConnectorStatusConnector()
            .state(ConnectorStatusConnector.StateEnum.UNASSIGNED)));
  }

  public Mono<Map<String, Object>> getConnectorConfig(KafkaCluster cluster, String connectName,
                                                      String connectorName) {
    return api(cluster, connectName)
        .mono(c -> c.getConnectorConfig(connectorName))
        .map(kafkaConfigSanitizer::sanitizeConnectorConfig);
  }

  public Mono<ConnectorDTO> setConnectorConfig(KafkaCluster cluster, String connectName,
                                               String connectorName, Mono<Map<String, Object>> requestBody) {
    return api(cluster, connectName)
        .mono(c ->
            requestBody
                .flatMap(body -> c.setConnectorConfig(connectorName, body))
                .map(kafkaConnectMapper::fromClient));
  }

  public Mono<Void> deleteConnector(
      KafkaCluster cluster, String connectName, String connectorName) {
    return api(cluster, connectName)
        .mono(c -> c.deleteConnector(connectorName));
  }

  public Mono<Void> updateConnectorState(KafkaCluster cluster, String connectName,
                                         String connectorName, ConnectorActionDTO action) {
    return api(cluster, connectName)
        .mono(client -> switch (action) {
              case RESTART -> client.restartConnector(connectorName, false, false);
              case RESTART_ALL_TASKS -> restartTasks(cluster, connectName, connectorName, task -> true);
              case RESTART_FAILED_TASKS -> restartTasks(cluster, connectName, connectorName,
                  t -> t.getStatus().getState() == ConnectorTaskStatusDTO.FAILED);
              case PAUSE -> client.pauseConnector(connectorName);
              case STOP -> client.stopConnector(connectorName);
              case RESUME -> client.resumeConnector(connectorName);
            }
        );
  }

  private Mono<Void> restartTasks(KafkaCluster cluster, String connectName,
                                  String connectorName, Predicate<TaskDTO> taskFilter) {
    return getConnectorTasks(cluster, connectName, connectorName)
        .filter(taskFilter)
        .flatMap(t ->
            restartConnectorTask(cluster, connectName, connectorName, t.getId().getTask()))
        .then();
  }

  public Flux<TaskDTO> getConnectorTasks(KafkaCluster cluster, String connectName, String connectorName) {
    return api(cluster, connectName)
        .flux(client ->
            client.getConnectorTasks(connectorName)
                .onErrorResume(WebClientResponseException.NotFound.class, e -> Flux.empty())
                .map(kafkaConnectMapper::fromClient)
                .flatMap(task ->
                    client
                        .getConnectorTaskStatus(connectorName, task.getId().getTask())
                        .onErrorResume(WebClientResponseException.NotFound.class, e -> Mono.empty())
                        .map(kafkaConnectMapper::fromClient)
                        .map(task::status)
                ));
  }

  public Mono<Void> restartConnectorTask(KafkaCluster cluster, String connectName,
                                         String connectorName, Integer taskId) {
    return api(cluster, connectName)
        .mono(client -> client.restartConnectorTask(connectorName, taskId));
  }

  public Flux<ConnectorPluginDTO> getConnectorPlugins(KafkaCluster cluster,
                                                      String connectName) {
    return api(cluster, connectName)
        .flux(client -> client.getConnectorPlugins().map(kafkaConnectMapper::fromClient));
  }

  public Mono<ConnectorPluginConfigValidationResponseDTO> validateConnectorPluginConfig(
      KafkaCluster cluster, String connectName, String pluginName, Mono<Map<String, Object>> requestBody) {
    return api(cluster, connectName)
        .mono(client ->
            requestBody
                .flatMap(body ->
                    client.validateConnectorPluginConfig(pluginName, body))
                .map(kafkaConnectMapper::fromClient)
        );
  }

  private ReactiveFailover<KafkaConnectClientApi> api(KafkaCluster cluster, String connectName) {
    var client = cluster.getConnectsClients().get(connectName);
    if (client == null) {
      throw new NotFoundException(
          "Connect %s not found for cluster %s".formatted(connectName, cluster.getName()));
    }
    return client;
  }

  public Mono<Void> resetConnectorOffsets(KafkaCluster cluster, String connectName,
      String connectorName) {
    return api(cluster, connectName)
        .mono(client -> client.resetConnectorOffsets(connectorName))
        .onErrorResume(WebClientResponseException.NotFound.class,
            e -> {
              throw new NotFoundException("Connector %s not found in %s".formatted(connectorName, connectName));
            })
        .onErrorResume(WebClientResponseException.BadRequest.class,
            e -> {
              throw new ConnectorOffsetsResetException(
                  "Failed to reset offsets of connector %s of %s. Make sure it is STOPPED first."
                      .formatted(connectorName, connectName));
            });
  }

  record ConnectCacheKey(KafkaCluster cluster, ClustersProperties.ConnectCluster connect) {}
}
