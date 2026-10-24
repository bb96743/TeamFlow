package com.teamflow.framework.security;

public interface PermissionResolver {

    boolean hasPermission(String username, String permission);
}
