package com.app.demo.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
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

    @Bean
    public TopicExchange deadLetterExchange() {
        return new TopicExchange("dead-letter-exchange");
    }
 
    @Bean
    public Queue deadLetterQueue() {
        return new Queue("dead-letter-queue", true);
    }
 
    @Bean
    public Binding deadLetterBinding(Queue deadLetterQueue, TopicExchange deadLetterExchange) {
        return BindingBuilder
                .bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with("#"); // catch-all: any routing key
    }

    // Email queue
    // The queue where email messages wait to be consumed
    @Bean
    public Queue emailQueue() {
        return QueueBuilder.durable("email-queue")
                .withArgument("x-dead-letter-exchange", "dead-letter-exchange")
                .build();
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


    // Webhook queue
    @Bean
    public Queue webhookQueue() {
        return QueueBuilder.durable("webhook-queue")
                .withArgument("x-dead-letter-exchange", "dead-letter-exchange")
                .build();
    }

    @Bean
    public Binding webhookBinding(Queue webhookQueue, TopicExchange notificationsExchange) {
        return BindingBuilder
                .bind(webhookQueue)
                .to(notificationsExchange)
                .with("notification.webhook");
    }
}