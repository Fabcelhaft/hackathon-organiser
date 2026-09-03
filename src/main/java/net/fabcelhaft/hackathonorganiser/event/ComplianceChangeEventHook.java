package net.fabcelhaft.hackathonorganiser.event;

import java.util.Map;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceStatus;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupRepository;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.group.GroupStatus;
import net.fabcelhaft.hackathonorganiser.topic.TopicRepository;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Wraps a Compliance Ruleset change (the instance-wide Maximum/Minimum Group Members, or a Custom
 * Field diversity requirement being added/removed) so that every active Group whose evaluated
 * {@link ComplianceStatus} actually flips as a result publishes its own {@code
 * GROUP_COMPLIANCE_CHANGED} Event (spec.md FR-010b; research.md §5).
 *
 * <p>Called from {@code ComplianceController} — the sole entry point for all three ruleset-change
 * actions ({@code OrganiserSettingsController} never touches Maximum/Minimum Group Members) —
 * rather than injected into {@link ComplianceService}/{@code OrganiserSettingsService} themselves,
 * to avoid a circular dependency (this component itself depends on {@link ComplianceService}).
 */
@Component
public class ComplianceChangeEventHook {

    private final GroupRepository groupRepository;
    private final GroupService groupService;
    private final ComplianceService complianceService;
    private final TopicRepository topicRepository;
    private final EventPublisher eventPublisher;
    private final EventPayloadFactory eventPayloadFactory;

    public ComplianceChangeEventHook(
            GroupRepository groupRepository,
            GroupService groupService,
            ComplianceService complianceService,
            TopicRepository topicRepository,
            EventPublisher eventPublisher,
            EventPayloadFactory eventPayloadFactory) {
        this.groupRepository = groupRepository;
        this.groupService = groupService;
        this.complianceService = complianceService;
        this.topicRepository = topicRepository;
        this.eventPublisher = eventPublisher;
        this.eventPayloadFactory = eventPayloadFactory;
    }

    /**
     * Snapshots every active Group's Compliance status under the ruleset as it stands now,
     * performs {@code save}, then re-evaluates those same Groups under the new ruleset and
     * publishes {@code GROUP_COMPLIANCE_CHANGED} for each one whose status differs. Any failure in
     * this snapshot/diff/publish machinery is isolated so it can never fail the underlying ruleset
     * save itself (FR-020a-1).
     */
    public <T> Mono<T> wrapRulesetChange(Mono<T> save) {
        return Mono.defer(this::snapshotActiveGroupStatuses)
                .onErrorResume(ex -> Mono.just(Map.<UUID, ComplianceStatus>of()))
                .flatMap(before -> save.flatMap(result -> Mono.defer(() -> publishDiff(before))
                        .onErrorResume(ex -> Mono.empty())
                        .thenReturn(result)));
    }

    private Mono<Map<UUID, ComplianceStatus>> snapshotActiveGroupStatuses() {
        return activeGroups().flatMap(this::statusEntry).collectMap(Map.Entry::getKey, Map.Entry::getValue);
    }

    private Mono<Void> publishDiff(Map<UUID, ComplianceStatus> before) {
        return activeGroups()
                .filter(group -> before.containsKey(group.getId()))
                .flatMap(group -> statusEntry(group).flatMap(entry -> {
                    ComplianceStatus oldStatus = before.get(group.getId());
                    ComplianceStatus newStatus = entry.getValue();
                    if (newStatus == oldStatus) {
                        return Mono.empty();
                    }
                    return topicRepository
                            .findById(group.getTopicId())
                            .doOnNext(topic -> eventPublisher.publish(
                                    eventPayloadFactory.groupComplianceChanged(group, newStatus, topic)));
                }))
                .then();
    }

    private Flux<Group> activeGroups() {
        return groupRepository.findAll().filter(group -> group.getStatus() == GroupStatus.ACTIVE);
    }

    private Mono<Map.Entry<UUID, ComplianceStatus>> statusEntry(Group group) {
        return groupService
                .activeMemberParticipantIds(group.getId())
                .flatMap(memberIds -> complianceService.evaluate(group, memberIds))
                .map(status -> Map.entry(group.getId(), status));
    }
}
