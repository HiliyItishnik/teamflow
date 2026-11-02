package com.teamflow.invitation;

import com.teamflow.auth.SecretRequest;
import com.teamflow.common.PageResponse;
import com.teamflow.membership.MemberResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class InvitationController {
    private final InvitationService service;
    public InvitationController(InvitationService service) { this.service = service; }

    @PostMapping("/workspaces/{workspaceId}/invitations")
    public ResponseEntity<InvitationCreated> create(@PathVariable UUID workspaceId, @Valid @RequestBody InvitationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(service.create(workspaceId, request));
    }

    @GetMapping("/workspaces/{workspaceId}/invitations")
    public PageResponse<InvitationResponse> list(@PathVariable UUID workspaceId,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(workspaceId, page, size);
    }

    @DeleteMapping("/workspaces/{workspaceId}/invitations/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable UUID workspaceId, @PathVariable UUID id) { service.revoke(workspaceId, id); }

    @PostMapping("/invitations/accept")
    public MemberResponse accept(@Valid @RequestBody SecretRequest request) { return service.accept(request.token()); }
}
