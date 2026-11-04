package com.chegg.pos.model;

/** Authenticated identity used by the UI shell and, later, the permission layer. */
public record User(
        long id,
        String username,
        String fullName,
        String roleCode,
        String roleName,
        boolean mustChangePassword
) {
    public String firstName() {
        if (fullName == null || fullName.isBlank()) {
            return username;
        }
        int space = fullName.indexOf(' ');
        return space < 0 ? fullName : fullName.substring(0, space);
    }
}
