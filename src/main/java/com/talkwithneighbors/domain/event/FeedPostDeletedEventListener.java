package com.talkwithneighbors.domain.event;

import com.talkwithneighbors.service.MediaStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
@RequiredArgsConstructor
public class FeedPostDeletedEventListener {
    private final MediaStorageService mediaStorageService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onPostDeleted(FeedPostDeletedEvent event) {
        mediaStorageService.deleteMedia(event.mediaUrls());
    }
}
