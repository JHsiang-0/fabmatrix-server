package com.example.farm.common;

import com.example.farm.common.utils.InMemoryCache;
import org.junit.jupiter.api.Test;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

class InMemoryCacheTest {
    @Test
    void incrementKeepsExistingExpiryAndOwnedLocksCannotBeReleasedByOthers() {
        var cache = new InMemoryCache();
        cache.increment("attempts", 1, 60, TimeUnit.SECONDS);
        cache.increment("attempts", 1, 60, TimeUnit.SECONDS);
        assertThat(cache.getExpire("attempts", TimeUnit.SECONDS)).isBetween(0L, 60L);
        assertThat(cache.tryLock("device", "owner", 60, TimeUnit.SECONDS)).isTrue();
        assertThat(cache.releaseLockIfOwned("device", "other")).isFalse();
        assertThat(cache.releaseLockIfOwned("device", "owner")).isTrue();
    }

    @Test
    void heartbeatScoresExpireOnlyTheIntendedDevices() {
        var cache = new InMemoryCache();
        cache.zSetAdd("heartbeats", "old", 10);
        cache.zSetAdd("heartbeats", "new", 20);
        assertThat(cache.zSetRemoveRangeByScore("heartbeats", 0, 15)).isEqualTo(1);
        assertThat(cache.zSetSize("heartbeats")).isEqualTo(1);
    }

    @Test
    void expiredCollectionsAreClearedBeforeReuse() {
        var cache = new InMemoryCache();
        cache.setAdd("busy", "old");
        assertThat(cache.expire("busy", 0, TimeUnit.MILLISECONDS)).isTrue();
        cache.setAdd("busy", "new");
        assertThat(cache.setIsMember("busy", "old")).isFalse();
        assertThat(cache.setIsMember("busy", "new")).isTrue();
        cache.listLeftPush("history", "old");
        assertThat(cache.expire("history", 0, TimeUnit.MILLISECONDS)).isTrue();
        cache.listLeftPush("history", "new");
        assertThat(cache.listRange("history", 0, 5, String.class)).containsExactly("new");
    }
}
