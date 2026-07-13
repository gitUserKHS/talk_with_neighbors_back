package com.talkwithneighbors.dto.feed;

import com.talkwithneighbors.entity.FeedPost;
import com.talkwithneighbors.entity.User;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;

class FeedPostDtoTest {

    @Test
    void copiesCollectionFieldsSoTheDtoDoesNotExposeLazyHibernateCollections() {
        User author = new User();
        author.setId(1L);
        author.setUsername("author");

        List<String> tags = new ArrayList<>(List.of("reading", "cafe"));
        List<String> sharedInterests = new ArrayList<>(List.of("reading"));
        FeedPost post = new FeedPost();
        post.setId("post-1");
        post.setAuthor(author);
        post.setInterestTags(tags);

        FeedPostDto dto = FeedPostDto.fromEntity(post, 0, 0, false, 80, sharedInterests);

        assertEquals(tags, dto.getInterestTags());
        assertEquals(sharedInterests, dto.getSharedInterests());
        assertNotSame(tags, dto.getInterestTags());
        assertNotSame(sharedInterests, dto.getSharedInterests());
    }
}
