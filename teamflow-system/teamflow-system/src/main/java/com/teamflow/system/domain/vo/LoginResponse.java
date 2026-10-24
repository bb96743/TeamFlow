package com.teamflow.system.domain.vo;

public record LoginResponse(String token, long expiresIn) {
}
