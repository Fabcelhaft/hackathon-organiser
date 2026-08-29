package net.fabcelhaft.hackathonorganiser.content;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/**
 * Reactive repository for {@link ContentImage} (T054).
 */
public interface ContentImageRepository extends ReactiveCrudRepository<ContentImage, UUID> {}
