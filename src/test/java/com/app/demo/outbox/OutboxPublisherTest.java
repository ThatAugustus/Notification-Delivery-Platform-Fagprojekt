package com.app.demo.outbox;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import org.mockito.InOrder;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import com.app.demo.config.OutboxFairnessConfig;
import com.app.demo.model.Notification;
import com.app.demo.model.OutboxEvent;
import com.app.demo.model.Tenant;
import com.app.demo.model.enums.NotificationChannel;
import com.app.demo.repository.NotificationRepository;
import com.app.demo.repository.OutboxEventRepository;
import com.app.demo.repository.PendingOutboxTenantView;
import com.app.demo.service.TenantQueueLifecycleService;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class OutboxPublisherTest {

    private static final int BATCH_SIZE = 100;

    @Test
    void pollAndPublish_interleavesTenantsAccordingToConfiguredWeights() {
        OutboxEventRepository outboxEventRepository = mock(OutboxEventRepository.class);
        NotificationRepository notificationRepository = mock(NotificationRepository.class);
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        TenantQueueLifecycleService queueLifecycleService = mock(TenantQueueLifecycleService.class);
        OutboxFairnessConfig outboxFairnessConfig = new OutboxFairnessConfig();
        outboxFairnessConfig.setWeights(Map.of("tenant-a", 1, "tenant-b", 2));

        PendingOutboxTenantView tenantA = tenantView(UUID.fromString("11111111-1111-1111-1111-111111111111"), "tenant-a");
        PendingOutboxTenantView tenantB = tenantView(UUID.fromString("22222222-2222-2222-2222-222222222222"), "tenant-b");

        List<OutboxEvent> tenantAEvents = List.of(
            event(tenantA, "a-1", "payload-a-1"),
            event(tenantA, "a-2", "payload-a-2"));
        List<OutboxEvent> tenantBEvents = List.of(
            event(tenantB, "b-1", "payload-b-1"),
            event(tenantB, "b-2", "payload-b-2"),
            event(tenantB, "b-3", "payload-b-3"),
            event(tenantB, "b-4", "payload-b-4"));

        when(outboxEventRepository.findPendingTenants(BATCH_SIZE)).thenReturn(List.of(tenantA, tenantB));
        when(outboxEventRepository.findUnpublishedBatchForTenant(eq(tenantA.getTenantId()), eq(BATCH_SIZE)))
            .thenReturn(tenantAEvents);
        when(outboxEventRepository.findUnpublishedBatchForTenant(eq(tenantB.getTenantId()), eq(BATCH_SIZE)))
            .thenReturn(tenantBEvents);

        when(queueLifecycleService.routingKeyFor(any(), any())).thenAnswer(invocation -> {
            UUID tenantId = invocation.getArgument(1);
            return tenantId.equals(tenantA.getTenantId()) ? "rk-a" : "rk-b";
        });

        when(outboxEventRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(notificationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        OutboxPublisher publisher = new OutboxPublisher(
                outboxEventRepository,
                notificationRepository,
                rabbitTemplate,
                queueLifecycleService,
                outboxFairnessConfig,
                new SimpleMeterRegistry());

        publisher.pollAndPublish();

        InOrder inOrder = inOrder(rabbitTemplate);
        inOrder.verify(rabbitTemplate).convertAndSend("notifications-exchange", "rk-a", "payload-a-1");
        inOrder.verify(rabbitTemplate).convertAndSend("notifications-exchange", "rk-b", "payload-b-1");
        inOrder.verify(rabbitTemplate).convertAndSend("notifications-exchange", "rk-b", "payload-b-2");
        inOrder.verify(rabbitTemplate).convertAndSend("notifications-exchange", "rk-a", "payload-a-2");
        inOrder.verify(rabbitTemplate).convertAndSend("notifications-exchange", "rk-b", "payload-b-3");
        inOrder.verify(rabbitTemplate).convertAndSend("notifications-exchange", "rk-b", "payload-b-4");
    }

    private PendingOutboxTenantView tenantView(UUID tenantId, String tenantName) {
        PendingOutboxTenantView view = mock(PendingOutboxTenantView.class);
        when(view.getTenantId()).thenReturn(tenantId);
        when(view.getTenantName()).thenReturn(tenantName);
        return view;
    }

    private OutboxEvent event(PendingOutboxTenantView tenantView, String notificationIdSuffix, String payload) {
        Tenant tenant = new Tenant(tenantView.getTenantName());
        tenant.setId(tenantView.getTenantId());

        Notification notification = new Notification(tenant, NotificationChannel.EMAIL, "recipient@example.com", "subject", "body");
        notification.setId(UUID.nameUUIDFromBytes(notificationIdSuffix.getBytes()));

        OutboxEvent outboxEvent = new OutboxEvent(notification, payload);
        outboxEvent.setNotification(notification);
        return outboxEvent;
    }
}