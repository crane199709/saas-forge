package io.saasforge.demo;

import static org.assertj.core.api.Assertions.assertThat;

import io.saasforge.demo.notification.entity.OrderNotification;
import io.saasforge.demo.notification.service.OrderNotificationService;
import io.saasforge.demo.order.entity.Order;
import io.saasforge.demo.order.model.OrderCreated;
import io.saasforge.demo.order.service.OrderService;
import io.saasforge.sdk.core.CurrentTenant;
import io.saasforge.sdk.mq.EventPublisher;
import io.saasforge.sdk.provisioning.ReferenceDataChanged;
import io.saasforge.sdk.provisioning.TenantCreated;
import io.saasforge.sdk.referencedata.ReferenceData;
import io.saasforge.sdk.referencedata.ReferenceDataAutoConfiguration;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * 以「订单已创建」验证 Outbox 链路：事件随业务事务原子落库、MQ 故障恢复后全部送达、重复投递只处理一次，
 * 消费端恢复租户上下文后租户隔离照常生效。
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class OutboxMessagingTest {
    static final UUID TENANT_A = UUID.fromString("019a0110-0000-7000-8000-0000000000aa");
    static final UUID TENANT_B = UUID.fromString("019a0110-0000-7000-8000-0000000000bb");
    static final String ROCKETMQ_HOME = "/home/rocketmq/rocketmq-5.3.2";
    static final int PROXY_PORT = 8081;
    static final Duration DELIVERY_TIMEOUT = Duration.ofSeconds(60);
    /** 否定断言（某事件不应被处理）没有完成信号，只能等待一段足以完成投递与消费的时间。 */
    static final Duration QUIET_PERIOD = Duration.ofSeconds(3);

    @Container
    static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine")
            .withDatabaseName("forge")
            .withCopyFileToContainer(
                    MountableFile.forHostPath("../../../deploy/postgres/init"), "/docker-entrypoint-initdb.d/");

    /**
     * namesrv、broker 与 proxy 放在同一容器。proxy 用 cluster 模式：local 模式（broker 内嵌 proxy）的路由返回容器内 IP，
     * 宿主机连不上；cluster 模式沿用客户端请求中的地址，并按配置沿用其端口，适配随机映射的端口。
     */
    @Container
    static final GenericContainer<?> rocketmq = new GenericContainer<>("apache/rocketmq:5.3.2")
            .withExposedPorts(PROXY_PORT)
            .withEnv("JAVA_OPT_EXT", "-Xms256m -Xmx512m -Xmn128m -XX:MaxDirectMemorySize=256m")
            .withCopyToContainer(
                    Transferable.of("{\"rocketMQClusterName\":\"DefaultCluster\",\"useEndpointPortFromRequest\":true}"),
                    ROCKETMQ_HOME + "/conf/rmq-proxy.json")
            .withCommand(
                    "sh",
                    "-c",
                    // cluster 模式的 proxy 启动时要在集群上建系统 Topic，broker 尚未注册到 namesrv 时会直接退出；
                    // 未指定配置文件时 broker 名取主机名，因此按其监听端口判断是否已注册
                    ROCKETMQ_HOME + "/bin/mqnamesrv & " + ROCKETMQ_HOME + "/bin/mqbroker -n localhost:9876 & "
                            + "until " + ROCKETMQ_HOME
                            + "/bin/mqadmin clusterList -n localhost:9876 | grep -q :10911; "
                            + "do sleep 2; done; exec " + ROCKETMQ_HOME + "/bin/mqproxy -n localhost:9876")
            .waitingFor(Wait.forListeningPorts(PROXY_PORT))
            .withStartupTimeout(Duration.ofMinutes(3));

    /**
     * 消费端启动时就要拉取所订阅 Topic 的路由，因此在创建 Spring 上下文的这一步先建好 Topic，并等到路由可查。
     *
     * <p>mqadmin 失败时退出码仍为 0，只能检查输出。
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        // 服务订阅的全部 Topic：订单事件，SDK 为租户初始化订阅的 Provisioning 事件，以及参考数据变更事件
        for (String topic : List.of(OrderCreated.TOPIC, TenantCreated.TOPIC, ReferenceDataChanged.TOPIC)) {
            Awaitility.await()
                    .atMost(Duration.ofMinutes(2))
                    .pollInterval(Duration.ofSeconds(2))
                    .until(() -> rocketmq.execInContainer(
                                            "sh",
                                            "-c",
                                            ROCKETMQ_HOME
                                                    + "/bin/mqadmin updateTopic -n localhost:9876 -c DefaultCluster -t "
                                                    + topic + " -a +message.type=NORMAL; " + ROCKETMQ_HOME
                                                    + "/bin/mqadmin topicRoute -n localhost:9876 -t " + topic
                                                    + " | grep -q brokerName")
                                    .getExitCode()
                            == 0);
        }
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("forge.mq.enabled", () -> true);
        registry.add("forge.mq.endpoints", OutboxMessagingTest::endpoints);
        registry.add("forge.mq.request-timeout", () -> "2s");
        registry.add("forge.mq.poll-interval", () -> "200ms");
        registry.add("forge.mq.initial-backoff", () -> "200ms");
        registry.add("forge.mq.max-backoff", () -> "1s");
        TestTokens.register(registry);
        TestReferenceData.register(registry);
    }

    @Autowired
    OrderService orderService;

    @Autowired
    OrderNotificationService notificationService;

    @Autowired
    TransactionTemplate transactionTemplate;

    @Autowired
    ReferenceData referenceData;

    @Autowired
    EventPublisher eventPublisher;

    @Test
    void eventIsConsumedInPublishersTenant() {
        UUID orderId = createOrder(TENANT_A, "A 的订单");

        awaitNotified(TENANT_A, orderId);
        CurrentTenant.run(
                TENANT_B,
                () -> assertThat(notificationService.findByOrder(orderId)).isEmpty());
        // 消费线程没有认证上下文：时间照常填充，操作人记为 NULL（系统），不从发布方传递
        CurrentTenant.run(TENANT_A, () -> {
            OrderNotification notification =
                    notificationService.findByOrder(orderId).get(0);
            assertThat(notification.getCreatedAt()).isNotNull().isEqualTo(notification.getUpdatedAt());
            assertThat(notification.getCreatedBy()).isNull();
            assertThat(notification.getUpdatedBy()).isNull();
            assertThat(notification.getDeleted()).isFalse();
        });
    }

    @Test
    void rolledBackTransactionProducesNoEvent() throws Exception {
        var rolledBack = new AtomicReference<UUID>();
        CurrentTenant.run(
                TENANT_A,
                () -> transactionTemplate.executeWithoutResult(status -> {
                    rolledBack.set(
                            orderService.create("回滚的订单", BigDecimal.TEN, null).getId());
                    status.setRollbackOnly();
                }));
        UUID committed = createOrder(TENANT_A, "提交的订单");

        awaitNotified(TENANT_A, committed);
        Thread.sleep(QUIET_PERIOD.toMillis());
        CurrentTenant.run(
                TENANT_A,
                () -> assertThat(notificationService.findByOrder(rolledBack.get()))
                        .isEmpty());
    }

    @Test
    void eventsProducedWhileBrokerIsDownAreAllDeliveredAfterRecovery() throws Exception {
        var docker = DockerClientFactory.instance().client();
        List<UUID> ordersOfA = new ArrayList<>();
        List<UUID> ordersOfB = new ArrayList<>();
        docker.pauseContainerCmd(rocketmq.getContainerId()).exec();
        try {
            for (int i = 0; i < 3; i++) {
                ordersOfA.add(createOrder(TENANT_A, "故障期间 A 的订单 " + i));
                ordersOfB.add(createOrder(TENANT_B, "故障期间 B 的订单 " + i));
            }
            // 停留超过一次发送超时，确保投递器确实经历了发送失败与退避
            Thread.sleep(QUIET_PERIOD.toMillis());
        } finally {
            docker.unpauseContainerCmd(rocketmq.getContainerId()).exec();
        }

        ordersOfA.forEach(orderId -> awaitNotified(TENANT_A, orderId));
        ordersOfB.forEach(orderId -> awaitNotified(TENANT_B, orderId));
    }

    @Test
    void duplicateDeliveryOfSameEventIsHandledOnce() throws Exception {
        UUID orderId = UUID.randomUUID();
        byte[] body =
                ("{\"orderId\":\"" + orderId + "\",\"title\":\"重复投递\",\"amount\":1}").getBytes(StandardCharsets.UTF_8);
        String eventId = UUID.randomUUID().toString();
        var provider = ClientServiceProvider.loadService();
        var configuration = ClientConfiguration.newBuilder()
                .setEndpoints(endpoints())
                .enableSsl(false)
                .build();
        try (Producer producer = provider.newProducerBuilder()
                .setClientConfiguration(configuration)
                .build()) {
            for (int i = 0; i < 2; i++) {
                producer.send(provider.newMessageBuilder()
                        .setTopic(OrderCreated.TOPIC)
                        .setTag(OrderCreated.class.getSimpleName())
                        .setKeys(eventId)
                        .addProperty(EventPublisher.EVENT_ID_PROPERTY, eventId)
                        .addProperty(EventPublisher.TENANT_ID_PROPERTY, TENANT_A.toString())
                        .setBody(body)
                        .build());
            }
        }

        awaitNotified(TENANT_A, orderId);
        Thread.sleep(QUIET_PERIOD.toMillis());
        CurrentTenant.run(
                TENANT_A,
                () -> assertThat(notificationService.findByOrder(orderId)).hasSize(1));
    }

    /**
     * 参考数据缓存在每个实例的进程内，同一服务的每个实例都要收到 {@link ReferenceDataChanged}，而不是像服务的消费组那样只分给其中一个。
     * 另起一个只含参考数据能力的上下文作为同一服务的第二个实例。
     */
    @Test
    void referenceDataChangedEvictsTheTenantsCacheOnEveryInstance() {
        TestReferenceData.referral(TENANT_A, "enabled");
        try (ConfigurableApplicationContext secondInstance = new SpringApplicationBuilder(SecondInstance.class)
                .web(WebApplicationType.NONE)
                // 以命令行参数传入：它同样读取服务的 application.yml，默认属性的优先级低于其中的本地开发地址
                .run(
                        "--forge.reference-data.iam-uri=" + TestReferenceData.uri(),
                        "--forge.reference-data.token=" + TestReferenceData.TOKEN,
                        "--forge.mq.endpoints=" + endpoints())) {
            List<ReferenceData> instances = List.of(referenceData, secondInstance.getBean(ReferenceData.class));
            for (ReferenceData instance : instances) {
                assertThat(referralSelectable(instance)).isTrue();
            }

            // 租户在 iam 中停用了转介绍，iam 随后发布事件；缓存过期时间为 10 分钟，只有事件能让新值这么快生效
            TestReferenceData.referral(TENANT_A, "disabled");
            CurrentTenant.run(
                    TENANT_A,
                    () -> transactionTemplate.executeWithoutResult(status ->
                            eventPublisher.publish(ReferenceDataChanged.TOPIC, new ReferenceDataChanged(TENANT_A))));

            for (ReferenceData instance : instances) {
                Awaitility.await().atMost(DELIVERY_TIMEOUT).until(() -> !referralSelectable(instance));
            }
        }
    }

    private static boolean referralSelectable(ReferenceData instance) {
        var selectable = new AtomicReference<Boolean>();
        CurrentTenant.run(TENANT_A, () -> selectable.set(instance.isSelectable("customer-source", "referral")));
        return selectable.get();
    }

    /**
     * 同一服务的另一个实例：只装配参考数据读取与变更订阅，连接同一个 RocketMQ。不标 {@code @Configuration}：
     * 测试类内嵌的配置类会取代 Spring Boot 应用成为测试上下文的配置。
     */
    @ImportAutoConfiguration(ReferenceDataAutoConfiguration.class)
    static class SecondInstance {
        @Bean
        ClientConfiguration clientConfiguration(Environment environment) {
            return ClientConfiguration.newBuilder()
                    .setEndpoints(environment.getRequiredProperty("forge.mq.endpoints"))
                    .enableSsl(false)
                    .build();
        }
    }

    private UUID createOrder(UUID tenantId, String title) {
        var created = new AtomicReference<Order>();
        CurrentTenant.run(tenantId, () -> created.set(orderService.create(title, BigDecimal.ONE, null)));
        return created.get().getId();
    }

    private void awaitNotified(UUID tenantId, UUID orderId) {
        Awaitility.await()
                .atMost(DELIVERY_TIMEOUT)
                .untilAsserted(() -> CurrentTenant.run(
                        tenantId,
                        () -> assertThat(notificationService.findByOrder(orderId))
                                .hasSize(1)));
    }

    private static String endpoints() {
        return rocketmq.getHost() + ":" + rocketmq.getMappedPort(PROXY_PORT);
    }
}
