package com.teamflow.common;

import java.util.UUID;

public interface VersionedResponse {
    UUID id();
    long version();
}
