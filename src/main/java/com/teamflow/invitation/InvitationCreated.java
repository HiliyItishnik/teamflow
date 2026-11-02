package com.teamflow.invitation;

public record InvitationCreated(InvitationResponse invitation, String token) {
    @Override public String toString() { return "InvitationCreated[token redacted]"; }
}
