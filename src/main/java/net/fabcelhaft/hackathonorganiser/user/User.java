package net.fabcelhaft.hackathonorganiser.user;

import java.time.Instant;
import java.util.UUID;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A person recognised by the system after authenticating via the external OIDC identity provider
 * (spec.md Key Entities: User; data-model.md "User").
 *
 * <p>{@code id} is left {@code null} on construction: PostgreSQL assigns it via the {@code users.id}
 * column's {@code DEFAULT uuidv7()} (research.md §1) — no application-side ID generation exists
 * anywhere in this codebase.
 */
@Table("users")
public class User {

    @Id
    private UUID id;

    private String oidcSubject;

    private String displayName;

    private String email;

    private boolean organiser;

    private Instant createdAt;

    private Instant updatedAt;

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getOidcSubject() {
        return oidcSubject;
    }

    public void setOidcSubject(String oidcSubject) {
        this.oidcSubject = oidcSubject;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public boolean isOrganiser() {
        return organiser;
    }

    public void setOrganiser(boolean organiser) {
        this.organiser = organiser;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }
}
