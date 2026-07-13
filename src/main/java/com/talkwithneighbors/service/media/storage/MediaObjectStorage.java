package com.talkwithneighbors.service.media.storage;

import java.nio.file.Path;

/**
 * Final storage for processed media objects. Implementations must never expose
 * provider credentials in the public URL; callers persist only relative keys.
 */
public interface MediaObjectStorage {

    void store(String relativeKey, Path source, String contentType);

    void delete(String relativeKey);

    void checkHealth();
}
