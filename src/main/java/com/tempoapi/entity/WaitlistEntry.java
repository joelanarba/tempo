package com.tempoapi.entity;

import io.hypersistence.utils.hibernate.type.range.PostgreSQLRangeType;
import io.hypersistence.utils.hibernate.type.range.Range;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.Type;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.util.UUID;

@Entity
@Table(name = "waitlist_entries")
public class WaitlistEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "resource_id", nullable = false)
    private Resource resource;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "space_id", nullable = false)
    private Space space;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Type(PostgreSQLRangeType.class)
    @Column(name = "time_range", nullable = false, columnDefinition = "tstzrange")
    private Range<ZonedDateTime> timeRange;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private WaitlistStatus status;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "held_booking_id")
    private Booking heldBooking;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    public WaitlistEntry() {
    }

    public WaitlistEntry(Resource resource, Space space, User user,
                         Range<ZonedDateTime> timeRange, WaitlistStatus status) {
        this.resource = resource;
        this.space = space;
        this.user = user;
        this.timeRange = timeRange;
        this.status = status;
    }

    public UUID getId() {
        return id;
    }

    public Resource getResource() {
        return resource;
    }

    public Space getSpace() {
        return space;
    }

    public User getUser() {
        return user;
    }

    public Range<ZonedDateTime> getTimeRange() {
        return timeRange;
    }

    public WaitlistStatus getStatus() {
        return status;
    }

    public void setStatus(WaitlistStatus status) {
        this.status = status;
    }

    public Booking getHeldBooking() {
        return heldBooking;
    }

    public void setHeldBooking(Booking heldBooking) {
        this.heldBooking = heldBooking;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
