package net.fabcelhaft.hackathonorganiser.skill;

import java.time.Instant;
import java.util.UUID;
import org.springframework.r2dbc.BadSqlGrammarException;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Organiser-facing operations on {@link Skill} records (T028): catalog CRUD with case-insensitive
 * duplicate-name rejection (FR-008a) and a referential delete-guard (FR-023).
 */
@Service
public class SkillService {

    private final SkillRepository skillRepository;
    private final DatabaseClient databaseClient;

    public SkillService(SkillRepository skillRepository, DatabaseClient databaseClient) {
        this.skillRepository = skillRepository;
        this.databaseClient = databaseClient;
    }

    public Flux<Skill> findAll() {
        return skillRepository.findAll();
    }

    public Mono<Skill> findById(UUID id) {
        return skillRepository.findById(id);
    }

    /**
     * Creates a new Skill, rejecting a name that duplicates an existing Skill case-insensitively
     * (FR-008a) with a friendly {@link SkillConflictException} rather than letting the database's
     * {@code unique index on lower(name)} surface a raw constraint-violation exception.
     */
    public Mono<Skill> create(String name) {
        return skillRepository.existsByNameIgnoreCase(name)
                .flatMap(exists -> {
                    if (exists) {
                        return Mono.error(new SkillConflictException(
                                "A skill named '" + name + "' already exists"));
                    }
                    return skillRepository.save(newSkill(name));
                });
    }

    /**
     * Renames an existing Skill, applying the same case-insensitive duplicate check as
     * {@link #create(String)} (excluding the Skill being renamed itself). Completes empty if no
     * Skill exists with the given id.
     */
    public Mono<Skill> rename(UUID id, String newName) {
        return skillRepository.findById(id)
                .flatMap(skill -> skillRepository.existsByNameIgnoreCaseAndIdNot(newName, id)
                        .flatMap(exists -> {
                            if (exists) {
                                return Mono.error(new SkillConflictException(
                                        "A skill named '" + newName + "' already exists"));
                            }
                            skill.setName(newName);
                            skill.setUpdatedAt(Instant.now());
                            return skillRepository.save(skill);
                        }));
    }

    /**
     * Deletes a Skill, blocked by {@link SkillConflictException} while any Participant or Topic
     * association still references it (FR-023).
     */
    public Mono<Void> delete(UUID id) {
        return referenceCount(id)
                .flatMap(count -> {
                    if (count > 0) {
                        return Mono.error(new SkillConflictException(
                                "Cannot delete this skill: still referenced by " + count
                                        + " Participant/Topic association(s)"));
                    }
                    return skillRepository.deleteById(id);
                });
    }

    /**
     * FR-023 referential guard, queried directly against {@code participant_skills} and
     * {@code topic_skills} — the real table names User Story 3 (Participant ↔ Skill) and User
     * Story 4 (Topic ↔ Skill) will add later in this feature. Until those tables exist, Postgres
     * reports "relation does not exist" ({@link BadSqlGrammarException}); that is treated
     * defensively as "not yet referenced" so this story's delete flow works correctly today. Once
     * those tables exist, the very same query starts returning real counts and this guard
     * activates with no further code change required — writing the guard against the eventual
     * schema now means it "just works" the moment the referencing tables land.
     */
    private Mono<Long> referenceCount(UUID id) {
        return countReferencing("participant_skills", "skill_id", id)
                .concatWith(countReferencing("topic_skills", "skill_id", id))
                .reduce(0L, Long::sum);
    }

    private Mono<Long> countReferencing(String table, String column, UUID id) {
        return databaseClient.sql("SELECT count(*) FROM " + table + " WHERE " + column + " = :id")
                .bind("id", id)
                .mapValue(Long.class)
                .one()
                .onErrorReturn(BadSqlGrammarException.class, Long.valueOf(0L));
    }

    private Skill newSkill(String name) {
        Skill skill = new Skill();
        skill.setName(name);
        Instant now = Instant.now();
        skill.setCreatedAt(now);
        skill.setUpdatedAt(now);
        return skill;
    }
}
