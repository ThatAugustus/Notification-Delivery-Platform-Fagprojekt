package com.app.demo.model.enums;

public enum NotificationStatus {
    ACCEPTED,
    QUEUED,
    PROCESSING,
    DELIVERED,
    FAILED,
    //DEAD_LETTERED, // not part of MVP
    RETRY_SCHEDULED
}
