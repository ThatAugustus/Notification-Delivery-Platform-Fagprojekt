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
}