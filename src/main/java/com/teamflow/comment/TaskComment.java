package com.teamflow.comment;

import com.teamflow.common.BaseEntity;
import com.teamflow.task.Task;
import com.teamflow.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "task_comments")
public class TaskComment extends BaseEntity {
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "task_id", nullable = false, updatable = false)
    private Task task;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", updatable = false)
    private User author;
    @Column(nullable = false, length = 120, updatable = false)
    private String authorName;
    @Column(nullable = false, length = 5000)
    private String body;

    protected TaskComment() {}
    public TaskComment(Task task, User author, String body) {
        this.task = task;
        this.author = author;
        this.authorName = author.getDisplayName();
        edit(body);
    }
    public User getAuthor() { return author; }
    public String getAuthorName() { return authorName; }
    public String getBody() { return body; }
    public void edit(String body) { this.body = body.strip(); }
}
