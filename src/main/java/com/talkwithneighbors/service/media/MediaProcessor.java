package com.talkwithneighbors.service.media;

import java.io.IOException;

public interface MediaProcessor {
    ProcessedMedia process(MediaProcessingRequest request) throws IOException;
}
