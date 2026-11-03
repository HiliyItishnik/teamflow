package com.teamflow.notification;

import com.teamflow.common.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final NotificationService service;
    public NotificationController(NotificationService service) { this.service = service; }
    @GetMapping
    public PageResponse<NotificationResponse> list(@RequestParam(defaultValue = "false") boolean unread,
            @RequestParam(defaultValue = "0") @Min(0) int page, @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return service.list(unread, page, size);
    }
    @GetMapping("/unread-count")
    public Map<String, Long> unread() { return Map.of("count", service.unreadCount()); }
    @PutMapping("/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@PathVariable UUID id) { service.read(id); }
    @PutMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void readAll() { service.readAll(); }
}
