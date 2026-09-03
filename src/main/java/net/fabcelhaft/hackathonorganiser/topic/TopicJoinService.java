package net.fabcelhaft.hackathonorganiser.topic;

import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.audit.AuditActor;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * The eligibility gate in front of the race-safe {@link GroupService#join} core (data-model.md
 * "TopicJoinService"; contracts/join-action.md): re-reads {@code
 * OrganiserSettings.topicJoiningEnabled} fresh on every call (FR-020c, never cached), the
 * requester's Participant status (FR-007b), and the Topic's approval status, in that order — each
 * rejection a distinct {@link TopicJoinConflictException} message (FR-026) — before delegating.
 */
@Service
public class TopicJoinService {

    private final OrganiserSettingsService organiserSettingsService;
    private final ParticipantService participantService;
    private final TopicService topicService;
    private final GroupService groupService;

    public TopicJoinService(
            OrganiserSettingsService organiserSettingsService,
            ParticipantService participantService,
            TopicService topicService,
            GroupService groupService) {
        this.organiserSettingsService = organiserSettingsService;
        this.participantService = participantService;
        this.topicService = topicService;
        this.groupService = groupService;
    }

    /**
     * Joins the requester to the Topic's Group, creating one on first join (FR-008) or growing it
     * (FR-009). Rejects with {@link TopicJoinConflictException} when Topic joining is disabled
     * (FR-020b), the requester has no Participant record or a non-{@code ACTIVE} status (FR-007b).
     * Completes empty (404) for an unknown or non-{@code APPROVED} Topic (spec Assumptions: a
     * Pending Topic has no Group and cannot be joined).
     */
    public Mono<Group> join(UUID topicId, UUID requesterUserId, AuditActor actor) {
        return organiserSettingsService.current().flatMap(settings -> {
            if (!settings.isTopicJoiningEnabled()) {
                return Mono.error(new TopicJoinConflictException("Topic joining is currently disabled"));
            }
            return participantService
                    .findByUserId(requesterUserId)
                    .switchIfEmpty(Mono.error(new TopicJoinConflictException(
                            "You must be an Active Participant to join a Topic")))
                    .flatMap(this::requireActive)
                    .flatMap(participant -> topicService
                            .findById(topicId)
                            .filter(topic -> topic.getApprovalStatus() == TopicApprovalStatus.APPROVED)
                            .flatMap(topic -> groupService.join(topicId, participant.getId(), actor)));
        });
    }

    private Mono<Participant> requireActive(Participant participant) {
        if (participant.getStatus() != ParticipantStatus.ACTIVE) {
            return Mono.error(
                    new TopicJoinConflictException("You must be an Active Participant to join a Topic"));
        }
        return Mono.just(participant);
    }

    /**
     * The eligibility gate in front of the equally race-safe {@link GroupService#leave} core (Story
     * 11, FR-037b, research.md §14): the requester must have a Participant record whose currently
     * active Group's Topic matches {@code topicId}. Unlike {@link #join}, this does <b>not</b>
     * re-check {@code OrganiserSettings.topicJoiningEnabled} or the Participant's status (FR-037e) —
     * that setting and status gate only new joins; a non-{@code ACTIVE} Participant already has no
     * active Group to leave, since self-revocation already removes it as a side effect.
     */
    public Mono<Group> leave(UUID topicId, UUID requesterUserId, AuditActor actor) {
        return participantService
                .findByUserId(requesterUserId)
                .switchIfEmpty(Mono.error(notCurrentlyAMember()))
                .flatMap(participant -> groupService
                        .findActiveGroupForParticipant(participant.getId())
                        .switchIfEmpty(Mono.error(notCurrentlyAMember()))
                        .flatMap(activeGroup -> {
                            if (!activeGroup.getTopicId().equals(topicId)) {
                                return Mono.<Group>error(notCurrentlyAMember());
                            }
                            return groupService.leave(topicId, participant.getId(), actor);
                        }));
    }

    private static TopicJoinConflictException notCurrentlyAMember() {
        return new TopicJoinConflictException("You are not currently a member of this Topic");
    }
}
