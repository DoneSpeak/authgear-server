package learning.userprofile.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Entity mirroring Authgear _auth_user for profile attributes.
 * standard_attributes: JSON object, keys = attribute names (e.g. email, given_name).
 * custom_attributes: JSON object in storage form, keys = config ids (not pointers).
 */
@Entity
@Table(name = "auth_user")
public class AuthUser {

    @Id
    private String id;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    /**
     * Standard attributes (OIDC-like). Keys = attribute names. No DB constraint; validated in app.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "standard_attributes")
    private Map<String, Object> standardAttributes = new HashMap<>();

    /**
     * Custom attributes in storage form. Keys = config attribute id. No DB constraint; validated in app.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "custom_attributes")
    private Map<String, Object> customAttributes = new HashMap<>();

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
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

    public Map<String, Object> getStandardAttributes() {
        return standardAttributes;
    }

    public void setStandardAttributes(Map<String, Object> standardAttributes) {
        this.standardAttributes = standardAttributes != null ? standardAttributes : new HashMap<>();
    }

    public Map<String, Object> getCustomAttributes() {
        return customAttributes;
    }

    public void setCustomAttributes(Map<String, Object> customAttributes) {
        this.customAttributes = customAttributes != null ? customAttributes : new HashMap<>();
    }
}
