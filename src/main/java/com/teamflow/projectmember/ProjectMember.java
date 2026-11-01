package com.teamflow.projectmember;

import com.teamflow.common.BaseEntity;
import com.teamflow.project.Project;
import com.teamflow.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "project_members")
public class ProjectMember extends BaseEntity {
    @Column(nullable = false, updatable = false)
    private UUID workspaceId;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "project_id", nullable = false, updatable = false)
    private Project project;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, updatable = false)
    private User user;

    protected ProjectMember() {}
    public ProjectMember(Project project, User user) {
        this.workspaceId = project.getWorkspace().getId();
        this.project = project;
        this.user = user;
    }
    public User getUser() { return user; }
}
