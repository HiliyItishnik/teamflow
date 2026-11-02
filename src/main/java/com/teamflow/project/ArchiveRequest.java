package com.teamflow.project;

import jakarta.validation.constraints.NotNull;

public record ArchiveRequest(@NotNull Boolean archived) {}
