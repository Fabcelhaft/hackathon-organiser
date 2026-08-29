package net.fabcelhaft.hackathonorganiser.topic;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * Reactive repository for {@link Topic} (T047).
 */
public interface TopicRepository extends ReactiveCrudRepository<Topic, UUID> {}
