package com.teamflow.history;

import com.teamflow.common.BaseEntity;
import com.teamflow.task.Task;
import com.teamflow.task.TaskStatus;
import com.teamflow.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "task_status_history")
public class TaskStatusChange extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false)
    private Task task;
    @Column(nullable = false)
    private long taskVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private StatusEventType eventType;
    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    private TaskStatus fromStatus;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TaskStatus toStatus;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "actor_id")
    private User actor;
    @Column(nullable = false, length = 120)
    private String actorName;

    protected TaskStatusChange() {}
    public TaskStatusChange(Task task, TaskStatus previous, User actor, StatusEventType eventType) {
        this.task = task;
        this.taskVersion = task.getVersion();
        this.eventType = eventType;
        this.fromStatus = previous;
        this.toStatus = task.getStatus();
        this.actor = actor;
        this.actorName = actor.getDisplayName();
    }
    public long getTaskVersion() { return taskVersion; }
    public StatusEventType getEventType() { return eventType; }
    public TaskStatus getFromStatus() { return fromStatus; }
    public TaskStatus getToStatus() { return toStatus; }
    public User getActor() { return actor; }
    public String getActorName() { return actorName; }
}
