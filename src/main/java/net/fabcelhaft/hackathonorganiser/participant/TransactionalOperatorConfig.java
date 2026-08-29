package net.fabcelhaft.hackathonorganiser.participant;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

/**
 * Exposes the {@link TransactionalOperator} {@link ParticipantService} composes its
 * registration/self-edit write path with (research.md §4). Spring Boot's {@code
 * spring-boot-starter-data-r2dbc} auto-configures the underlying {@link ReactiveTransactionManager}
 * bean the moment a {@code ConnectionFactory} bean exists (which 002 already established) — no new
 * dependency — but does not itself expose a {@link TransactionalOperator} bean, so this one small
 * wrapper is all that's needed to compose it into a reactive chain.
 */
@Configuration
public class TransactionalOperatorConfig {

    @Bean
    public TransactionalOperator transactionalOperator(ReactiveTransactionManager transactionManager) {
        return TransactionalOperator.create(transactionManager);
    }
}
