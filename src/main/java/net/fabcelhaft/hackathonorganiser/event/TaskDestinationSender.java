package net.fabcelhaft.hackathonorganiser.event;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.fabcelhaft.hackathonorganiser.task.TaskService;
import net.fabcelhaft.hackathonorganiser.task.TitlePatternResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Creates a Task for a {@code TASK}-type {@link EventDestination} instead of sending the Event
 * anywhere (feature 011 contracts/title-pattern.md; FR-001, FR-016 - FR-018).
 *
 * <p>Shares the {@code Mono<Void> send(EventDestination, String)} contract of {@link
 * KafkaDestinationSender} and {@link HttpDestinationSender}, so it slots into {@link
 * EventPublisher}'s existing detached dispatch untouched. That inheritance is what satisfies FR-018
 * and SC-003 by construction: the triggering domain occurrence's own reactive chain is never chained
 * to this call (research.md §6).
 *
 * <p>The returned {@code Mono} always completes successfully. A failure — malformed JSON, a database
 * error — is logged and swallowed, exactly as a failed HTTP delivery already is, so the user action
 * that fired the Event never sees an error.
 *
 * <p>Wildcards are resolved against {@code jsonBody} parsed back into a {@link JsonNode}, not against
 * the in-memory payload map. The serialized envelope <em>is</em> the Event's published form, so an
 * {@code Instant} is already ISO-8601 text and a {@code UUID} already its canonical string — FR-011a
 * holds without this class re-implementing Jackson's rendering (research.md §2).
 */
@Component
public class TaskDestinationSender {

    private static final Logger log = LoggerFactory.getLogger(TaskDestinationSender.class);

    private final TaskService taskService;
    private final ObjectMapper objectMapper;

    /**
     * Builds its own {@link ObjectMapper} for the same reason {@link EventPublisher} does: this
     * application's minimal Spring context auto-configures no {@code ObjectMapper} bean. Only reading
     * is done here, so no module registration is needed — the serializing side already rendered every
     * value to its wire form.
     */
    public TaskDestinationSender(TaskService taskService) {
        this.taskService = taskService;
        this.objectMapper = new ObjectMapper();
    }

    public Mono<Void> send(EventDestination destination, String jsonBody) {
        return Mono.fromCallable(() -> objectMapper.readTree(jsonBody))
                .flatMap(envelope -> {
                    EventType eventType = eventTypeOf(envelope);
                    if (eventType == null) {
                        log.warn(
                                "Task Rule '{}' received an Event with no recognisable eventType; no Task created",
                                destination.getName());
                        return Mono.empty();
                    }
                    String title = TitlePatternResolver.resolveTitle(
                            destination.getTaskTitlePattern(), envelope, destination.getName(), eventType);
                    return taskService.createFromEvent(destination, eventType, title);
                })
                .doOnError(ex -> log.warn(
                        "Failed to create a Task for Rule '{}': {}", destination.getName(), ex.toString()))
                .onErrorComplete()
                .then();
    }

    private static EventType eventTypeOf(JsonNode envelope) {
        JsonNode node = envelope.get("eventType");
        if (node == null || !node.isTextual()) {
            return null;
        }
        try {
            return EventType.valueOf(node.asText());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
