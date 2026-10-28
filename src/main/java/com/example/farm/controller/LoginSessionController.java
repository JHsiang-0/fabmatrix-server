package com.example.farm.controller;

import com.example.farm.common.api.Result;
import com.example.farm.common.utils.SecurityContextUtil;
import com.example.farm.service.LoginSessionService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/v1/auth")
public class LoginSessionController {
    private final LoginSessionService sessions;

    @GetMapping("/sessions")
    public Result<List<LoginSessionService.SessionView>> own(HttpServletRequest request) {
        return Result.success(sessions.list(SecurityContextUtil.getCurrentUserId(), sessionId(request)));
    }

    @PostMapping("/sessions/{id}/revoke")
    public Result<Void> revoke(@PathVariable String id) {
        sessions.revoke(SecurityContextUtil.getCurrentUserId(), id);
        return Result.success(null);
    }

    @PostMapping("/sessions/revoke-others")
    public Result<Void> revokeOthers(HttpServletRequest request) {
        sessions.revokeAll(SecurityContextUtil.getCurrentUserId(), sessionId(request));
        return Result.success(null);
    }

    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest request) {
        sessions.revoke(SecurityContextUtil.getCurrentUserId(), sessionId(request));
        return Result.success(null);
    }

    @GetMapping("/admin/users/{userId}/sessions")
    public Result<List<LoginSessionService.SessionView>> list(@PathVariable Long userId, HttpServletRequest request) {
        return Result.success(sessions.list(userId, sessionId(request)));
    }

    @PostMapping("/admin/users/{userId}/sessions/{id}/revoke")
    public Result<Void> adminRevoke(@PathVariable Long userId, @PathVariable String id) {
        sessions.revoke(userId, id);
        return Result.success(null);
    }

    @PostMapping("/admin/users/{userId}/sessions/revoke-all")
    public Result<Void> revokeAll(@PathVariable Long userId) {
        sessions.revokeAll(userId, null);
        return Result.success(null);
    }

    private String sessionId(HttpServletRequest request) {
        return (String) request.getAttribute("loginSessionId");
    }
}
