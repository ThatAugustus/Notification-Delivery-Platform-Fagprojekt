package com.app.demo.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // The exchange — receives messages and routes them to queues
    @Bean
    public TopicExchange notificationsExchange() {
        return new TopicExchange("notifications-exchange");
    }

    // The queue where email messages wait to be consumed
    @Bean
    public Queue emailQueue() {
        return new Queue("email-queue", true); // true = durable (survives RabbitMQ restart)
    }

    // The binding — tells the exchange: "messages with routing key
    // 'notification.email' go to email-queue"
    @Bean
    public Binding emailBinding(Queue emailQueue, TopicExchange notificationsExchange) {
        return BindingBuilder
                .bind(emailQueue)
                .to(notificationsExchange)
                .with("notification.email");
    }
}