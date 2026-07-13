package com.talkwithneighbors.service.media.storage;

import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

public final class MediaStoragePath {

    public static final String PUBLIC_ROOT = "/uploads/";

    private static final Set<String> CATEGORIES = Set.of("feed", "profile", "chat");
    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    private MediaStoragePath() {
    }

    public static String relativeKey(String category, String fileName) {
        return validateRelativeKey(category + "/" + fileName);
    }

    public static String publicUrl(String relativeKey) {
        return PUBLIC_ROOT + validateRelativeKey(relativeKey);
    }

    public static Optional<String> fromPublicUrl(String url) {
        if (url == null || !url.startsWith(PUBLIC_ROOT)) {
            return Optional.empty();
        }
        try {
            return Optional.of(validateRelativeKey(url.substring(PUBLIC_ROOT.length())));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    public static String validateRelativeKey(String relativeKey) {
        if (relativeKey == null
                || relativeKey.isBlank()
                || relativeKey.contains("\\")
                || relativeKey.contains("?")
                || relativeKey.contains("#")) {
            throw new IllegalArgumentException("Invalid media object key");
        }
        String[] segments = relativeKey.split("/", -1);
        if (segments.length != 2
                || !CATEGORIES.contains(segments[0])
                || !FILE_NAME.matcher(segments[1]).matches()
                || ".".equals(segments[1])
                || "..".equals(segments[1])) {
            throw new IllegalArgumentException("Invalid media object key");
        }
        return segments[0] + "/" + segments[1];
    }
}
