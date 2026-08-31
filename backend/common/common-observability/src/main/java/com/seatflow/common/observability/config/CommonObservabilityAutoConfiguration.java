package com.seatflow.common.observability.config;

import com.seatflow.common.observability.filter.MdcLoggingFilter;
import com.seatflow.common.observability.handler.GlobalExceptionHandler;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class CommonObservabilityAutoConfiguration {

    @Bean
    public GlobalExceptionHandler globalExceptionHandler() {
        return new GlobalExceptionHandler();
    }

    @Bean
    public MdcLoggingFilter mdcLoggingFilter(ObjectProvider<Tracer> tracerProvider) {
        return new MdcLoggingFilter(tracerProvider);
    }
}
