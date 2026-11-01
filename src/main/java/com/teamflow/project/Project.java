package com.teamflow.project;

import com.teamflow.common.BaseEntity;
import com.teamflow.workspace.Workspace;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "projects")
public class Project extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "workspace_id", nullable = false)
    private Workspace workspace;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 2000)
    private String description;

    protected Project() {}

    public Project(Workspace workspace, String name, String description) {
        this.workspace = workspace;
        update(name, description);
    }

    public void update(String name, String description) {
        this.name = name.strip();
        this.description = description;
    }

    public Workspace getWorkspace() { return workspace; }
    public String getName() { return name; }
    public String getDescription() { return description; }
}
