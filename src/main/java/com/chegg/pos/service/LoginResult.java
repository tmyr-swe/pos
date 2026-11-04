package com.chegg.pos.service;

import com.chegg.pos.model.User;

/** Outcome of a sign-in attempt. Failures deliberately do not reveal whether a username exists. */
public record LoginResult(boolean success, User user, String message) {

    public static LoginResult success(User user) {
        return new LoginResult(true, user, null);
    }

    public static LoginResult failure(String message) {
        return new LoginResult(false, null, message);
    }
}
