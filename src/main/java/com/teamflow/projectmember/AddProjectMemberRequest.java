package com.teamflow.projectmember;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record AddProjectMemberRequest(@NotNull UUID userId) {}
