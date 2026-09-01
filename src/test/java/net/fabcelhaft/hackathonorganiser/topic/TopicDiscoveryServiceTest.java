package net.fabcelhaft.hackathonorganiser.topic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceService;
import net.fabcelhaft.hackathonorganiser.compliance.ComplianceStatus;
import net.fabcelhaft.hackathonorganiser.group.Group;
import net.fabcelhaft.hackathonorganiser.group.GroupService;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettingsService;
import net.fabcelhaft.hackathonorganiser.organisersettings.SkillDisplayMode;
import net.fabcelhaft.hackathonorganiser.participant.Participant;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantService;
import net.fabcelhaft.hackathonorganiser.participant.ParticipantStatus;
import net.fabcelhaft.hackathonorganiser.skill.Skill;
import net.fabcelhaft.hackathonorganiser.skill.SkillRepository;
import net.fabcelhaft.hackathonorganiser.user.User;
import net.fabcelhaft.hackathonorganiser.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.r2dbc.core.RowsFetchSpec;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * Unit tests for {@link TopicDiscoveryService} (T026, T042, T061; FR-003-FR-006, FR-014, FR-017):
 * the Home Page's capped, fullness-sorted, viewer-Skill-intersected read model, and the Topic
 * Overview's uncapped, author+Compliance-augmented read model — both sharing the same Skill
 * Display Mode computation. Per Constitution Development Workflow #4, the multi-operator reactive
 * chains under test are verified with {@link StepVerifier}, never {@code .block()}.
 */
@ExtendWith(MockitoExtension.class)
class TopicDiscoveryServiceTest {

    @Mock
    private TopicRepository topicRepository;

    @Mock
    private GroupService groupService;

    @Mock
    private OrganiserSettingsService organiserSettingsService;

    @Mock
    private ComplianceService complianceService;

    @Mock
    private SkillRepository skillRepository;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ParticipantService participantService;

    @Mock
    private DatabaseClient databaseClient;

    private TopicDiscoveryService topicDiscoveryService;

    @BeforeEach
    void setUp() {
        topicDiscoveryService = new TopicDiscoveryService(
                topicRepository,
                groupService,
                organiserSettingsService,
                complianceService,
                skillRepository,
                userRepository,
                participantService,
                databaseClient);
    }

    // --- findOpenTopicsForHomePage: cap, fullness filter/order, viewer-Skill intersection --------

