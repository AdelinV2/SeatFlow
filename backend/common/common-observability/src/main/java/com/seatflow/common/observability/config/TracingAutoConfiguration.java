package com.seatflow.common.observability.config;

import com.seatflow.common.observability.tracing.KafkaListenerTraceScope;
import com.seatflow.common.observability.tracing.W3cTraceContextPropagator;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
public class TracingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public W3cTraceContextPropagator w3cTraceContextPropagator() {
        return new W3cTraceContextPropagator();
    }

    @Bean
    @ConditionalOnMissingBean
    public KafkaListenerTraceScope kafkaListenerTraceScope(W3cTraceContextPropagator propagator,
                                                           ObjectProvider<Tracer> tracerProvider) {
        return new KafkaListenerTraceScope(propagator, tracerProvider);
    }
}
