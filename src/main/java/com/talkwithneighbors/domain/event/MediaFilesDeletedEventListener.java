package com.talkwithneighbors.domain.event;

import com.talkwithneighbors.service.MediaStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MediaFilesDeletedEventListener {
    private final MediaStorageService mediaStorageService;

    @EventListener
    public void onMediaFilesDeleted(MediaFilesDeletedEvent event) {
        mediaStorageService.deleteMediaOrThrow(event.mediaUrls());
    }
}