    @Test
    void findOpenTopicsForHomePageExcludesPendingTopics() {
        Topic pending = topicOf(TopicApprovalStatus.PENDING);
        when(topicRepository.findAll()).thenReturn(Flux.just(pending));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.STILL_NEEDED_ONLY)));

        StepVerifier.create(
                        topicDiscoveryService.findOpenTopicsForHomePage(UUID.randomUUID(), null, 10).collectList())
                .assertNext(rows -> assertThat(rows).isEmpty())
                .verifyComplete();
    }

    @Test
    void findOpenTopicsForHomePageExcludesFullTopicsAndTreatsNoGroupAsZero() {
        Topic noGroupTopic = topicOf(TopicApprovalStatus.APPROVED);
        Topic fullTopic = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findAll()).thenReturn(Flux.just(noGroupTopic, fullTopic));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(2, SkillDisplayMode.STILL_NEEDED_ONLY)));
        when(groupService.findActiveGroupForTopic(noGroupTopic.getId())).thenReturn(Mono.empty());
        Group fullGroup = groupOf(fullTopic.getId());
        when(groupService.findActiveGroupForTopic(fullTopic.getId())).thenReturn(Mono.just(fullGroup));
        when(groupService.activeMemberCount(fullGroup.getId())).thenReturn(Mono.just(2));
        stubEmptySkillsAndParticipantSkills();

        StepVerifier.create(
                        topicDiscoveryService.findOpenTopicsForHomePage(UUID.randomUUID(), null, 10).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).topic().getId()).isEqualTo(noGroupTopic.getId());
                    assertThat(rows.get(0).memberCount()).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    void findOpenTopicsForHomePageOrdersByMemberCountDescendingAndCapsAtLimit() {
        Topic t1 = topicOf(TopicApprovalStatus.APPROVED);
        Topic t2 = topicOf(TopicApprovalStatus.APPROVED);
        Topic t3 = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findAll()).thenReturn(Flux.just(t1, t2, t3));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.STILL_NEEDED_ONLY)));
        Group g1 = groupOf(t1.getId());
        Group g2 = groupOf(t2.getId());
        when(groupService.findActiveGroupForTopic(t1.getId())).thenReturn(Mono.just(g1));
        when(groupService.findActiveGroupForTopic(t2.getId())).thenReturn(Mono.just(g2));
        when(groupService.findActiveGroupForTopic(t3.getId())).thenReturn(Mono.empty());
        when(groupService.activeMemberCount(g1.getId())).thenReturn(Mono.just(1));
        when(groupService.activeMemberCount(g2.getId())).thenReturn(Mono.just(3));
        stubEmptySkillsAndParticipantSkills();

        StepVerifier.create(
                        topicDiscoveryService.findOpenTopicsForHomePage(UUID.randomUUID(), null, 2).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(2);
                    assertThat(rows.get(0).topic().getId()).isEqualTo(t2.getId());
                    assertThat(rows.get(0).memberCount()).isEqualTo(3);
                    assertThat(rows.get(1).topic().getId()).isEqualTo(t1.getId());
                })
                .verifyComplete();
    }

    @Test
    void findOpenTopicsForHomePageIntersectsNeededSkillsWithTheViewersOwnSkills() {
        UUID viewerParticipantId = UUID.randomUUID();
        Topic topic = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findAll()).thenReturn(Flux.just(topic));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.ALL_ASSOCIATED)));
        when(groupService.findActiveGroupForTopic(topic.getId())).thenReturn(Mono.empty());
        UUID sharedSkillId = UUID.randomUUID();
        UUID onlyNeededSkillId = UUID.randomUUID();
        Skill sharedSkill = skillOf(sharedSkillId, "Shared");
        stubTopicSkillIds(topic.getId(), List.of(sharedSkillId, onlyNeededSkillId));
        stubParticipantSkillIds(viewerParticipantId, List.of(sharedSkillId));
        when(skillRepository.findAllById(List.of(sharedSkillId))).thenReturn(Flux.just(sharedSkill));

        StepVerifier.create(topicDiscoveryService
                        .findOpenTopicsForHomePage(UUID.randomUUID(), viewerParticipantId, 10)
                        .collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).viewerOfferedSkills()).containsExactly(sharedSkill);
                })
                .verifyComplete();
    }

    @Test
    void findOpenTopicsForHomePageGivesAViewerWithNoParticipantRecordAnEmptySkillsListNeverAnError() {
        Topic topic = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findAll()).thenReturn(Flux.just(topic));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.ALL_ASSOCIATED)));
        when(groupService.findActiveGroupForTopic(topic.getId())).thenReturn(Mono.empty());
        stubTopicSkillIds(topic.getId(), List.of(UUID.randomUUID()));

        StepVerifier.create(
                        topicDiscoveryService.findOpenTopicsForHomePage(UUID.randomUUID(), null, 10).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).viewerOfferedSkills()).isEmpty();
                })
                .verifyComplete();
    }

    // --- Own-Topic pinning + joinable flag (FR-033, FR-035, research.md §11) ----------------------

    @Test
    void findOpenTopicsForHomePagePinsTheViewersOwnTopicsAboveTheRestAndNeverTruncatesThemAway() {
        UUID viewerUserId = UUID.randomUUID();
        Topic ownPending = topicOf(TopicApprovalStatus.PENDING);
        ownPending.setCreatedByUserId(viewerUserId);
        Topic ownFull = topicOf(TopicApprovalStatus.APPROVED);
        ownFull.setCreatedByUserId(viewerUserId);
        Topic other = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findAll()).thenReturn(Flux.just(ownPending, ownFull, other));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(1, SkillDisplayMode.STILL_NEEDED_ONLY)));
        when(groupService.findActiveGroupForTopic(ownPending.getId())).thenReturn(Mono.empty());
        Group ownFullGroup = groupOf(ownFull.getId());
        when(groupService.findActiveGroupForTopic(ownFull.getId())).thenReturn(Mono.just(ownFullGroup));
        when(groupService.activeMemberCount(ownFullGroup.getId())).thenReturn(Mono.just(1));
        when(groupService.findActiveGroupForTopic(other.getId())).thenReturn(Mono.empty());
        stubEmptySkillsAndParticipantSkills();

        StepVerifier.create(
                        topicDiscoveryService.findOpenTopicsForHomePage(viewerUserId, null, 10).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(3);
                    // Own Topics (Pending, full) come first, pinned, even though neither would
                    // otherwise appear in the fullness-sorted list at all.
                    assertThat(rows.get(0).pinned()).isTrue();
                    assertThat(rows.get(1).pinned()).isTrue();
                    assertThat(rows.stream().map(r -> r.topic().getId()))
                            .containsExactlyInAnyOrder(ownPending.getId(), ownFull.getId(), other.getId());
                    assertThat(rows.get(2).pinned()).isFalse();
                    assertThat(rows.get(2).topic().getId()).isEqualTo(other.getId());
                })
                .verifyComplete();
    }

    @Test
    void findOpenTopicsForHomePageMarksAPendingOrFullPinnedTopicAsNotJoinableButAnOpenOneAsJoinable() {
        UUID viewerUserId = UUID.randomUUID();
        Topic ownPending = topicOf(TopicApprovalStatus.PENDING);
        ownPending.setCreatedByUserId(viewerUserId);
        Topic ownOpen = topicOf(TopicApprovalStatus.APPROVED);
        ownOpen.setCreatedByUserId(viewerUserId);
        when(topicRepository.findAll()).thenReturn(Flux.just(ownPending, ownOpen));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.STILL_NEEDED_ONLY)));
        when(groupService.findActiveGroupForTopic(ownPending.getId())).thenReturn(Mono.empty());
        when(groupService.findActiveGroupForTopic(ownOpen.getId())).thenReturn(Mono.empty());
        stubEmptySkillsAndParticipantSkills();

        StepVerifier.create(
                        topicDiscoveryService.findOpenTopicsForHomePage(viewerUserId, null, 10).collectList())
                .assertNext(rows -> {
                    var pendingRow = rows.stream()
                            .filter(r -> r.topic().getId().equals(ownPending.getId()))
                            .findFirst()
                            .orElseThrow();
                    var openRow = rows.stream()
                            .filter(r -> r.topic().getId().equals(ownOpen.getId()))
                            .findFirst()
                            .orElseThrow();
                    assertThat(pendingRow.joinable()).isFalse();
                    assertThat(openRow.joinable()).isTrue();
                })
                .verifyComplete();
    }

    @Test
    void findOpenTopicsForHomePageMarksAnOwnFullTopicWithAComplianceOverrideAsJoinable() {
        UUID viewerUserId = UUID.randomUUID();
        Topic ownFullOverridden = topicOf(TopicApprovalStatus.APPROVED);
        ownFullOverridden.setCreatedByUserId(viewerUserId);
        when(topicRepository.findAll()).thenReturn(Flux.just(ownFullOverridden));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(1, SkillDisplayMode.STILL_NEEDED_ONLY)));
        Group overriddenGroup = groupOf(ownFullOverridden.getId());
        overriddenGroup.setComplianceOverride(true);
        when(groupService.findActiveGroupForTopic(ownFullOverridden.getId())).thenReturn(Mono.just(overriddenGroup));
        when(groupService.activeMemberCount(overriddenGroup.getId())).thenReturn(Mono.just(1));
        stubEmptySkillsAndParticipantSkills();

        StepVerifier.create(
                        topicDiscoveryService.findOpenTopicsForHomePage(viewerUserId, null, 10).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).joinable()).isTrue();
                })
                .verifyComplete();
    }

    // --- findTopicOverview: every visible Topic, author + Compliance columns (FR-005, FR-006, FR-014) --

    @Test
    void findTopicOverviewIncludesAPendingTopicOnlyForItsAuthorOrAnOrganiser() {
        UUID authorId = UUID.randomUUID();
        UUID otherId = UUID.randomUUID();
        Topic ownPending = topicOf(TopicApprovalStatus.PENDING);
        ownPending.setCreatedByUserId(authorId);
        when(topicRepository.findAll()).thenReturn(Flux.just(ownPending));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.ALL_ASSOCIATED)));
        stubAuthorDisplayName(authorId, "Author Name");
        when(groupService.findActiveGroupForTopic(ownPending.getId())).thenReturn(Mono.empty());
        stubTopicSkillIds(ownPending.getId(), List.of());

        StepVerifier.create(topicDiscoveryService.findTopicOverview(authorId, false).collectList())
                .assertNext(rows -> assertThat(rows).hasSize(1))
                .verifyComplete();

        StepVerifier.create(topicDiscoveryService.findTopicOverview(otherId, false).collectList())
                .assertNext(rows -> assertThat(rows).isEmpty())
                .verifyComplete();
    }

    @Test
    void findTopicOverviewReportsNoGroupYetAsAnEmptyComplianceStatus() {
        Topic topic = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findAll()).thenReturn(Flux.just(topic));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.ALL_ASSOCIATED)));
        stubAuthorDisplayName(topic.getCreatedByUserId(), "Author");
        when(groupService.findActiveGroupForTopic(topic.getId())).thenReturn(Mono.empty());
        stubTopicSkillIds(topic.getId(), List.of());

        StepVerifier.create(topicDiscoveryService.findTopicOverview(UUID.randomUUID(), true).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).complianceStatus()).isEmpty();
                    assertThat(rows.get(0).memberCount()).isEqualTo(0);
                })
                .verifyComplete();
    }

    @Test
    void findTopicOverviewUsesComplianceServiceForATopicWithAGroup() {
        Topic topic = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findAll()).thenReturn(Flux.just(topic));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.ALL_ASSOCIATED)));
        stubAuthorDisplayName(topic.getCreatedByUserId(), "Author");
        Group group = groupOf(topic.getId());
        when(groupService.findActiveGroupForTopic(topic.getId())).thenReturn(Mono.just(group));
        when(groupService.activeMemberCount(group.getId())).thenReturn(Mono.just(2));
        UUID p1 = UUID.randomUUID();
        when(groupService.activeMemberParticipantIds(group.getId())).thenReturn(Mono.just(List.of(p1)));
        when(complianceService.evaluate(group, List.of(p1))).thenReturn(Mono.just(ComplianceStatus.COMPLIANT));
        stubTopicSkillIds(topic.getId(), List.of());

        StepVerifier.create(topicDiscoveryService.findTopicOverview(UUID.randomUUID(), true).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).complianceStatus()).contains(ComplianceStatus.COMPLIANT);
                })
                .verifyComplete();
    }

    @Test
    void findTopicOverviewPinsTheViewersOwnTopicsAboveAllOtherRowsWithNoTruncation() {
        UUID viewerUserId = UUID.randomUUID();
        Topic own = topicOf(TopicApprovalStatus.APPROVED);
        own.setCreatedByUserId(viewerUserId);
        Topic other = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findAll()).thenReturn(Flux.just(other, own));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.ALL_ASSOCIATED)));
        stubAuthorDisplayName(own.getCreatedByUserId(), "Own Author");
        stubAuthorDisplayName(other.getCreatedByUserId(), "Other Author");
        when(groupService.findActiveGroupForTopic(own.getId())).thenReturn(Mono.empty());
        when(groupService.findActiveGroupForTopic(other.getId())).thenReturn(Mono.empty());
        stubTopicSkillIds(own.getId(), List.of());
        stubTopicSkillIds(other.getId(), List.of());

        StepVerifier.create(topicDiscoveryService.findTopicOverview(viewerUserId, false).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(2);
                    assertThat(rows.get(0).topic().getId()).isEqualTo(own.getId());
                    assertThat(rows.get(0).pinned()).isTrue();
                    assertThat(rows.get(1).topic().getId()).isEqualTo(other.getId());
                    assertThat(rows.get(1).pinned()).isFalse();
                })
                .verifyComplete();
    }

    // --- Skill Display Mode: still-needed vs. all-associated (FR-017, FR-018, Edge Cases) --------

    @Test
    void stillNeededOnlyModeExcludesSkillsAlreadyCoveredByACurrentGroupMember() {
        Topic topic = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findAll()).thenReturn(Flux.just(topic));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.STILL_NEEDED_ONLY)));
        stubAuthorDisplayName(topic.getCreatedByUserId(), "Author");
        Group group = groupOf(topic.getId());
        when(groupService.findActiveGroupForTopic(topic.getId())).thenReturn(Mono.just(group));
        when(groupService.activeMemberCount(group.getId())).thenReturn(Mono.just(1));
        UUID memberId = UUID.randomUUID();
        when(groupService.activeMemberParticipantIds(group.getId())).thenReturn(Mono.just(List.of(memberId)));
        when(complianceService.evaluate(group, List.of(memberId))).thenReturn(Mono.just(ComplianceStatus.COMPLIANT));

        UUID coveredSkillId = UUID.randomUUID();
        UUID stillNeededSkillId = UUID.randomUUID();
        Skill stillNeededSkill = skillOf(stillNeededSkillId, "Still Needed");
        stubTopicSkillIds(topic.getId(), List.of(coveredSkillId, stillNeededSkillId));
        stubParticipantSkillIds(memberId, List.of(coveredSkillId));
        when(skillRepository.findAllById(List.of(stillNeededSkillId))).thenReturn(Flux.just(stillNeededSkill));

        StepVerifier.create(topicDiscoveryService.findTopicOverview(UUID.randomUUID(), true).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).neededSkills()).containsExactly(stillNeededSkill);
                })
                .verifyComplete();
    }

    @Test
    void allAssociatedModeShowsEveryNeededSkillRegardlessOfCoverage() {
        Topic topic = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findAll()).thenReturn(Flux.just(topic));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.ALL_ASSOCIATED)));
        stubAuthorDisplayName(topic.getCreatedByUserId(), "Author");
        Group group = groupOf(topic.getId());
        when(groupService.findActiveGroupForTopic(topic.getId())).thenReturn(Mono.just(group));
        when(groupService.activeMemberCount(group.getId())).thenReturn(Mono.just(1));
        UUID memberId = UUID.randomUUID();
        when(groupService.activeMemberParticipantIds(group.getId())).thenReturn(Mono.just(List.of(memberId)));
        when(complianceService.evaluate(group, List.of(memberId))).thenReturn(Mono.just(ComplianceStatus.COMPLIANT));

        UUID coveredSkillId = UUID.randomUUID();
        Skill coveredSkill = skillOf(coveredSkillId, "Covered");
        stubTopicSkillIds(topic.getId(), List.of(coveredSkillId));

        StepVerifier.create(topicDiscoveryService.findTopicOverview(UUID.randomUUID(), true).collectList())
                .assertNext(rows -> {
                    assertThat(rows).hasSize(1);
                    assertThat(rows.get(0).neededSkills()).extracting(Skill::getId).containsExactly(coveredSkillId);
                })
                .verifyComplete();
    }

    // --- findTopicDetail: Topic Details read model (FR-030, FR-031, FR-032, FR-014a) ---------------

    @Test
    void findTopicDetailIsEmptyForAnUnknownTopicId() {
        when(topicRepository.findById(any(UUID.class))).thenReturn(Mono.empty());

        StepVerifier.create(topicDiscoveryService.findTopicDetail(UUID.randomUUID(), UUID.randomUUID(), false))
                .verifyComplete();
    }

    @Test
    void findTopicDetailIsEmptyForAPendingTopicTheCallerMayNotSee() {
        Topic pending = topicOf(TopicApprovalStatus.PENDING);
        when(topicRepository.findById(pending.getId())).thenReturn(Mono.just(pending));

        StepVerifier.create(topicDiscoveryService.findTopicDetail(pending.getId(), UUID.randomUUID(), false))
                .verifyComplete();
    }

    @Test
    void findTopicDetailReturnsCoreFieldsAndABlankComplianceStatusWhenThereIsNoGroupYet() {
        Topic topic = topicOf(TopicApprovalStatus.APPROVED);
        when(topicRepository.findById(topic.getId())).thenReturn(Mono.just(topic));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.ALL_ASSOCIATED)));
        when(groupService.findActiveGroupForTopic(topic.getId())).thenReturn(Mono.empty());
        stubTopicSkillIds(topic.getId(), List.of());

        StepVerifier.create(topicDiscoveryService.findTopicDetail(topic.getId(), topic.getCreatedByUserId(), false))
                .assertNext(detail -> {
                    assertThat(detail.topic().getId()).isEqualTo(topic.getId());
                    assertThat(detail.memberCount()).isEqualTo(0);
                    assertThat(detail.complianceStatus()).isEmpty();
                    assertThat(detail.members()).isEmpty();
                    assertThat(detail.author()).isTrue();
                    assertThat(detail.isMember()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void findTopicDetailReturnsOneParticipantViewerDetailPerJoinedMemberViaTheExistingParticipantService() {
        Topic topic = topicOf(TopicApprovalStatus.APPROVED);
        UUID viewerUserId = UUID.randomUUID();
        when(topicRepository.findById(topic.getId())).thenReturn(Mono.just(topic));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.ALL_ASSOCIATED)));
        Group group = groupOf(topic.getId());
        when(groupService.findActiveGroupForTopic(topic.getId())).thenReturn(Mono.just(group));
        when(groupService.activeMemberCount(group.getId())).thenReturn(Mono.just(1));
        UUID memberParticipantId = UUID.randomUUID();
        when(groupService.activeMemberParticipantIds(group.getId())).thenReturn(Mono.just(List.of(memberParticipantId)));
        when(complianceService.evaluate(group, List.of(memberParticipantId)))
                .thenReturn(Mono.just(ComplianceStatus.COMPLIANT));
        stubTopicSkillIds(topic.getId(), List.of());
        var viewerDetail = viewerDetailOf(memberParticipantId, "Member Name");
        when(participantService.findDetailForViewer(memberParticipantId, viewerUserId, false))
                .thenReturn(Mono.just(viewerDetail));
        // The viewer themselves has no Participant record here, so isMember must be false
        // (Story 11, FR-037) — a separate test below covers the true case.
        when(participantService.findByUserId(viewerUserId)).thenReturn(Mono.empty());

        StepVerifier.create(topicDiscoveryService.findTopicDetail(topic.getId(), viewerUserId, false))
                .assertNext(detail -> {
                    assertThat(detail.complianceStatus()).contains(ComplianceStatus.COMPLIANT);
                    assertThat(detail.members()).containsExactly(viewerDetail);
                    assertThat(detail.author()).isFalse();
                    assertThat(detail.isMember()).isFalse();
                })
                .verifyComplete();
    }

    @Test
    void findTopicDetailReturnsIsMemberTrueWhenTheViewersOwnParticipantIsAmongTheActiveMembers() {
        Topic topic = topicOf(TopicApprovalStatus.APPROVED);
        UUID viewerUserId = UUID.randomUUID();
        when(topicRepository.findById(topic.getId())).thenReturn(Mono.just(topic));
        when(organiserSettingsService.current()).thenReturn(Mono.just(settingsOf(5, SkillDisplayMode.ALL_ASSOCIATED)));
        Group group = groupOf(topic.getId());
        when(groupService.findActiveGroupForTopic(topic.getId())).thenReturn(Mono.just(group));
        when(groupService.activeMemberCount(group.getId())).thenReturn(Mono.just(1));
        Participant viewerParticipant = new Participant();
        viewerParticipant.setId(UUID.randomUUID());
        viewerParticipant.setUserId(viewerUserId);
        viewerParticipant.setStatus(ParticipantStatus.ACTIVE);
        when(groupService.activeMemberParticipantIds(group.getId()))
                .thenReturn(Mono.just(List.of(viewerParticipant.getId())));
        when(complianceService.evaluate(group, List.of(viewerParticipant.getId())))
                .thenReturn(Mono.just(ComplianceStatus.COMPLIANT));
        stubTopicSkillIds(topic.getId(), List.of());
        var viewerDetail = viewerDetailOf(viewerParticipant.getId(), "Viewer's Own Name");
        when(participantService.findDetailForViewer(viewerParticipant.getId(), viewerUserId, false))
                .thenReturn(Mono.just(viewerDetail));
        when(participantService.findByUserId(viewerUserId)).thenReturn(Mono.just(viewerParticipant));

        StepVerifier.create(topicDiscoveryService.findTopicDetail(topic.getId(), viewerUserId, false))
                .assertNext(detail -> assertThat(detail.isMember()).isTrue())
                .verifyComplete();
    }

    private ParticipantService.ParticipantViewerDetail viewerDetailOf(UUID participantId, String displayName) {
        Participant participant = new Participant();
        participant.setId(participantId);
        participant.setUserId(UUID.randomUUID());
        participant.setStatus(ParticipantStatus.ACTIVE);
        Instant now = Instant.now();
        participant.setCreatedAt(now);
        participant.setUpdatedAt(now);
        return new ParticipantService.ParticipantViewerDetail(
                participant, displayName, "member@example.com", List.of(), List.of(), false, false, false, false);
    }

    // --- test helpers ------------------------------------------------------------------------------

    private Topic topicOf(TopicApprovalStatus status) {
        Topic topic = new Topic();
        topic.setId(UUID.randomUUID());
        topic.setName("Topic " + UUID.randomUUID());
        topic.setDescription("Description");
        topic.setCreatedByUserId(UUID.randomUUID());
        topic.setApprovalStatus(status);
        Instant now = Instant.now();
        topic.setCreatedAt(now);
        topic.setUpdatedAt(now);
        return topic;
    }

    private Group groupOf(UUID topicId) {
        Group group = new Group();
        group.setId(UUID.randomUUID());
        group.setTopicId(topicId);
        return group;
    }

    private Skill skillOf(UUID id, String name) {
        Skill skill = new Skill();
        skill.setId(id);
        skill.setName(name);
        Instant now = Instant.now();
        skill.setCreatedAt(now);
        skill.setUpdatedAt(now);
        return skill;
    }

    private OrganiserSettings settingsOf(int maxGroupMembers, SkillDisplayMode skillDisplayMode) {
        OrganiserSettings settings = new OrganiserSettings();
        settings.setId(UUID.randomUUID());
        settings.setMaxGroupMembers(maxGroupMembers);
        settings.setSkillDisplayMode(skillDisplayMode);
        settings.setUpdatedAt(Instant.now());
        return settings;
    }

    private void stubAuthorDisplayName(UUID userId, String displayName) {
        User user = new User();
        user.setId(userId);
        user.setDisplayName(displayName);
        user.setOidcSubject("sub-" + userId);
        lenient().when(userRepository.findById(userId)).thenReturn(Mono.just(user));
    }

    /**
     * Each distinct SQL query ({@code topic_skills} vs. {@code participant_skills}) needs its own
     * {@link DatabaseClient.GenericExecuteSpec} mock instance — reusing a single shared spec across
     * both would make the last-registered {@code mapValue(...)} stub win for every call regardless
     * of which query text triggered it, since the stub lives on the returned spec object, not on
     * the {@code sql(...)} call site.
     */
    @SuppressWarnings("unchecked")
    private void stubUuidRows(String sqlContains, List<UUID> ids) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        RowsFetchSpec<UUID> fetch = mock(RowsFetchSpec.class);
        lenient().when(databaseClient.sql(org.mockito.ArgumentMatchers.contains(sqlContains))).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.mapValue(UUID.class)).thenReturn(fetch);
        lenient().when(fetch.all()).thenReturn(Flux.fromIterable(ids));
    }

    private void stubTopicSkillIds(UUID topicId, List<UUID> skillIds) {
        stubUuidRows("topic_skills", skillIds);
        if (!skillIds.isEmpty()) {
            lenient()
                    .when(skillRepository.findAllById(eq(skillIds)))
                    .thenAnswer(invocation -> Flux.fromIterable(skillIds).map(id -> {
                        Skill skill = new Skill();
                        skill.setId(id);
                        skill.setName("Skill " + id);
                        return skill;
                    }));
        }
    }

    private void stubParticipantSkillIds(UUID participantId, List<UUID> skillIds) {
        stubUuidRows("participant_skills", skillIds);
    }

    private void stubEmptySkillsAndParticipantSkills() {
        stubUuidRows("topic_skills", List.of());
        stubUuidRows("participant_skills", List.of());
    }
}
