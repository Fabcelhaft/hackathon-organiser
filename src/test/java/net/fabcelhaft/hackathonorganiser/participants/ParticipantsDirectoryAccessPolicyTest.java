package net.fabcelhaft.hackathonorganiser.participants;

import static org.assertj.core.api.Assertions.assertThat;

import net.fabcelhaft.hackathonorganiser.organisersettings.DirectoryAudience;
import net.fabcelhaft.hackathonorganiser.organisersettings.OrganiserSettings;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ParticipantsDirectoryAccessPolicy} (T042; research.md §6): all three
 * {@link DirectoryAudience} tiers combined with organiser / has-Participant-record /
 * plain-authenticated-user. A plain synchronous method with no reactive chain.
 */
class ParticipantsDirectoryAccessPolicyTest {

    private final ParticipantsDirectoryAccessPolicy policy = new ParticipantsDirectoryAccessPolicy();

    @Test
    void organiserIsAlwaysInAudienceRegardlessOfTheConfiguredTier() {
        for (DirectoryAudience audience : DirectoryAudience.values()) {
            assertThat(policy.isInAudience(settingsOf(audience), true, false)).isTrue();
            assertThat(policy.isInAudience(settingsOf(audience), true, true)).isTrue();
        }
    }

    @Test
    void organisersOnlyExcludesEveryNonOrganiser() {
        OrganiserSettings settings = settingsOf(DirectoryAudience.ORGANISERS_ONLY);
        assertThat(policy.isInAudience(settings, false, true)).isFalse();
        assertThat(policy.isInAudience(settings, false, false)).isFalse();
    }

    @Test
    void organisersAndParticipantsIncludesOnlyUsersWithAParticipantRecord() {
        OrganiserSettings settings = settingsOf(DirectoryAudience.ORGANISERS_AND_PARTICIPANTS);
        assertThat(policy.isInAudience(settings, false, true)).isTrue();
        assertThat(policy.isInAudience(settings, false, false)).isFalse();
    }

    @Test
    void allAuthenticatedIncludesEveryAuthenticatedUser() {
        OrganiserSettings settings = settingsOf(DirectoryAudience.ALL_AUTHENTICATED);
        assertThat(policy.isInAudience(settings, false, true)).isTrue();
        assertThat(policy.isInAudience(settings, false, false)).isTrue();
    }

    private OrganiserSettings settingsOf(DirectoryAudience audience) {
        OrganiserSettings settings = new OrganiserSettings();
        settings.setParticipantsDirectoryAudience(audience);
        return settings;
    }
}
