package com.iasbank.integration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import com.iasbank.config.DataSeeder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.function.client.WebClient;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.RabbitMQContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end business rules against a real MySQL and a real RabbitMQ (Testcontainers): the only
 * way to prove the SQL daily total, the UNIQUE request reference, the row locks under concurrency
 * and the publication of events to the broker.
 * Skipped automatically when Docker is not available.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers(disabledWithoutDocker = true)
@DisplayName("Integración con MySQL real - reglas de negocio de punta a punta")
class TransferIntegrationTest {

    private static final String TRANSFERS = "/api/transfers";
    private static final String QUEUE = "iasbank.transfers.authorized";
    private static final BigDecimal INITIAL_BALANCE = new BigDecimal("10000000");

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("ias_bank");

    @Container
    static final RabbitMQContainer RABBITMQ = new RabbitMQContainer("rabbitmq:3.13-management");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.r2dbc.url", () -> "r2dbc:mysql://%s:%d/ias_bank?sslMode=DISABLED&allowPublicKeyRetrieval=true"
                .formatted(MYSQL.getHost(), MYSQL.getMappedPort(MySQLContainer.MYSQL_PORT)));
        registry.add("spring.r2dbc.username", MYSQL::getUsername);
        registry.add("spring.r2dbc.password", MYSQL::getPassword);
        registry.add("logging.level.org.springframework.r2dbc", () -> "WARN");
        registry.add("spring.rabbitmq.host", RABBITMQ::getHost);
        registry.add("spring.rabbitmq.port", RABBITMQ::getAmqpPort);
        registry.add("iasbank.events.publication-interval-ms", () -> "300");
    }

    @LocalServerPort
    private int port;

    @Autowired
    private WebTestClient client;

    @Autowired
    private DatabaseClient database;

    @Autowired
    private DataSeeder dataSeeder;

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AmqpAdmin amqpAdmin;

    @Autowired
    private ObjectMapper objectMapper;

    /** Each test starts a clean day: seed accounts with their initial balance and no transfers. */
    @BeforeEach
    void startCleanDay() {
        amqpAdmin.purgeQueue(QUEUE, false);
        database.sql("DELETE FROM outbox_events").then()
                .then(database.sql("DELETE FROM transfers").then())
                .then(database.sql("UPDATE accounts SET balance = :balance, daily_limit = 5000000")
                        .bind("balance", INITIAL_BALANCE).then())
                .block();
    }

    @Nested
    @DisplayName("Datos semilla - día limpio al iniciar")
    class CleanDay {

        @Test
        @DisplayName("al iniciar no hay transferencias, las cuentas semilla recuperan su saldo y conservan su límite configurado")
        void startupRestoresACleanDay() {
            post("REF-001", "CTA-1001", "CTA-2001", 5000000).expectStatus().isCreated();
            database.sql("UPDATE accounts SET daily_limit = 3000000 WHERE account_number = 'CTA-1002'").then().block();

            dataSeeder.run(null);

            assertThat(countByReference("REF-001")).isZero();
            assertThat(balanceOf("CTA-1001")).isEqualByComparingTo(INITIAL_BALANCE);
            assertThat(balanceOf("CTA-2001")).isEqualByComparingTo(INITIAL_BALANCE);
            assertThat(dailyLimitOf("CTA-1002")).as("el límite configurado se conserva").isEqualByComparingTo("3000000");
        }

        @Test
        @DisplayName("en un día, una cuenta no puede gastar más que su saldo inicial: el límite diario se agota primero")
        void dailyLimitIsReachedBeforeTheBalance() {
            post("REF-001", "CTA-1001", "CTA-2001", 5000000).expectBody().jsonPath("$.status").isEqualTo("AUTHORIZED");

            post("REF-002", "CTA-1001", "CTA-2001", 1).expectBody()
                    .jsonPath("$.rejectionReason").isEqualTo("DAILY_LIMIT_EXCEEDED");
            assertThat(balanceOf("CTA-1001")).as("todavía le queda saldo").isEqualByComparingTo("5000000");
        }
    }

    @Nested
    @DisplayName("Límite diario configurable")
    class ConfigurableDailyLimit {

        @Test
        @DisplayName("el nuevo límite se aplica a las transferencias siguientes")
        void newLimitAppliesToTransfers() {
            client.put().uri("/api/accounts/CTA-1001/daily-limit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"dailyLimit\":1000000}")
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.dailyLimit").isEqualTo(1000000);

            post("REF-001", "CTA-1001", "CTA-2001", 1000001).expectBody()
                    .jsonPath("$.rejectionReason").isEqualTo("DAILY_LIMIT_EXCEEDED");
            post("REF-002", "CTA-1001", "CTA-2001", 1000000).expectBody()
                    .jsonPath("$.status").isEqualTo("AUTHORIZED");
            assertThat(dailyLimitOf("CTA-1001")).isEqualByComparingTo("1000000");
        }

        @Test
        @DisplayName("un límite inválido responde 400 y no cambia el límite")
        void invalidLimitIsRejected() {
            client.put().uri("/api/accounts/CTA-1001/daily-limit")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"dailyLimit\":0}")
                    .exchange()
                    .expectStatus().isBadRequest();

            assertThat(dailyLimitOf("CTA-1001")).isEqualByComparingTo("5000000");
        }
    }

    @Nested
    @DisplayName("RF01 / RF03 - procesar y conservar el resultado")
    class ProcessAndKeepResult {

        @Test
        @DisplayName("una transferencia válida se autoriza, se persiste con su fecha y mueve los saldos")
        void authorizesAndPersists() {
            post("REF-001", "CTA-1001", "CTA-2001", 600000)
                    .expectStatus().isCreated()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("AUTHORIZED")
                    .jsonPath("$.processedAt").exists();

            assertThat(row("REF-001")).containsEntry("status", "AUTHORIZED").containsEntry("rejection_reason", null);
            assertThat(row("REF-001").get("processed_at")).isNotNull();
            assertThat(balanceOf("CTA-1001")).isEqualByComparingTo("9400000");
            assertThat(balanceOf("CTA-2001")).isEqualByComparingTo("10600000");
        }

        @Test
        @DisplayName("una transferencia rechazada se persiste con su razón y no modifica saldos")
        void rejectedIsPersistedWithReason() {
            post("REF-001", "CTA-1001", "CTA-1001", 1000)
                    .expectStatus().isCreated()
                    .expectBody().jsonPath("$.rejectionReason").isEqualTo("SAME_ACCOUNT");

            assertThat(row("REF-001")).containsEntry("status", "REJECTED").containsEntry("rejection_reason", "SAME_ACCOUNT");
            assertThat(balanceOf("CTA-1001")).isEqualByComparingTo(INITIAL_BALANCE);
        }
    }

    @Nested
    @DisplayName("RF02 / RF03 - reglas de negocio y límite diario")
    class BusinessRules {

        @Test
        @DisplayName("se autoriza hasta alcanzar exactamente el límite y se rechaza lo que lo supera")
        void dailyLimitBoundary() {
            post("REF-001", "CTA-1001", "CTA-2001", 3000000).expectBody().jsonPath("$.status").isEqualTo("AUTHORIZED");
            post("REF-002", "CTA-1001", "CTA-1002", 2000000).expectBody().jsonPath("$.status").isEqualTo("AUTHORIZED");
            post("REF-003", "CTA-1001", "CTA-2001", 1).expectBody()
                    .jsonPath("$.status").isEqualTo("REJECTED")
                    .jsonPath("$.rejectionReason").isEqualTo("DAILY_LIMIT_EXCEEDED");

            assertThat(authorizedTotal("CTA-1001")).isEqualByComparingTo("5000000");
        }

        @Test
        @DisplayName("una solicitud rechazada no incrementa el acumulado diario")
        void rejectedDoesNotCountForTheLimit() {
            post("REF-001", "CTA-1001", "CTA-2001", 6000000).expectBody()
                    .jsonPath("$.rejectionReason").isEqualTo("DAILY_LIMIT_EXCEEDED");
            post("REF-002", "CTA-1001", "CTA-2001", 5000000).expectBody()
                    .jsonPath("$.status").isEqualTo("AUTHORIZED");
        }

        @Test
        @DisplayName("el límite es por cuenta origen: otra cuenta no se ve afectada")
        void limitIsPerSourceAccount() {
            post("REF-001", "CTA-1001", "CTA-2001", 5000000).expectBody().jsonPath("$.status").isEqualTo("AUTHORIZED");
            post("REF-002", "CTA-1002", "CTA-2001", 5000000).expectBody().jsonPath("$.status").isEqualTo("AUTHORIZED");
        }

        @Test
        @DisplayName("monto no positivo y cuenta inexistente se rechazan con su razón")
        void otherRejections() {
            post("REF-001", "CTA-1001", "CTA-2001", 0).expectBody().jsonPath("$.rejectionReason").isEqualTo("INVALID_AMOUNT");
            post("REF-002", "CTA-9999", "CTA-2001", 1000).expectBody().jsonPath("$.rejectionReason").isEqualTo("ACCOUNT_NOT_FOUND");
        }
    }

    @Nested
    @DisplayName("RF05 - referencias repetidas")
    class RepeatedReferences {

        @Test
        @DisplayName("la misma referencia con los mismos datos devuelve el original sin procesar de nuevo")
        void sameDataReturnsOriginal() {
            post("REF-001", "CTA-1001", "CTA-2001", 600000).expectStatus().isCreated();

            post("REF-001", "CTA-1001", "CTA-2001", 600000)
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.id").isEqualTo(row("REF-001").get("id"));

            assertThat(countByReference("REF-001")).isEqualTo(1);
            assertThat(balanceOf("CTA-1001")).as("debitado una sola vez").isEqualByComparingTo("9400000");
            assertThat(authorizedTotal("CTA-1001")).as("acumulado incrementado una sola vez").isEqualByComparingTo("600000");
        }

        @Test
        @DisplayName("la misma referencia con datos diferentes responde 422 y conserva la operación original")
        void differentDataIsAConflict() {
            post("REF-001", "CTA-1001", "CTA-2001", 600000).expectStatus().isCreated();

            post("REF-001", "CTA-1001", "CTA-1002", 900000).expectStatus().isEqualTo(422);

            assertThat(countByReference("REF-001")).isEqualTo(1);
            assertThat(row("REF-001")).containsEntry("destination_account_id", "CTA-2001");
            assertThat(balanceOf("CTA-1001")).isEqualByComparingTo("9400000");
        }
    }

    @Nested
    @DisplayName("RF04 - solicitudes simultáneas")
    class ConcurrentRequests {

        @Test
        @DisplayName("20 transferencias simultáneas de 1.000.000 desde la misma cuenta autorizan exactamente 5.000.000")
        void dailyLimitHoldsUnderConcurrency() {
            List<Integer> statuses = sendConcurrently(20, i -> body("C-" + i, "CTA-1001", "CTA-2001", 1000000));

            assertThat(statuses).as("ninguna solicitud falla").containsOnly(201);
            assertThat(authorizedTotal("CTA-1001")).isEqualByComparingTo("5000000");
            assertThat(countByStatus("CTA-1001", "AUTHORIZED")).isEqualTo(5);
            assertThat(countByStatus("CTA-1001", "REJECTED")).isEqualTo(15);
            assertThat(balanceOf("CTA-1001")).isEqualByComparingTo("5000000");
            assertThat(balanceOf("CTA-2001")).isEqualByComparingTo("15000000");
        }

        @Test
        @DisplayName("transferencias cruzadas simultáneas (A→B y B→A) no generan bloqueos mutuos ni pierden dinero")
        void crossTransfersDoNotDeadlock() {
            List<Integer> statuses = sendConcurrently(20, i -> i % 2 == 0
                    ? body("X-" + i, "CTA-1002", "CTA-2001", 100000)
                    : body("X-" + i, "CTA-2001", "CTA-1002", 100000));

            assertThat(statuses).containsOnly(201);
            assertThat(countByStatus("CTA-1002", "AUTHORIZED") + countByStatus("CTA-2001", "AUTHORIZED")).isEqualTo(20);
            assertThat(balanceOf("CTA-1002").add(balanceOf("CTA-2001")))
                    .as("el dinero total se conserva")
                    .isEqualByComparingTo("20000000");
        }

        @Test
        @DisplayName("la misma referencia enviada 10 veces a la vez se registra una sola vez y todas reciben el mismo resultado")
        void sameReferenceConcurrently() {
            List<Integer> statuses = sendConcurrently(10, i -> body("SAME-REF", "CTA-1002", "CTA-1001", 1000));

            assertThat(countByReference("SAME-REF")).isEqualTo(1);
            assertThat(statuses).filteredOn(status -> status == 201).hasSize(1);
            assertThat(statuses).filteredOn(status -> status == 200).hasSize(9);
            assertThat(balanceOf("CTA-1002")).isEqualByComparingTo("9999000");
        }
    }

    @Nested
    @DisplayName("Punto opcional - publicación de eventos en RabbitMQ")
    class Events {

        @Test
        @DisplayName("una transferencia autorizada publica un evento en la cola, con su información y su id de mensaje")
        void authorizedTransferIsPublished() throws Exception {
            post("REF-001", "CTA-1001", "CTA-2001", 600000).expectStatus().isCreated();

            awaitEventStatus("REF-001", "PUBLISHED");
            Message message = rabbitTemplate.receive(QUEUE, 5000);

            assertThat(message).isNotNull();
            JsonNode payload = objectMapper.readTree(message.getBody());
            assertThat(payload.get("requestReference").asText()).isEqualTo("REF-001");
            assertThat(payload.get("sourceAccountId").asText()).isEqualTo("CTA-1001");
            assertThat(payload.get("amount").decimalValue()).isEqualByComparingTo("600000");
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(eventIdOf("REF-001"));
            assertThat(message.getMessageProperties().getType()).isEqualTo("TRANSFER_AUTHORIZED");
        }

        @Test
        @DisplayName("una transferencia rechazada no genera evento")
        void rejectedTransferIsNotPublished() {
            post("REF-001", "CTA-1001", "CTA-1001", 1000).expectBody().jsonPath("$.status").isEqualTo("REJECTED");

            assertThat(countEvents()).isZero();
        }

        @Test
        @DisplayName("una referencia repetida no genera un segundo evento")
        void repeatedReferenceIsPublishedOnce() {
            post("REF-001", "CTA-1001", "CTA-2001", 600000).expectStatus().isCreated();
            post("REF-001", "CTA-1001", "CTA-2001", 600000).expectStatus().isOk();

            assertThat(countEvents()).isEqualTo(1);
        }

        @Test
        @DisplayName("con 20 solicitudes simultáneas se publican exactamente 5 eventos (las 5 autorizadas)")
        void concurrentRequestsPublishOneEventPerAuthorizedTransfer() {
            sendConcurrently(20, i -> body("EV-" + i, "CTA-1001", "CTA-2001", 1000000));

            assertThat(countEvents()).isEqualTo(5);
            for (String reference : authorizedReferences()) {
                awaitEventStatus(reference, "PUBLISHED");
            }
            assertThat(amqpAdmin.getQueueInfo(QUEUE).getMessageCount()).isEqualTo(5);
        }

        @Test
        @DisplayName("la API de administración informa los eventos publicados y los mensajes en la cola")
        void administrationApi() {
            post("REF-001", "CTA-1001", "CTA-2001", 600000).expectStatus().isCreated();
            awaitEventStatus("REF-001", "PUBLISHED");

            client.get().uri("/api/events/summary").exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.published").isEqualTo(1)
                    .jsonPath("$.failed").isEqualTo(0)
                    .jsonPath("$.queuedMessages").isEqualTo(1)
                    .jsonPath("$.maxRetries").isEqualTo(3);
            client.get().uri("/api/events?status=PUBLISHED").exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.content[0].aggregateId").isEqualTo("REF-001")
                    .jsonPath("$.content[0].retries").isEqualTo(0);
        }
    }

    @Nested
    @DisplayName("RF06 - consultas")
    class Queries {

        @Test
        @DisplayName("consulta una transferencia por su referencia, o responde 404 si no existe")
        void byReference() {
            post("REF-001", "CTA-1001", "CTA-2001", 600000);

            client.get().uri(TRANSFERS + "/REF-001").exchange()
                    .expectStatus().isOk()
                    .expectBody().jsonPath("$.amount").isEqualTo(600000.0);
            client.get().uri(TRANSFERS + "/NOPE").exchange().expectStatus().isNotFound();
        }

        @Test
        @DisplayName("las recientes se paginan de la más reciente a la más antigua")
        void recentArePaginatedNewestFirst() {
            for (int i = 1; i <= 5; i++) {
                post("REF-00" + i, "CTA-2001", "CTA-1002", 1000 * i);
            }

            client.get().uri(TRANSFERS + "?page=0&size=2").exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.content[0].requestReference").isEqualTo("REF-005")
                    .jsonPath("$.content[1].requestReference").isEqualTo("REF-004")
                    .jsonPath("$.totalElements").isEqualTo(5)
                    .jsonPath("$.totalPages").isEqualTo(3);
            client.get().uri(TRANSFERS + "?page=2&size=2").exchange()
                    .expectBody().jsonPath("$.content[0].requestReference").isEqualTo("REF-001");
        }
    }

    // ---------- helpers ----------

    private WebTestClient.ResponseSpec post(String reference, String source, String destination, long amount) {
        return client.post().uri(TRANSFERS)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body(reference, source, destination, amount))
                .exchange();
    }

    private static String body(String reference, String source, String destination, long amount) {
        return """
                {"requestReference":"%s","sourceAccountId":"%s","destinationAccountId":"%s","amount":%d}"""
                .formatted(reference, source, destination, amount);
    }

    /** Fires all requests at the same time and returns their HTTP status codes. */
    private List<Integer> sendConcurrently(int requests, Function<Integer, String> bodyFor) {
        WebClient webClient = WebClient.create("http://localhost:" + port);
        return Flux.range(1, requests)
                .flatMap(i -> webClient.post().uri(TRANSFERS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(bodyFor.apply(i))
                        .exchangeToMono(response -> response.releaseBody().thenReturn(response.statusCode().value())),
                        requests)
                .collectList()
                .block(Duration.ofSeconds(60));
    }

    private Map<String, Object> row(String reference) {
        return database.sql("SELECT id, status, rejection_reason, destination_account_id, processed_at FROM transfers WHERE request_reference = :ref")
                .bind("ref", reference)
                .fetch().one()
                .block();
    }

    private BigDecimal balanceOf(String account) {
        return database.sql("SELECT balance FROM accounts WHERE account_number = :account")
                .bind("account", account)
                .map(result -> result.get("balance", BigDecimal.class))
                .one().block();
    }

    private BigDecimal dailyLimitOf(String account) {
        return database.sql("SELECT daily_limit FROM accounts WHERE account_number = :account")
                .bind("account", account)
                .map(result -> result.get("daily_limit", BigDecimal.class))
                .one().block();
    }

    private BigDecimal authorizedTotal(String account) {
        return database.sql("SELECT COALESCE(SUM(amount), 0) AS total FROM transfers WHERE source_account_id = :account AND status = 'AUTHORIZED'")
                .bind("account", account)
                .map(result -> result.get("total", BigDecimal.class))
                .one().block();
    }

    private long countByStatus(String account, String status) {
        return database.sql("SELECT COUNT(*) AS total FROM transfers WHERE source_account_id = :account AND status = :status")
                .bind("account", account)
                .bind("status", status)
                .map(result -> result.get("total", Long.class))
                .one().block();
    }

    /** The publisher runs every 300 ms in this test; waits up to 10 s for the expected status. */
    private void awaitEventStatus(String reference, String expectedStatus) {
        String status = null;
        for (int attempt = 0; attempt < 50 && !expectedStatus.equals(status); attempt++) {
            status = database.sql("SELECT status FROM outbox_events WHERE aggregate_id = :ref")
                    .bind("ref", reference)
                    .map(result -> result.get("status", String.class))
                    .one().block();
            if (!expectedStatus.equals(status)) {
                Mono.delay(Duration.ofMillis(200)).block();
            }
        }
        assertThat(status).as("estado del evento de " + reference).isEqualTo(expectedStatus);
    }

    private String eventIdOf(String reference) {
        return database.sql("SELECT event_id FROM outbox_events WHERE aggregate_id = :ref")
                .bind("ref", reference)
                .map(result -> result.get("event_id", String.class))
                .one().block();
    }

    private long countEvents() {
        return database.sql("SELECT COUNT(*) AS total FROM outbox_events")
                .map(result -> result.get("total", Long.class))
                .one().block();
    }

    private List<String> authorizedReferences() {
        return database.sql("SELECT request_reference FROM transfers WHERE status = 'AUTHORIZED'")
                .map(result -> result.get("request_reference", String.class))
                .all().collect(ArrayList<String>::new, ArrayList::add).block();
    }

    private long countByReference(String reference) {
        return database.sql("SELECT COUNT(*) AS total FROM transfers WHERE request_reference = :ref")
                .bind("ref", reference)
                .map(result -> result.get("total", Long.class))
                .one().block();
    }
}
