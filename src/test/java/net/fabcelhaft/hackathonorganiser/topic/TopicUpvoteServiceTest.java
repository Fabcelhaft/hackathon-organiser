package net.fabcelhaft.hackathonorganiser.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link TopicUpvoteService} (data-model.md "New Entity: Topic Upvote"; FR-001,
 * FR-002; research.md §2, §3): the pre-check-plus-constraint-backstop upvote/withdraw shape and
 * the two bulk read queries. Per Constitution Development Workflow #4, the multi-operator reactive
 * chains under test are verified with {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class TopicUpvoteServiceTest {

    @Mock
    private DatabaseClient databaseClient;

    private TopicUpvoteService topicUpvoteService;

    @BeforeEach
    void setUp() {
        topicUpvoteService = new TopicUpvoteService(databaseClient);
    }

    // --- upvote: pre-check + insert, idempotent on a duplicate cast (research.md §3) --------------

    @Test
    void upvoteInsertsWhenNoActiveUpvoteExists() {
        UUID topicId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubExists("topic_upvotes WHERE", false);
        DatabaseClient.GenericExecuteSpec insertSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        lenient().when(databaseClient.sql(contains("INSERT INTO topic_upvotes"))).thenReturn(insertSpec);
        lenient().when(insertSpec.bind(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(insertSpec);
        lenient().when(insertSpec.then()).thenReturn(Mono.empty());

        StepVerifier.create(topicUpvoteService.upvote(topicId, userId)).verifyComplete();

        verify(databaseClient).sql(contains("INSERT INTO topic_upvotes"));
    }

    @Test
    void upvoteIsANoOpWhenTheUserAlreadyHasAnActiveUpvote() {
        UUID topicId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubExists("topic_upvotes WHERE", true);

        StepVerifier.create(topicUpvoteService.upvote(topicId, userId)).verifyComplete();

        verify(databaseClient, never()).sql(contains("INSERT INTO topic_upvotes"));
    }

    @Test
    void upvoteSwallowsALostRaceDuplicateInsertAsSuccess() {
        UUID topicId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        stubExists("topic_upvotes WHERE", false);
        DatabaseClient.GenericExecuteSpec insertSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        lenient().when(databaseClient.sql(contains("INSERT INTO topic_upvotes"))).thenReturn(insertSpec);
        lenient().when(insertSpec.bind(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(insertSpec);
        lenient()
                .when(insertSpec.then())
                .thenReturn(Mono.error(new DataIntegrityViolationException("duplicate key")));

        StepVerifier.create(topicUpvoteService.upvote(topicId, userId)).verifyComplete();
    }

    // --- withdraw: plain delete, no-op if nothing existed -------------------------------------------

    @Test
    void withdrawIssuesADeleteScopedToTheTopicAndUser() {
        UUID topicId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        DatabaseClient.GenericExecuteSpec deleteSpec = mock(DatabaseClient.GenericExecuteSpec.class);
        lenient().when(databaseClient.sql(contains("DELETE FROM topic_upvotes"))).thenReturn(deleteSpec);
        lenient().when(deleteSpec.bind(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(deleteSpec);
        lenient().when(deleteSpec.then()).thenReturn(Mono.empty());

        StepVerifier.create(topicUpvoteService.withdraw(topicId, userId)).verifyComplete();

        verify(databaseClient).sql(contains("DELETE FROM topic_upvotes"));
        verify(deleteSpec).bind("tid", topicId);
        verify(deleteSpec).bind("uid", userId);
    }

    // --- countsFor: one grouped COUNT query -----------------------------------------------------

    @Test
    @SuppressWarnings("unchecked")
    void countsForReturnsAMapKeyedByTopicId() {
        UUID topicId1 = UUID.randomUUID();
        UUID topicId2 = UUID.randomUUID();
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<Map.Entry<UUID, Integer>> fetch = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(contains("GROUP BY topic_id"))).thenReturn(spec);
        lenient().when(spec.bind(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(spec);
        lenient()
                .when(spec.map(org.mockito.ArgumentMatchers
                        .<java.util.function.BiFunction<io.r2dbc.spi.Row, io.r2dbc.spi.RowMetadata, Map.Entry<UUID, Integer>>>
                                any()))
                .thenReturn(fetch);
        lenient()
                .when(fetch.all())
                .thenReturn(Flux.just(Map.entry(topicId1, 3), Map.entry(topicId2, 1)));

        StepVerifier.create(topicUpvoteService.countsFor(Set.of(topicId1, topicId2)))
                .assertNext(counts -> {
                    assertThat(counts).hasSize(2);
                    assertThat(counts.get(topicId1)).isEqualTo(3);
                    assertThat(counts.get(topicId2)).isEqualTo(1);
                })
                .verifyComplete();
    }

    @Test
    void countsForReturnsAnEmptyMapForAnEmptySetWithoutQuerying() {
        StepVerifier.create(topicUpvoteService.countsFor(Set.of()))
                .assertNext(counts -> assertThat(counts).isEmpty())
                .verifyComplete();

        verify(databaseClient, never()).sql(anyString());
    }

    // --- viewerUpvotedTopicIds: one query returning the viewer's own subset ----------------------

    @Test
    @SuppressWarnings("unchecked")
    void viewerUpvotedTopicIdsReturnsOnlyTheIdsTheViewerHasUpvoted() {
        UUID viewerUserId = UUID.randomUUID();
        UUID upvotedTopicId = UUID.randomUUID();
        UUID notUpvotedTopicId = UUID.randomUUID();
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<UUID> fetch = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(contains("topic_upvotes"))).thenReturn(spec);
        lenient().when(spec.bind(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(spec);
        lenient().when(spec.mapValue(UUID.class)).thenReturn(fetch);
        lenient().when(fetch.all()).thenReturn(Flux.just(upvotedTopicId));

        StepVerifier.create(
                        topicUpvoteService.viewerUpvotedTopicIds(Set.of(upvotedTopicId, notUpvotedTopicId), viewerUserId))
                .assertNext(ids -> {
                    assertThat(ids).containsExactly(upvotedTopicId);
                })
                .verifyComplete();
    }

    @Test
    void viewerUpvotedTopicIdsReturnsAnEmptySetForAnEmptySetWithoutQuerying() {
        StepVerifier.create(topicUpvoteService.viewerUpvotedTopicIds(Set.of(), UUID.randomUUID()))
                .assertNext(ids -> assertThat(ids).isEmpty())
                .verifyComplete();

        verify(databaseClient, never()).sql(anyString());
    }

    // --- test helpers ------------------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private void stubExists(String sqlContains, boolean exists) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<Boolean> fetch = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(contains(sqlContains))).thenReturn(spec);
        lenient().when(spec.bind(anyString(), org.mockito.ArgumentMatchers.any())).thenReturn(spec);
        lenient().when(spec.mapValue(Boolean.class)).thenReturn(fetch);
        lenient().when(fetch.one()).thenReturn(Mono.just(exists));
    }
}
