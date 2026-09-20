package net.fabcelhaft.hackathonorganiser.task;

import java.util.UUID;
import org.springframework.data.repository.reactive.ReactiveCrudRepository;

/** Basic CRUD access to {@link Task} (data-model.md "Task"). */
public interface TaskRepository extends ReactiveCrudRepository<Task, UUID> {}
