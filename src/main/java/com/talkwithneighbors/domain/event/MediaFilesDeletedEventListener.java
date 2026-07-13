package com.talkwithneighbors.domain.event;

import com.talkwithneighbors.service.MediaStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class MediaFilesDeletedEventListener {
    private final MediaStorageService mediaStorageService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMediaFilesDeleted(MediaFilesDeletedEvent event) {
        mediaStorageService.deleteMedia(event.mediaUrls());
    }
}
