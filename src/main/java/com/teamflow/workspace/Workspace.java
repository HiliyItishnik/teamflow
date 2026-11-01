package com.teamflow.workspace;

import com.teamflow.common.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "workspaces")
public class Workspace extends BaseEntity {
    @Column(nullable = false, length = 120)
    private String name;

    @Column(length = 2000)
    private String description;

    protected Workspace() {}

    public Workspace(String name, String description) {
        update(name, description);
    }

    public void update(String name, String description) {
        this.name = name.strip();
        this.description = description;
    }

    public String getName() { return name; }
    public String getDescription() { return description; }
}
