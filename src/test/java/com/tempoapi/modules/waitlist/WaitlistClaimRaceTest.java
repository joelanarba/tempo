package com.tempoapi.modules.waitlist;

import com.tempoapi.AbstractIntegrationTest;
import com.tempoapi.entity.*;
import com.tempoapi.exception.OfferExpiredException;
import com.tempoapi.modules.bookings.BookingService;
import com.tempoapi.modules.bookings.dto.CreateBookingRequest;
import com.tempoapi.modules.waitlist.dto.JoinWaitlistRequest;
import com.tempoapi.repository.*;
import com.tempoapi.support.MutableClock;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import(WaitlistClaimRaceTest.Time.class)
class WaitlistClaimRaceTest extends AbstractIntegrationTest {
    @TestConfiguration
    static class Time {
        @Bean @Primary MutableClock controlledClock() { return new MutableClock(Instant.now()); }
    }

    @Autowired MutableClock clock;
    @Autowired BookingService bookings;
    @Autowired WaitlistService waitlist;
    @Autowired BookingRepository bookingRepository;
    @Autowired WaitlistEntryRepository entries;
    @Autowired ResourceRepository resources;
    @Autowired SpaceRepository spaces;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired JdbcTemplate jdbc;

    private record Offer(UUID userId, UUID entryId, UUID holdId, Instant expiresAt) { }

    @BeforeEach
    void resetClock() { clock.set(Instant.now()); }

    private Offer offer() {
        Space space = spaces.save(new Space("Hub", "UTC", "", LocalTime.of(8, 0), LocalTime.of(20, 0)));
        Resource resource = resources.save(new Resource(space, "Desk", ResourceType.DESK, null));
        User owner = persistUser(Role.MEMBER);
        User waiter = persistUser(Role.MEMBER);
        OffsetDateTime start = LocalDate.now(clock).plusDays(1).atTime(9, 0).atOffset(ZoneOffset.UTC);
        var booking = bookings.createBooking(owner.getId(), new CreateBookingRequest(resource.getId(), start, start.plusHours(1)));
        var entry = waitlist.join(waiter.getId(), new JoinWaitlistRequest(resource.getId(), start, start.plusHours(1)));
        bookings.cancelBooking(owner.getId(), booking.id());
        WaitlistEntry offered = entries.findById(entry.id()).orElseThrow();
        UUID holdId = offered.getHeldBooking().getId();
        return new Offer(waiter.getId(), entry.id(), holdId, bookingRepository.findById(holdId).orElseThrow().getHoldExpiresAt());
    }

    private void awaitLatch(CountDownLatch latch) {
        try { assertThat(latch.await(10, TimeUnit.SECONDS)).isTrue(); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException(ex); }
    }

    private void awaitDatabaseBlock(int blocked, int holder) {
        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT CAST(? AS integer) = ANY(pg_blocking_pids(?))",
                        Boolean.class, holder, blocked)).isTrue());
    }

    @Test
    void claimTransactionWinsWhileExpiryCompetesBeforeItsCommit() throws Exception {
        Offer offer = offer();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch competing = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        AtomicInteger blockedPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> claim = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                waitlist.claim(offer.userId(), offer.entryId());
                claimed.countDown();
                awaitLatch(commit);
            }));
            awaitLatch(claimed);
            clock.set(offer.expiresAt().plusSeconds(1));
            Future<?> expiry = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                blockedPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                competing.countDown();
                waitlist.expireHold(offer.holdId());
            }));
            awaitLatch(competing);
            awaitDatabaseBlock(blockedPid.get(), holderPid.get());
            commit.countDown();
            claim.get(30, TimeUnit.SECONDS);
            expiry.get(30, TimeUnit.SECONDS);
            assertThat(bookingRepository.findById(offer.holdId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.CONFIRMED);
            assertThat(entries.findById(offer.entryId()).orElseThrow().getStatus()).isEqualTo(WaitlistStatus.FULFILLED);
        } finally { commit.countDown(); pool.shutdownNow(); }
    }

    @Test
    void expiryTransactionWinsWhileClaimCompetesBeforeItsCommit() throws Exception {
        Offer offer = offer();
        clock.set(offer.expiresAt().plusSeconds(1));
        CountDownLatch expired = new CountDownLatch(1);
        CountDownLatch competing = new CountDownLatch(1);
        CountDownLatch commit = new CountDownLatch(1);
        AtomicInteger holderPid = new AtomicInteger();
        AtomicInteger blockedPid = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> expiry = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                holderPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                waitlist.expireHold(offer.holdId());
                expired.countDown();
                awaitLatch(commit);
            }));
            awaitLatch(expired);
            Future<Boolean> claim = pool.submit(() -> {
                try {
                    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                        blockedPid.set(jdbc.queryForObject("SELECT pg_backend_pid()", Integer.class));
                        competing.countDown();
                        waitlist.claim(offer.userId(), offer.entryId());
                    });
                    return false;
                } catch (OfferExpiredException ex) {
                    return true;
                }
            });
            awaitLatch(competing);
            awaitDatabaseBlock(blockedPid.get(), holderPid.get());
            commit.countDown();
            expiry.get(30, TimeUnit.SECONDS);
            assertThat(claim.get(30, TimeUnit.SECONDS)).isTrue();
            assertThat(bookingRepository.findById(offer.holdId()).orElseThrow().getStatus()).isEqualTo(BookingStatus.EXPIRED);
            assertThat(entries.findById(offer.entryId()).orElseThrow().getStatus()).isEqualTo(WaitlistStatus.EXPIRED);
        } finally { commit.countDown(); pool.shutdownNow(); }
    }
}
